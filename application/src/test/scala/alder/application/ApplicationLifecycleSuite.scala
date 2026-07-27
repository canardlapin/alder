package alder.application

import alder.data.*
import alder.kernel.*
import alder.metrics.*
import scala.compiletime.testing.typeCheckErrors

class ApplicationLifecycleSuite extends munit.FunSuite:
  private type Observation = Example[Double, Double, String]

  private val component =
    ComponentDescriptor(
      ComponentId("alder.test.identity-regression"),
      ComponentVersion("1"),
      AuditValue.record(),
      BackendFingerprint("test", "1", AuditValue.record())
    )

  private val context =
    FitContext.root(
      Seed(71L),
      PlanFingerprint.content("sha256", "identity-regression-v1"),
      SchemaFingerprint("double"),
      NumericMode.Deterministic
    )

  private def source(
      count: Int,
      identity: String
  ): Data[Use.Unsplit, Observation] =
    InMemoryData.unsplit(
      Vector.tabulate(count) { index =>
        val value = index.toDouble + 1.0
        Example(value, value, s"row-$index")
      },
      new DataFingerprint(
        FingerprintPolicy.ContentDigest("sha256"),
        identity
      )
    )

  private def rows(value: Long): Rows =
    Rows(value) match
      case Right(result) => result
      case Left(error)   => fail(s"unexpected Rows error: $error")

  private def identity(
      fittedOn: NonEmptyData[Use.Fit, Observation]
  ): Trained[Pipe[Double, Nothing, Double]] =
    context.complete(Pipe.identity[Double], fittedOn, component)

  private def validationEvaluation(
      split: ValidationSplit[Observation]
  ): ScoredEvaluation[
    Use.Validation,
    Double,
    Double,
    String,
    Double,
    RootMeanSquaredError,
    ObjectiveMetric[
      Scored[Double, Double, String],
      RootMeanSquaredError
    ]
  ] =
    val sources =
      EvaluationSources
        .validation(split.train, split.validation.data) match
        case Left(error)  => fail(s"unexpected source error: $error")
        case Right(value) => value
    val metric = RegressionMetrics.rmse[String]
    Evaluation.scored(identity(split.train), sources, metric) match
      case Left(error)  => fail(s"unexpected evaluation error: $error")
      case Right(value) => value

  test("scored validation preserves RowId, truth, prediction, and metadata") {
    val split =
      Split.validation(
        source(6, "validation-source"),
        ValidationSpec(rows(2L)),
        Seed(9L)
      ) match
        case Left(error)  => fail(s"unexpected split error: $error")
        case Right(value) => value
    val evaluated = validationEvaluation(split)
    val expected =
      split.validation.data.foldRows(
        Vector.empty[(Long, Observation)]
      )((rows, id, value) => rows :+ (id.value, value))
    val actual =
      evaluated.scored.data.foldRows(
        Vector.empty[(Long, Scored[Double, Double, String])]
      )((rows, id, value) => rows :+ (id.value, value))

    assertEquals(
      actual.map(_._1),
      expected.map(_._1)
    )
    assertEquals(
      actual.map(_._2.truth),
      expected.map(_._2.target)
    )
    assertEquals(
      actual.map(_._2.prediction),
      expected.map(_._2.input)
    )
    assertEquals(
      actual.map(_._2.meta),
      expected.map(_._2.meta)
    )
    assertEquals(evaluated.score.value, 0.0)
    assertEquals(
      evaluated.receipt.metric,
      RegressionMetrics.rmse[String].descriptor
    )
  }

  test("phase seeds are stable, plan-scoped, and domain separated") {
    val plan = PlanFingerprint.content("sha256", "phase-plan")
    val first = PhaseSeeds(Seed(99L), plan)
    val replay = PhaseSeeds(Seed(99L), plan)
    assertEquals(first.split, replay.split)
    assertEquals(first.candidateFit, replay.candidateFit)
    assertNotEquals(first.split, first.candidateFit)
    assertNotEquals(first.validation, first.test)
    assertNotEquals(first.selectedRefit, first.deploymentRefit)
    assertNotEquals(
      first.split,
      PhaseSeeds(
        Seed(99L),
        PlanFingerprint.content("sha256", "other-plan")
      ).split
    )
    assertNotEquals(
      first.split,
      PhaseSeeds(
        Seed(99L),
        PlanFingerprint(
          FingerprintPolicy.Summary("sha256"),
          "phase-plan"
        )
      ).split
    )
  }

  test("metric failure emits no EvaluationReceipt") {
    val invalid = InMemoryData.unsplit(
      Vector.tabulate(4)(index =>
        Example(index.toDouble, Double.NaN, s"row-$index")
      ),
      new DataFingerprint(
        FingerprintPolicy.ContentDigest("sha256"),
        "invalid-truth"
      )
    )
    val split =
      Split.validation(
        invalid,
        ValidationSpec(rows(2L)),
        Seed(4L)
      ) match
        case Left(error)  => fail(s"unexpected split error: $error")
        case Right(value) => value
    val sources =
      EvaluationSources
        .validation(split.train, split.validation.data) match
        case Left(error)  => fail(s"unexpected source error: $error")
        case Right(value) => value

    Evaluation.scored(
      identity(split.train),
      sources,
      RegressionMetrics.rmse[String]
    ) match
      case Left(
            ScoredEvaluationError.Metric(
              MetricError.NonFiniteTruth(value)
            )
          ) =>
        assert(value.isNaN)
      case other => fail(s"expected metric failure, got $other")
  }

  test("selection authorizes only its exact observed bundle and only once") {
    val split =
      Split.validation(
        source(6, "selection-source"),
        ValidationSpec(rows(2L)),
        Seed(12L)
      ) match
        case Left(error)  => fail(s"unexpected split error: $error")
        case Right(value) => value
    val first = validationEvaluation(split)
    val replay = validationEvaluation(split)
    val selection = first.select("identity-learner", SingleCandidate)

    Refit.after(selection).from(replay.allObserved) match
      case Left(
            ApplicationRefitError.SelectionReceiptMismatch(id)
          ) =>
        assertEquals(id, selection.id)
      case other => fail(s"expected receipt mismatch, got $other")

    val promoted =
      Refit.after(selection).from(first.allObserved) match
        case Left(error) => fail(s"unexpected promotion error: $error")
        case Right(value) => value
    assertEquals(promoted.size, 6L)
    promoted.refit match
      case None => fail("expected refit audit")
      case Some(audit) =>
        assertEquals(audit.evaluationReceipt, first.receipt.id)
        assertEquals(audit.selectionReceipt, Some(selection.id))
        assertEquals(
          audit.sources.map(_.role),
          Vector(
            ObservedSourceRole.Train,
            ObservedSourceRole.Validation
          )
        )

    Refit.after(selection).from(first.allObserved) match
      case Left(
            ApplicationRefitError.SelectionReceiptAlreadyUsed(id)
          ) =>
        assertEquals(id, selection.id)
      case other => fail(s"expected receipt reuse error, got $other")
  }

  test("selected Train+Validation refit unlocks final Test exactly once") {
    val specification =
      TrainValidationTestSpec(
        SplitAmount.Count(rows(2L)),
        SplitAmount.Count(rows(2L))
      ) match
        case Left(error)  => fail(s"unexpected specification error: $error")
        case Right(value) => value
    val split =
      Split.trainValidationTest(
        source(8, "three-way-source"),
        specification,
        Seed(18L)
      ) match
        case Left(error)  => fail(s"unexpected split error: $error")
        case Right(value) => value
    val validationSources =
      EvaluationSources
        .validation(split.train, split.validation.data) match
        case Left(error)  => fail(s"unexpected source error: $error")
        case Right(value) => value
    val metric = RegressionMetrics.rmse[String]
    val validation =
      Evaluation.scored(
        identity(split.train),
        validationSources,
        metric
      ) match
        case Left(error)  => fail(s"unexpected validation error: $error")
        case Right(value) => value
    val selection =
      validation.select("identity-learner", SingleCandidate)
    val refitData =
      Refit.after(selection).from(validation.allObserved) match
        case Left(error)  => fail(s"unexpected selected refit: $error")
        case Right(value) => value
    val refitModel = identity(refitData)
    val testSources =
      EvaluationSources.finalTest(refitData, split.test.data) match
        case Left(error)  => fail(s"unexpected final-test source: $error")
        case Right(value) => value
    val tested =
      Evaluation.scored(refitModel, testSources, metric) match
        case Left(error)  => fail(s"unexpected Test error: $error")
        case Right(value) => value
    val deploymentData =
      Refit.after(tested.receipt).from(tested.allObserved) match
        case Left(error)  => fail(s"unexpected deployment refit: $error")
        case Right(value) => value

    assertEquals(deploymentData.size, 8L)
    deploymentData.refit match
      case None => fail("expected deployment refit audit")
      case Some(audit) =>
        assertEquals(audit.evaluationReceipt, tested.receipt.id)
        assertEquals(audit.selectionReceipt, Some(selection.id))
        assertEquals(
          audit.sources.map(_.role),
          Vector(
            ObservedSourceRole.Train,
            ObservedSourceRole.Validation,
            ObservedSourceRole.Test
          )
        )
        assertEquals(
          audit.claim,
          RefitEvaluationClaim.ArtifactNotEvaluatedOnAuthorizingTest
        )
  }

  test("precommitted Train/Test evaluation permits deployment refit without selection") {
    val split =
      Split.holdout(
        source(6, "precommitted-source"),
        HoldoutSpec(rows(2L)),
        Seed(21L)
      ) match
        case Left(error)  => fail(s"unexpected split error: $error")
        case Right(value) => value
    val sources =
      EvaluationSources
        .precommittedTest(split.train, split.test.data) match
        case Left(error)  => fail(s"unexpected source error: $error")
        case Right(value) => value
    val tested =
      Evaluation.scored(
        identity(split.train),
        sources,
        RegressionMetrics.rmse[String]
      ) match
        case Left(error)  => fail(s"unexpected evaluation error: $error")
        case Right(value) => value
    val deploymentData =
      Refit.after(tested.receipt).from(tested.allObserved) match
        case Left(error)  => fail(s"unexpected promotion error: $error")
        case Right(value) => value

    assertEquals(deploymentData.size, 6L)
    assertEquals(
      deploymentData.refit.flatMap(_.selectionReceipt),
      None
    )
    assertEquals(
      deploymentData.refit.map(_.sources.map(_.role)),
      Some(Vector(ObservedSourceRole.Train, ObservedSourceRole.Test))
    )
  }

  test("roles, objective capability, and receipt constructors fail at compile time") {
    val validationCannotRefit = typeCheckErrors(
      """package consumer
import alder.application.*
import alder.data.*
import alder.kernel.*
def illegal(
  evaluation: ScoredEvaluation[
    Use.Validation,
    Double,
    Double,
    Unit,
    Double,
    alder.metrics.RootMeanSquaredError,
    alder.metrics.ObjectiveMetric[
      Scored[Double, Double, Unit],
      alder.metrics.RootMeanSquaredError
    ]
  ]
) =
  Refit.after(evaluation.receipt)
"""
    )
    val reportingCannotSelect = typeCheckErrors(
      """package consumer
import alder.application.*
import alder.kernel.*
import alder.metrics.*
def illegal(
  evaluation: ScoredEvaluation[
    Use.Validation,
    Double,
    Double,
    Unit,
    Double,
    Double,
    Metric[Scored[Double, Double, Unit], Double]
  ]
) =
  evaluation.select("learner", SingleCandidate)
"""
    )
    val rolesCannotCross = typeCheckErrors(
      """package consumer
import alder.application.*
import alder.data.*
import alder.kernel.*
def illegal[L, Mt, S, A](
  receipt: SelectionReceipt[L, Mt, S],
  test: AllObserved[Use.Test, A]
) =
  Refit.after(receipt).from(test)
"""
    )
    val receiptCannotBeForged = typeCheckErrors(
      """package consumer
import alder.application.*
import alder.kernel.*
import alder.metrics.*
def forged(existing: EvaluationReceipt[Use.Test]) =
  new EvaluationReceipt[Use.Test](
    EvaluationReceiptId("forged"),
    PredictionReceiptId("forged"),
    Vector.empty,
    EvaluationRole.Test,
    RegressionMetrics.rmse[Unit].descriptor,
    DataFingerprint.external("forged"),
    existing.priorSelection,
    existing.authority
  )
"""
    )
    val observedCannotBeResplit = typeCheckErrors(
      """package consumer
import alder.application.*
import alder.data.*
import alder.kernel.*
def illegal[A](
  observed: AllObserved[Use.Validation, A],
  specification: ValidationSpec
) =
  Split.validation(observed, specification, Seed(0L))
"""
    )

    assert(validationCannotRefit.nonEmpty)
    assert(reportingCannotSelect.nonEmpty)
    assert(rolesCannotCross.nonEmpty)
    assert(receiptCannotBeForged.nonEmpty)
    assert(observedCannotBeResplit.nonEmpty)
  }

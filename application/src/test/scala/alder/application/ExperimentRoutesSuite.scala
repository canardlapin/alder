package alder.application

import alder.data.*
import alder.kernel.*
import alder.metrics.*
import cats.Id
import cats.data.EitherT
import scala.compiletime.testing.typeCheckErrors

class ExperimentRoutesSuite extends munit.FunSuite:
  private type Observation = Example[Double, Double, Unit]

  private val component =
    ComponentDescriptor(
      ComponentId("alder.test.experiment-identity"),
      ComponentVersion("1"),
      AuditValue.record(),
      BackendFingerprint("test", "1", AuditValue.record())
    )

  private final class IdentityLearner
      extends Learner[Id, Double, Double, Unit, Double]:
    type FitError = Nothing
    type RunError = Nothing
    type Model    = Pipe[Double, Nothing, Double]

    def fit[U <: Use.Fit](
        data: NonEmptyData[U, Observation]
    )(using fitContext: FitContext): FitResult[Id, FitError, Trained[Model]] =
      val _ = data
      EitherT.right(
        fitContext.complete(Pipe.identity[Double], data, component)
      )

  private val learner = new IdentityLearner

  private def source(
      count: Int,
      identity: String
  ): Data[Use.Unsplit, Observation] =
    InMemoryData.unsplit(
      Vector.tabulate(count) { index =>
        val value = index.toDouble + 1.0
        Example(value, value, ())
      },
      DataFingerprint.external(identity)
    )

  private def predictionInputs(identity: String): Data[Use.Unsplit, Double] =
    InMemoryData.unsplit(
      Vector(3.0, 1.0, 2.0),
      DataFingerprint.external(identity)
    )

  private def rows(value: Long): Rows =
    Rows(value) match
      case Right(result) => result
      case Left(error)   => fail(s"unexpected Rows error: $error")

  test("validation route select and refit stay on the Experiment façade") {
    val specification = ValidationSpec(rows(2L))
    val validated =
      Experiment
        .validation(
          source(8, "experiment-validation"),
          specification,
          Seed(9L),
          "experiment-validation-v1",
          learner,
          RegressionMetrics.rmse[Unit]
        )
        .run match
        case Left(error)  => fail(s"unexpected validation failure: $error")
        case Right(value) => value

    assertEquals(validated.predictions.size, 2L)
    assertEquals(validated.predict(3.0), validated.model.predict(3.0))
    val inputs = predictionInputs("validation-prediction-inputs")
    val inputIds =
      inputs.foldRows(Vector.empty[RowId])((ids, id, _) => ids :+ id)
    assertEquals(
      validated.predictAll(inputs),
      validated.model.predictAll(inputs)
    )
    assertEquals(validated.predictAll(inputs).map(_.map(_._1)), Right(inputIds))
    val refitted =
      validated
        .select(SingleCandidate)
        .refit match
        case Left(error)  => fail(s"unexpected refit failure: $error")
        case Right(value) => value
    assertEquals(refitted.audit.plan.render, validated.audit.plan.render)
    assertEquals(refitted.predict(3.0), refitted.model.predict(3.0))
    assert(refitted.predict(3.0).contains(3.0))
  }

  test("train-validation-test run then deploymentRefit") {
    val specification =
      TrainValidationTestSpec(
        SplitAmount.Count(rows(2L)),
        SplitAmount.Count(rows(2L))
      ) match
        case Left(error)  => fail(s"unexpected TVT spec: $error")
        case Right(value) => value
    val tested =
      Experiment
        .trainValidationTest(
          source(10, "experiment-tvt"),
          specification,
          Seed(18L),
          "experiment-tvt-v1",
          learner,
          RegressionMetrics.rmse[Unit]
        )
        .run(selection = SingleCandidate) match
        case Left(error)  => fail(s"unexpected TVT failure: $error")
        case Right(value) => value

    assertEquals(tested.evaluation.scored.size, 2L)
    assert(tested.score.value.isFinite)
    assertEquals(tested.predict(4.0), tested.model.predict(4.0))
    val deployed =
      tested.deploymentRefit match
        case Left(error)  => fail(s"unexpected deployment refit: $error")
        case Right(value) => value
    assert(deployed.prior.score.value.isFinite)
    assertEquals(deployed.learner, learner)
    assertEquals(deployed.predict(4.0), deployed.model.predict(4.0))
  }

  test("precommitted run scores without selection") {
    val specification = HoldoutSpec(rows(2L))
    val tested =
      Experiment
        .precommitted(
          source(8, "experiment-precommitted"),
          specification,
          Seed(21L),
          "experiment-precommitted-v1",
          learner,
          RegressionMetrics.rmse[Unit]
        )
        .run match
        case Left(error)  => fail(s"unexpected precommitted failure: $error")
        case Right(value) => value
    assertEquals(tested.evaluation.scored.size, 2L)
    assert(tested.score.value.isFinite)
    assertEquals(tested.predict(5.0), tested.model.predict(5.0))
  }

  private final class FailingLearner
      extends Learner[Id, Double, Double, Unit, Double]:
    type FitError = String
    type RunError = Nothing
    type Model    = Pipe[Double, Nothing, Double]

    def fit[U <: Use.Fit](
        data: NonEmptyData[U, Observation]
    )(using fitContext: FitContext): FitResult[Id, FitError, Trained[Model]] =
      val _ = data
      EitherT.leftT(
        fitContext.stagePath.failure("forced-candidate-fit-failure")
      )

  private final class ConditionalPredictionLearner
      extends Learner[Id, Double, Double, Unit, Double]:
    type FitError = Nothing
    type RunError = String
    type Model    = Pipe[Double, String, Double]

    def fit[U <: Use.Fit](
        data: NonEmptyData[U, Observation]
    )(using fitContext: FitContext): FitResult[Id, FitError, Trained[Model]] =
      val model = new Pipe[Double, String, Double]:
        def run(value: Double): Either[Failure[String], Double] =
          if value < 0.0 then
            Left(fitContext.stagePath.failure("negative-input"))
          else Right(value)
      EitherT.right(fitContext.complete(model, data, component))

  test("Validated.predict preserves the exact trained failure") {
    val validated =
      Experiment
        .validation(
          source(8, "prediction-failure"),
          ValidationSpec(rows(2L)),
          Seed(31L),
          "prediction-failure-v1",
          new ConditionalPredictionLearner,
          RegressionMetrics.rmse[Unit]
        )
        .run match
        case Left(error)  => fail(s"unexpected validation failure: $error")
        case Right(value) => value

    val throughResult = validated.predict(-1.0)
    val throughModel  = validated.model.predict(-1.0)
    assertEquals(throughResult, throughModel)
    throughResult match
      case Left(failure) =>
        assertEquals(failure.cause, "negative-input")
        assertEquals(failure.stage, validated.audit.preparation.stage)
      case Right(value) => fail(s"expected prediction failure, got $value")
  }

  test("empty source is a definition failure") {
    val empty = InMemoryData.unsplit(
      Vector.empty[Observation],
      DataFingerprint.external("empty-source")
    )
    Experiment
      .validation(
        empty,
        ValidationSpec(rows(1L)),
        Seed(1L),
        "empty-source-v1",
        learner,
        RegressionMetrics.rmse[Unit]
      )
      .run match
      case Left(
            error @ ExperimentFailure.Definition(
              ExperimentDefinitionError.EmptySource
            )
          ) =>
        assertEquals(
          error.render,
          "experiment definition failed: source data is empty"
        )
      case other =>
        fail(s"expected EmptySource, got $other")
  }

  test("oversized validation split is a split failure") {
    Experiment
      .validation(
        source(3, "split-fail"),
        ValidationSpec(rows(3L)),
        Seed(2L),
        PlanFingerprint.external("split-fail-v1"),
        learner,
        RegressionMetrics.rmse[Unit]
      )
      .run match
      case Left(error @ ExperimentFailure.Split(SplitPhase.Partition, _)) =>
        assert(error.render.contains("split phase Partition"))
        assert(error.render.contains("left no training rows"))
      case other =>
        fail(s"expected Split failure, got $other")
  }

  test("candidate fit failure retains Fit phase") {
    Experiment
      .validation(
        source(6, "fit-fail"),
        ValidationSpec(rows(2L)),
        Seed(3L),
        "fit-fail-v1",
        new FailingLearner,
        RegressionMetrics.rmse[Unit]
      )
      .run match
      case Left(ExperimentFailure.Fit(FitPhase.Candidate, failure)) =>
        assertEquals(failure.cause, "forced-candidate-fit-failure")
      case other =>
        fail(s"expected Fit Candidate failure, got $other")
  }

  test("validation stepwise partition fitCandidate validate matches run") {
    val defined =
      Experiment.validation(
        source(8, "stepwise"),
        ValidationSpec(rows(2L)),
        Seed(9L),
        "stepwise-v1",
        learner,
        RegressionMetrics.rmse[Unit]
      )
    val stepwise =
      for
        partitioned <- defined.partition
        fitted      <- partitioned.fitCandidate
        validated   <- fitted.validate
      yield validated
    val direct = defined.run
    (stepwise, direct) match
      case (Right(left), Right(right)) =>
        assertEquals(left.predictions.size, right.predictions.size)
        assertEquals(left.score, right.score)
        assertEquals(left.audit.plan.render, right.audit.plan.render)
      case other =>
        fail(s"unexpected stepwise/direct mismatch: $other")
  }

  test("trainValidationTest stepwise path matches run") {
    val specification =
      TrainValidationTestSpec(
        SplitAmount.Count(rows(2L)),
        SplitAmount.Count(rows(2L))
      ) match
        case Left(error)  => fail(s"unexpected TVT spec: $error")
        case Right(value) => value
    val defined =
      Experiment.trainValidationTest(
        source(10, "tvt-stepwise"),
        specification,
        Seed(18L),
        PlanFingerprint.external("tvt-stepwise-v1"),
        learner,
        RegressionMetrics.rmse[Unit]
      )
    val stepwise =
      for
        partitioned <- defined.partition
        fitted      <- partitioned.fitCandidate
        validated   <- fitted.validate
        refitted    <- validated.select(SingleCandidate).refit
        tested      <- refitted.test
      yield tested
    val direct = defined.run(selection = SingleCandidate)
    (stepwise, direct) match
      case (Right(left), Right(right)) =>
        assertEquals(left.evaluation.scored.size, right.evaluation.scored.size)
        assertEquals(left.score, right.score)
        assertEquals(left.plan.render, right.plan.render)
      case other =>
        fail(s"unexpected TVT stepwise/direct mismatch: $other")
  }

  test("trainValidationTest and precommitted accept Blueprint.Complete") {
    val scaleLearner = learner
    val blueprint    = Blueprint.supervised[Double, Double].learn(scaleLearner)
    val tvt =
      TrainValidationTestSpec(
        SplitAmount.Count(rows(2L)),
        SplitAmount.Count(rows(2L))
      ) match
        case Left(error)  => fail(s"unexpected TVT spec: $error")
        case Right(value) => value
    Experiment
      .trainValidationTest(
        source(10, "tvt-blueprint"),
        tvt,
        Seed(19L),
        PlanFingerprint.external("tvt-blueprint-v1"),
        blueprint,
        RegressionMetrics.rmse[Unit]
      )
      .run(selection = SingleCandidate) match
      case Left(error) => fail(s"unexpected TVT blueprint failure: $error")
      case Right(tested) =>
        assertEquals(tested.evaluation.scored.size, 2L)
    Experiment
      .precommitted(
        source(8, "precommitted-blueprint"),
        HoldoutSpec(rows(2L)),
        Seed(22L),
        PlanFingerprint.external("precommitted-blueprint-v1"),
        blueprint,
        RegressionMetrics.rmse[Unit]
      )
      .run match
      case Left(error) =>
        fail(s"unexpected precommitted blueprint failure: $error")
      case Right(tested) =>
        assertEquals(tested.evaluation.scored.size, 2L)
  }

  test("TVT and precommitted empty sources are definition failures") {
    val empty = InMemoryData.unsplit(
      Vector.empty[Observation],
      DataFingerprint.external("empty-routes")
    )
    val tvt =
      TrainValidationTestSpec(
        SplitAmount.Count(rows(1L)),
        SplitAmount.Count(rows(1L))
      ) match
        case Left(error)  => fail(s"unexpected TVT spec: $error")
        case Right(value) => value
    Experiment
      .trainValidationTest(
        empty,
        tvt,
        Seed(1L),
        "empty-tvt-v1",
        learner,
        RegressionMetrics.rmse[Unit]
      )
      .run(selection = SingleCandidate) match
      case Left(
            ExperimentFailure.Definition(ExperimentDefinitionError.EmptySource)
          ) =>
        ()
      case other =>
        fail(s"expected TVT EmptySource, got $other")
    Experiment
      .precommitted(
        empty,
        HoldoutSpec(rows(1L)),
        Seed(1L),
        "empty-precommitted-v1",
        learner,
        RegressionMetrics.rmse[Unit]
      )
      .run match
      case Left(
            ExperimentFailure.Definition(ExperimentDefinitionError.EmptySource)
          ) =>
        ()
      case other =>
        fail(s"expected precommitted EmptySource, got $other")
  }

  test("precommitted PlanFingerprint overload and deploymentRefit") {
    val tested =
      Experiment
        .precommitted(
          source(8, "precommitted-fingerprint"),
          HoldoutSpec(rows(2L)),
          Seed(21L),
          PlanFingerprint.external("precommitted-fingerprint-v1"),
          learner,
          RegressionMetrics.rmse[Unit]
        )
        .run match
        case Left(error)  => fail(s"unexpected precommitted failure: $error")
        case Right(value) => value
    tested.deploymentRefit match
      case Left(error) => fail(s"unexpected deployment refit: $error")
      case Right(deployed) =>
        assert(deployed.prior.score.value.isFinite)
        assert(deployed.predict(1.0).contains(1.0))
  }

  test("trainValidationTest run requires an explicit selection argument") {
    val errors = typeCheckErrors(
      """import alder.application.*
import alder.kernel.*
import alder.metrics.*
import cats.Id
def illegal[
  L <: Learner[Id, Double, Double, Unit, Double],
  Mt <: ObjectiveMetric[
    Scored[Double, Double, Unit],
    RootMeanSquaredError
  ]
](
  defined: Experiment.TVTDefined[
    Double,
    Double,
    Unit,
    Double,
    L,
    Mt,
    RootMeanSquaredError
  ]
) = defined.run()
"""
    )
    assert(
      errors.exists(_.message.contains("selection")),
      clues(errors.map(_.message))
    )
  }

  test(
    "Experiment Validated.select rejects reporting-only metrics at compile time"
  ) {
    val errors = typeCheckErrors(
      """import alder.application.*
import alder.kernel.*
import alder.metrics.*
import cats.Id
def illegal[
  L <: Learner[Id, Double, Double, Unit, Double],
  R <: ValidationCapableRoute
](
  validated: Experiment.Validated[
    R,
    Double,
    Double,
    Unit,
    Double,
    L,
    Metric[Scored[Double, Double, Unit], RootMeanSquaredError],
    RootMeanSquaredError
  ]
) =
  validated.select(SingleCandidate)
"""
    )
    assert(
      errors.exists(_.message.contains("ObjectiveMetric")),
      clues(errors.map(_.message))
    )
  }

  test("ValidationRoute has no test method") {
    val errors = typeCheckErrors(
      """import alder.application.*
import alder.kernel.*
import alder.metrics.*
import cats.Id
def illegal[
  L <: Learner[Id, Double, Double, Unit, Double]
](
  validated: Experiment.Validated[
    ValidationRoute.type,
    Double,
    Double,
    Unit,
    Double,
    L,
    ObjectiveMetric[Scored[Double, Double, Unit], RootMeanSquaredError],
    RootMeanSquaredError
  ]
) =
  validated.test
"""
    )
    assert(
      errors.exists(error =>
        error.message.contains("test") &&
          error.message.contains("Validated")
      ),
      clues(errors.map(_.message))
    )
  }

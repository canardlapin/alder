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
    type Model = Pipe[Double, Nothing, Double]

    def fit[U <: Use.Fit](
        data: NonEmptyData[U, Observation]
    )(using fitContext: FitContext): FitResult[Id, FitError, Trained[Model]] =
      val _ = data
      EitherT.right(
        fitContext.complete(Pipe.identity[Double], data, component)
      )

  private val learner = new IdentityLearner

  private def source(count: Int, identity: String)
      : Data[Use.Unsplit, Observation] =
    InMemoryData.unsplit(
      Vector.tabulate(count) { index =>
        val value = index.toDouble + 1.0
        Example(value, value, ())
      },
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
        .runToValidated match
        case Left(error)  => fail(s"unexpected validation failure: $error")
        case Right(value) => value

    assertEquals(validated.predictions.size, 2L)
    val refitted =
      validated
        .select(SingleCandidate)
        .refit match
        case Left(error)  => fail(s"unexpected refit failure: $error")
        case Right(value) => value
    assertEquals(refitted.audit.plan.render, validated.audit.plan.render)
    assert(refitted.model.artifact.run(3.0).contains(3.0))
  }

  test("train-validation-test runToTested then deploymentRefit") {
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
        .runToTested match
        case Left(error)  => fail(s"unexpected TVT failure: $error")
        case Right(value) => value

    assertEquals(tested.evaluation.scored.size, 2L)
    assert(tested.score.value.isFinite)
    val deployed =
      tested.deploymentRefit match
        case Left(error)  => fail(s"unexpected deployment refit: $error")
        case Right(value) => value
    assert(deployed.prior.score.value.isFinite)
    assertEquals(deployed.learner, learner)
  }

  test("precommitted runToTested scores without selection") {
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
        .runToTested match
        case Left(error)  => fail(s"unexpected precommitted failure: $error")
        case Right(value) => value
    assertEquals(tested.evaluation.scored.size, 2L)
    assert(tested.score.value.isFinite)
  }

  private final class FailingLearner
      extends Learner[Id, Double, Double, Unit, Double]:
    type FitError = String
    type RunError = Nothing
    type Model = Pipe[Double, Nothing, Double]

    def fit[U <: Use.Fit](
        data: NonEmptyData[U, Observation]
    )(using fitContext: FitContext): FitResult[Id, FitError, Trained[Model]] =
      val _ = data
      EitherT.leftT(fitContext.stagePath.failure("forced-candidate-fit-failure"))

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
      .runToValidated match
      case Left(ExperimentFailure.Definition(ExperimentDefinitionError.EmptySource)) =>
        ()
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
      .runToValidated match
      case Left(ExperimentFailure.Split(SplitPhase.Partition, _)) => ()
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
      .runToValidated match
      case Left(ExperimentFailure.Fit(FitPhase.Candidate, failure)) =>
        assertEquals(failure.cause, "forced-candidate-fit-failure")
      case other =>
        fail(s"expected Fit Candidate failure, got $other")
  }

  test("validation stepwise partition fitCandidate validate matches runToValidated") {
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
        fitted <- partitioned.fitCandidate
        validated <- fitted.validate
      yield validated
    val direct = defined.runToValidated
    (stepwise, direct) match
      case (Right(left), Right(right)) =>
        assertEquals(left.predictions.size, right.predictions.size)
        assertEquals(left.score, right.score)
        assertEquals(left.audit.plan.render, right.audit.plan.render)
      case other =>
        fail(s"unexpected stepwise/direct mismatch: $other")
  }

  test("trainValidationTest stepwise path matches runToTested") {
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
        fitted <- partitioned.fitCandidate
        validated <- fitted.validate
        refitted <- validated.select(SingleCandidate).refit
        tested <- refitted.test
      yield tested
    val direct = defined.runToTested
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
    val blueprint = Blueprint.supervised[Double, Double].learn(scaleLearner)
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
      .runToTested match
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
      .runToTested match
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
      .runToTested match
      case Left(ExperimentFailure.Definition(ExperimentDefinitionError.EmptySource)) =>
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
      .runToTested match
      case Left(ExperimentFailure.Definition(ExperimentDefinitionError.EmptySource)) =>
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
        .runToTested match
        case Left(error)  => fail(s"unexpected precommitted failure: $error")
        case Right(value) => value
    tested.deploymentRefit match
      case Left(error) => fail(s"unexpected deployment refit: $error")
      case Right(deployed) =>
        assert(deployed.prior.score.value.isFinite)
        assert(deployed.trained.artifact.run(1.0).contains(1.0))
  }

  test("Experiment Validated.select rejects reporting-only metrics at compile time") {
    val errors = typeCheckErrors(
      """package consumer
import alder.application.*
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
    assert(errors.nonEmpty)
  }

  test("ValidationRoute has no test method") {
    val errors = typeCheckErrors(
      """package consumer
import alder.application.*
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
    assert(errors.nonEmpty)
  }

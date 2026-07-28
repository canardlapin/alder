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

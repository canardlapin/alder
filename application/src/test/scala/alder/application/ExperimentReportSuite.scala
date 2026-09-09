package alder.application

import alder.data.*
import alder.kernel.*
import alder.metrics.*
import cats.Id
import cats.data.EitherT

class ExperimentReportSuite extends munit.FunSuite:
  private type Observation = Example[Double, Double, Unit]

  private val component =
    ComponentDescriptor(
      ComponentId("alder.test.report-identity"),
      ComponentVersion("1"),
      AuditValue.record(),
      BackendFingerprint("report-test", "1", AuditValue.record())
    )

  private final class IdentityLearner
      extends Learner[Id, Double, Double, Unit, Double]:
    type FitError = Nothing
    type RunError = Nothing
    type Model = Pipe[Double, Nothing, Double]

    def fit[U <: Use.Fit](
        data: NonEmptyData[U, Observation]
    )(using context: FitContext): FitResult[Id, Nothing, Trained[Model]] =
      EitherT.right(context.complete(Pipe.identity[Double], data, component))

  private val learner = new IdentityLearner
  private val metric = RegressionMetrics.rmse[Unit]

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

  test("validation report is a typed projection of retained evidence") {
    val validated =
      Experiment
        .validation(
          data = source(8, "report-validation"),
          specification = ValidationSpec(rows(2L)),
          seed = Seed(42L),
          plan = "report-validation-v1",
          learner = learner,
          metric = metric
        )
        .run match
        case Left(error)  => fail(s"unexpected validation failure: $error")
        case Right(value) => value

    val report = validated.report
    assertEquals(report.route, ExperimentReportRoute.Validation)
    assertEquals(report.partitions, ExperimentPartitions.Validation(6L, 2L))
    assertEquals(report.metric, validated.evaluation.metric.descriptor)
    assertEquals(report.score, validated.score)
    assertEquals(report.plan, validated.plan)
    assertEquals(report.seed, Seed(42L))
    assertEquals(report.candidate.descriptor.id.render, component.id.render)
    assertEquals(report.components.map(_.descriptor.id.render), Vector(component.id.render))
    assertEquals(report.components.map(_.backend.id), Vector("report-test"))
    assertEquals(report.components.map(_.backend.version), Vector("1"))

    val rendered = report.render(score => score.value.toString)
    assert(rendered.contains("Route: validation"))
    assert(rendered.contains("Training rows: 6"))
    assert(rendered.contains("Validation rows: 2"))
    assert(rendered.contains("Metric: root-mean-squared-error"))
    assert(rendered.contains("Candidate: alder.test.report-identity"))
    assert(rendered.contains("Backends: report-test@1"))
    assert(rendered.contains("Run: report-validation-v1"))
    assert(rendered.contains("Seed: 42"))
  }

  test("three-way and precommitted reports retain distinct partition shapes") {
    val threeWay =
      Experiment
        .trainValidationTest(
          data = source(10, "report-tvt"),
          specification = TrainValidationTestSpec(
            SplitAmount.Count(rows(2L)),
            SplitAmount.Count(rows(2L))
          ).toOption.getOrElse(fail("unexpected TVT specification failure")),
          seed = Seed(7L),
          plan = "report-tvt-v1",
          learner = learner,
          metric = metric
        )
        .run(selection = SingleCandidate) match
        case Left(error)  => fail(s"unexpected TVT failure: $error")
        case Right(value) => value

    assertEquals(
      threeWay.report.partitions,
      ExperimentPartitions.TrainValidationTest(6L, 2L, 2L)
    )

    val precommitted =
      Experiment
        .precommitted(
          data = source(8, "report-precommitted"),
          specification = HoldoutSpec(rows(2L)),
          seed = Seed(9L),
          plan = "report-precommitted-v1",
          learner = learner,
          metric = metric
        )
        .run match
        case Left(error)  => fail(s"unexpected precommitted failure: $error")
        case Right(value) => value

    assertEquals(
      precommitted.report.partitions,
      ExperimentPartitions.PrecommittedHoldout(6L, 2L)
    )
  }

  test("error rendering does not replace exact typed failure evidence") {
    val error: ExperimentFailure[String, String] =
      ExperimentFailure.Fit(
        FitPhase.Candidate,
        StagePath(Vector(2, 1)).failure("ill-conditioned")
      )

    assertEquals(
      error.renderWith(fit => s"fit=$fit", run => s"run=$run"),
      "experiment fit phase Candidate failed at /2/1: fit=ill-conditioned"
    )
    error match
      case ExperimentFailure.Fit(FitPhase.Candidate, failure) =>
        assertEquals(failure.cause, "ill-conditioned")
        assertEquals(failure.stage, StagePath(Vector(2, 1)))
      case other => fail(s"typed failure was not retained: $other")
  }

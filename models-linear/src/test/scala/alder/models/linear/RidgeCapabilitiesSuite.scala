package alder.models.linear

import alder.data.*
import alder.kernel.*
import alder.testkit.TestData
import cats.Id
import cats.data.EitherT
import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** Deterministic backend for capability inspection tests. */
private final class UnitRidgeBackend extends RidgeBackend[Id]:
  val fingerprint: BackendFingerprint =
    BackendFingerprint("test-unit", "1", AuditValue.record())

  def solve[X, M, U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Double, M]],
      features: FeatureView[X],
      config: RidgeConfig,
      weights: RowWeights,
      context: BackendContext
  ): FitResult[Id, RidgeBackendError, RidgeSolution] =
    val _ = (data, weights, context, config)
    EitherT.rightT(
      RidgeSolution.create(
        IArray.fill(features.size)(1.0),
        0.0,
        SolverReceipt(
          SolverId.GaleNormalCholesky,
          Some(1),
          TerminationReason.Direct,
          None,
          None,
          Vector.empty,
          fingerprint,
          AuditRecord.empty
        ),
        objective = 0.0,
        kktResidual = 0.0
      )
    )

class RidgeCapabilitiesSuite extends FunSuite:
  final case class Point(x: Double) derives Coordinates, Schema
  final case class StandardPoint(x: Double) derives Coordinates, Schema

  private final class StandardizePoint
      extends Transform.Leaf[Id, Point, StandardPoint]:
    type FitError = Nothing
    type RunError = Nothing
    type Fitted = Pipe[Point, Nothing, StandardPoint]

    protected def descriptor: ComponentDescriptor =
      ComponentDescriptor(
        ComponentId("alder.test.standardize-point"),
        ComponentVersion("1"),
        AuditValue.record(),
        BackendFingerprint("test", "1", AuditValue.record())
      )

    protected def replayFailure(
        failure: Failure[RunError]
    ): Failure[FitError] = failure.widen[FitError]

    protected def fitPipe[U <: Use.Fit](
        data: NonEmptyData[U, Point]
    )(using FitContext): Either[Failure[FitError], Fitted] =
      val _ = data
      Right(Pipe.total(point => StandardPoint(point.x / 2.0)))

  test("Coefficients and Explain inspect the terminal ridge model") {
    val config = RidgeConfig.create(0.1) match
      case Left(error)  => fail(s"config: $error")
      case Right(value) => value
    val learner =
      RidgeRegression.sync[Point, Unit](config, new UnitRidgeBackend)
    val rows =
      Vector(
        Example(Point(1.0), 2.0, ()),
        Example(Point(2.0), 4.0, ()),
        Example(Point(3.0), 6.0, ())
      )
    val data =
      TestData.indexed[Use.Train, Example[Point, Double, Unit]](
        rows,
        DataFingerprint.external("ridge-capabilities")
      ) match
        case Some(value) => value
        case None        => fail("expected data")
    val trained =
      Fit.learner(
        learner,
        data,
        Seed(1L),
        "ridge-capabilities-v1"
      ) match
        case Left(error)  => fail(s"fit failed: $error")
        case Right(value) => value

    val prediction = trained.predict(Point(4.0)) match
      case Left(error)  => fail(s"predict failed: $error")
      case Right(value) => value
    assert(prediction.isFinite)

    val coefficients = Coefficients[RidgeModel[Point]]
    assertEquals(coefficients.coefficientCount(trained), 1)
    assertEquals(coefficients.coefficients(trained).length, 1)

    val attribution =
      summon[Explain[RidgeModel[Point], Point]](trained, Point(4.0)) match
        case Left(error)  => fail(s"explain failed: $error")
        case Right(value) => value
    assertEquals(attribution.contributions.length, 1)
    assert(attribution.prediction.isFinite)
    assertEquals(attribution.prediction, prediction)

    assertEquals(trained.artifact.solution.intercept, coefficients.intercept(trained))
    assertEquals(
      coefficients.coefficient(trained, 0),
      coefficients.coefficients(trained)(0)
    )

    val trainIds = TestData.rowsOf(data).map(_._1)
    val heldOut =
      InMemoryData.unsplit(
        Vector(Point(1.0), Point(2.0), Point(4.0)),
        DataFingerprint.external("ridge-predict-all")
      )
    val predictedAll = trained.predictAll(heldOut) match
      case Left(error)  => fail(s"predictAll failed: $error")
      case Right(value) => value
    assertEquals(predictedAll.map(_._1.value), Vector(0L, 1L, 2L))
    assertEquals(predictedAll.map(_._2), Vector(1.0, 2.0, 4.0).map(v =>
      trained.predict(Point(v)) match
        case Right(prediction) => prediction
        case Left(error)       => fail(s"predict failed: $error")
    ))
    val _ = trainIds
  }

  test("a composed workflow focuses the terminal model with its child audit") {
    val config = RidgeConfig.create(0.1) match
      case Left(error)  => fail(s"config: $error")
      case Right(value) => value
    val ridge =
      RidgeRegression.sync[StandardPoint, Unit](config, new UnitRidgeBackend)
    val workflow = new StandardizePoint().learnWith(ridge)
    val data =
      TestData.indexed[Use.Train, Example[Point, Double, Unit]](
        Vector(
          Example(Point(1.0), 2.0, ()),
          Example(Point(2.0), 4.0, ()),
          Example(Point(3.0), 6.0, ())
        ),
        DataFingerprint.external("composed-ridge-capabilities")
      ) match
        case Some(value) => value
        case None        => fail("expected data")
    val trained =
      Fit.learner(workflow, data, Seed(2L), "composed-ridge-v1") match
        case Left(error)  => fail(s"fit failed: $error")
        case Right(value) => value

    assertEquals(trained.predict(Point(4.0)), Right(2.0))
    workflow.terminalModel(trained) match
      case Left(error) => fail(s"terminal focus failed: $error")
      case Right(terminal) =>
        assert(terminal.audit eq trained.audit.children.last)
        assertEquals(
          Coefficients[RidgeModel[StandardPoint]].coefficientCount(terminal),
          1
        )
        val explanation =
          summon[Explain[RidgeModel[StandardPoint], StandardPoint]]
            .apply(terminal, StandardPoint(2.0))
        assert(explanation.isRight)
  }

  test("Coefficients and Explain evidence are required at compile time") {
    val errors = typeCheckErrors(
      """package consumer
import alder.kernel.*
def illegal(trained: Trained[String]) =
  Coefficients[String].coefficients(trained)
"""
    )
    assert(errors.nonEmpty)
  }

  test("algorithm capabilities are not lifted to a composed workflow") {
    val errors = typeCheckErrors(
      """package consumer
import alder.kernel.*
import alder.models.linear.*
final case class Raw(x: Double)
final case class Feature(x: Double)
type WorkflowModel = Pipe.Chain[
  Raw, Nothing, Feature, Nothing, Double,
  Pipe[Raw, Nothing, Feature], RidgeModel[Feature]
]
def illegal(trained: Trained[WorkflowModel]) =
  Coefficients[WorkflowModel].coefficients(trained)
"""
    )
    assert(errors.nonEmpty)
  }

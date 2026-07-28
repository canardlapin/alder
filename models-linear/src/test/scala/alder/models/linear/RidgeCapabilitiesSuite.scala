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

    assertEquals(trained.terminal.solution.intercept, coefficients.intercept(trained))
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

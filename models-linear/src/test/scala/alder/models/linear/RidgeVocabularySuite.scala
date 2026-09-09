package alder.models.linear

import scala.compiletime.testing.typeCheckErrors

class RidgeVocabularySuite extends munit.FunSuite:
  test("RidgeConfig admits only finite semantic values") {
    assertEquals(
      RidgeConfig.create(-1.0),
      Left(RidgeConfigError.InvalidPenalty(-1.0))
    )
    RidgeConfig.create(1.0, tolerance = Double.NaN) match
      case Left(RidgeConfigError.InvalidTolerance(value)) =>
        assert(value.isNaN)
      case other => fail(s"expected invalid tolerance, got $other")
    assert(RidgeConfig.create(0.0).isRight)
  }

  test("validated ridge scalar domains support literal and dynamic paths") {
    val penalty = RidgePenalty.const(0.1)
    val tolerance = RidgeTolerance.const(1.0e-8)
    val config = RidgeConfig(penalty, tolerance = tolerance)

    assertEquals(config.penalty, 0.1)
    assertEquals(config.tolerance, 1.0e-8)
    assertEquals(
      RidgePenalty.create(-0.1),
      Left(RidgeConfigError.InvalidPenalty(-0.1))
    )
    assertEquals(
      RidgeTolerance.create(0.0),
      Left(RidgeConfigError.InvalidTolerance(0.0))
    )
  }

  test("invalid ridge literals fail at compile time") {
    val penaltyErrors = typeCheckErrors("RidgePenalty.const(-0.1)")
    assert(
      penaltyErrors.exists(error =>
        error.message.contains("RidgePenalty") &&
          error.message.contains("non-negative")
      ),
      clues(penaltyErrors.map(_.message))
    )
    assert(
      typeCheckErrors("RidgePenalty.const(Double.PositiveInfinity)").nonEmpty
    )
    assert(typeCheckErrors("RidgeTolerance.const(0.0)").nonEmpty)
    assert(typeCheckErrors("RidgeTolerance.const(Double.NaN)").nonEmpty)
  }

  test("ByRow validates values and takes ownership") {
    assertEquals(
      RowWeights.byRow(IArray.empty[Double]),
      Left(RowWeights.Error.Empty)
    )
    assertEquals(
      RowWeights.byRow(IArray(1.0, -0.5)),
      Left(RowWeights.Error.Invalid(1, -0.5))
    )

    val mutable = Array(1.0, 2.0)
    val weights = RowWeights.byRow(IArray.unsafeFromArray(mutable)) match
      case Left(error)  => fail(s"unexpected weight error: $error")
      case Right(value) => value
    mutable(0) = 99.0
    assertEquals(weights.valuesCopy.toVector, Vector(1.0, 2.0))
  }

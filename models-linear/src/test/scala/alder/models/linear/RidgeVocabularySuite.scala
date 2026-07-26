package alder.models.linear

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

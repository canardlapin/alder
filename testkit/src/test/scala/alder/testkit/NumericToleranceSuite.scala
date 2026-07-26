package alder.testkit

class NumericToleranceSuite extends munit.FunSuite:
  test("absolute and relative tolerances cover their intended scales") {
    NumericTolerance(absolute = 1.0e-8, relative = 1.0e-6) match
      case Left(error) => fail(s"unexpected tolerance error: $error")
      case Right(tolerance) =>
        assert(tolerance.equivalent(0.0, 5.0e-9))
        assert(tolerance.equivalent(1.0e6, 1.0e6 + 0.5))
        assert(!tolerance.equivalent(1.0, 1.01))
  }

  test("nonfinite and negative tolerance configurations are rejected") {
    NumericTolerance(Double.NaN, 0.0) match
      case Left(ToleranceError.NonFiniteAbsolute(value)) =>
        assert(java.lang.Double.isNaN(value))
      case result => fail(s"unexpected absolute tolerance result: $result")
    assertEquals(
      NumericTolerance(0.0, Double.PositiveInfinity),
      Left(
        ToleranceError.NonFiniteRelative(Double.PositiveInfinity)
      )
    )
    assertEquals(
      NumericTolerance(-1.0, 0.0),
      Left(ToleranceError.NegativeAbsolute(-1.0))
    )
    assertEquals(
      NumericTolerance(0.0, -1.0),
      Left(ToleranceError.NegativeRelative(-1.0))
    )
  }

  test("NaN never compares equal and equal infinities remain equal") {
    NumericTolerance(0.0, 0.0) match
      case Left(error) => fail(s"unexpected tolerance error: $error")
      case Right(tolerance) =>
        assert(!tolerance.equivalent(Double.NaN, Double.NaN))
        assert(
          tolerance.equivalent(
            Double.PositiveInfinity,
            Double.PositiveInfinity
          )
        )
        assert(
          !tolerance.equivalent(
            Double.PositiveInfinity,
            Double.NegativeInfinity
          )
        )
  }

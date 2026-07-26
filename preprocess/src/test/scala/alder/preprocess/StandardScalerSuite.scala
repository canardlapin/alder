package alder.preprocess

import alder.data.Coordinates
import alder.kernel.*
import alder.testkit.TestData
import cats.Id
import scala.compiletime.testing.typeCheckErrors

final case class Point(x: Double, y: Double)
    derives Coordinates,
      CanEqual

class StandardScalerSuite extends munit.FunSuite:
  private val context =
    FitContext.root(
      Seed(19L),
      PlanFingerprint("standard-scaler-suite"),
      SchemaFingerprint("point"),
      NumericMode.Deterministic
    )

  private def data(
      values: Vector[Point]
  ): NonEmptyData[Use.Train, Point] =
    TestData.nonEmpty(
      values.zipWithIndex.map { (value, index) =>
        RowId(index.toLong) -> value
      },
      new DataFingerprint(
        FingerprintPolicy.Summary("standard-scaler-test"),
        "standard-scaler-data"
      )
    ) match
      case Some(value) => value
      case None        => fail("test data must be nonempty")

  test("centered scaler has zero mean and population variance one") {
    val original =
      data(Vector(Point(1.0, 4.0), Point(2.0, 4.0), Point(3.0, 4.0)))
    val scaler =
      new StandardScaler[Id, Point](ZeroVariance.EmitZero)
    val prepared = scaler.fit(original)(using context).value match
      case Left(error) => fail(s"unexpected fit error: $error")
      case Right(value) => value
    val rows = TestData.rowsOf(prepared.rows).map { (id, value) =>
      val coordinates = Coordinates[Standardized[Point]].read(value) match
        case Left(error) => fail(s"unexpected coordinate error: $error")
        case Right(result) => result
      id -> coordinates.toVector
    }
    assertEquals(rows.map(_._1), Vector(RowId(0L), RowId(1L), RowId(2L)))
    val x = rows.map(_._2(0))
    val y = rows.map(_._2(1))
    assertEqualsDouble(x.sum / x.length.toDouble, 0.0, 1.0e-15)
    assertEqualsDouble(
      x.map(value => value * value).sum / x.length.toDouble,
      1.0,
      1.0e-15
    )
    assertEquals(y, Vector(0.0, 0.0, 0.0))
  }

  test("Reject reports the named constant coordinate") {
    val original =
      data(Vector(Point(1.0, 4.0), Point(2.0, 4.0), Point(3.0, 4.0)))
    val scaler = new StandardScaler[Id, Point](ZeroVariance.Reject)
    scaler.fit(original)(using context).value match
      case Left(Failure(_, ScaleFitError.ConstantCoordinate(name))) =>
        assertEquals(name, "y")
      case other => fail(s"expected constant-coordinate failure, got $other")
  }

  test("nonfinite fitting and serving inputs fail explicitly") {
    val invalid =
      data(Vector(Point(1.0, 2.0), Point(Double.NaN, 3.0)))
    val scaler =
      new StandardScaler[Id, Point](ZeroVariance.EmitZero)
    scaler.fit(invalid)(using context).value match
      case Left(
            Failure(
              _,
              ScaleFitError.NonFinite(row, coordinate, value)
            )
          ) =>
        assertEquals(row, RowId(1L))
        assertEquals(coordinate, "x")
        assert(value.isNaN)
      case other => fail(s"expected nonfinite fit failure, got $other")

    val valid = data(Vector(Point(1.0, 2.0), Point(3.0, 4.0)))
    val fitted = scaler.fit(valid)(using context).value match
      case Left(error) => fail(s"unexpected fit error: $error")
      case Right(value) => value.fitted.artifact
    fitted.run(Point(Double.PositiveInfinity, 3.0)) match
      case Left(Failure(_, ScaleRunError.NonFiniteInput(name, value))) =>
        assertEquals(name, "x")
        assertEquals(value, Double.PositiveInfinity)
      case other => fail(s"expected nonfinite run failure, got $other")
  }

  test("scale-only fitting preserves structural zero positions") {
    val original =
      data(
        Vector(
          Point(0.0, 2.0),
          Point(3.0, 0.0),
          Point(6.0, 8.0)
        )
      )
    val scaler =
      new ScaleOnlyScaler[Id, Point](ZeroVariance.Reject)
    val prepared = scaler.fit(original)(using context).value match
      case Left(error) => fail(s"unexpected fit error: $error")
      case Right(value) => value
    val rawRows = TestData.rowsOf(original).map(_._2)
    val scaledRows = TestData.rowsOf(prepared.rows).map(_._2)
    rawRows.zip(scaledRows).foreach { (raw, scaled) =>
      val rawValues = Coordinates[Point].read(raw) match
        case Left(error) => fail(s"unexpected raw coordinate error: $error")
        case Right(value) => value
      val scaledValues = Coordinates[Scaled[Point]].read(scaled) match
        case Left(error) => fail(s"unexpected scaled coordinate error: $error")
        case Right(value) => value
      rawValues.indices.foreach { index =>
        assertEquals(rawValues(index) == 0.0, scaledValues(index) == 0.0)
      }
    }
  }

  test("representation brands prevent implicit substitution") {
    val rawErrors = typeCheckErrors(
      """import alder.preprocess.*
final case class P(x: Double)
def illegal(value: P): Standardized[P] = value
"""
    )
    val scaledErrors = typeCheckErrors(
      """import alder.preprocess.*
def illegal[P](value: Scaled[P]): Standardized[P] = value
"""
    )
    val centeringSwitchErrors = typeCheckErrors(
      """import alder.preprocess.*
import cats.Id
import alder.data.Coordinates
final case class P(x: Double) derives Coordinates
val illegal = new ScaleOnlyScaler[Id, P](
  ZeroVariance.EmitZero,
  true
)
"""
    )
    assert(rawErrors.nonEmpty)
    assert(scaledErrors.nonEmpty)
    assert(centeringSwitchErrors.nonEmpty)
  }

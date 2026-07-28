package alder.preprocess

import alder.data.{Coordinates, Dense, Fit, Schema}
import alder.kernel.*
import alder.testkit.TestData
import cats.Id
import scala.compiletime.testing.typeCheckErrors

final case class Point(x: Double, y: Double)
    derives Coordinates,
      Schema,
      CanEqual

final case class MixedHouse(area: Double, bedrooms: Int, age: Double)
    derives Coordinates,
      Schema,
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

  private def scaler(
      policy: ZeroVariance
  ): StandardScaler[Id, Point] =
    StandardScaler.sync[Point](policy) match
      case Left(error)  => fail(s"unexpected scaler construction: $error")
      case Right(value) => value

  private def scaleOnly(
      policy: ZeroVariance
  ): ScaleOnlyScaler[Id, Point] =
    ScaleOnlyScaler.sync[Point](policy) match
      case Left(error)  => fail(s"unexpected scaler construction: $error")
      case Right(value) => value

  test("centered scaler has zero mean and population variance one") {
    val original =
      data(Vector(Point(1.0, 4.0), Point(2.0, 4.0), Point(3.0, 4.0)))
    val prepared = scaler(ZeroVariance.EmitZero).fit(original)(using context).value match
      case Left(error) => fail(s"unexpected fit error: $error")
      case Right(value) => value
    val rows = TestData.rowsOf(prepared.rows).map { (id, value) =>
      id -> value.values.toVector
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

  test("synchronous constructor and prepared artifact accessor preserve behavior") {
    val original =
      data(Vector(Point(1.0, 2.0), Point(3.0, 4.0)))
    val prepared =
      Fit
        .transform(
          scaler(ZeroVariance.Reject),
          original,
          seed = Seed(19L),
          plan = "standard-scaler-factory"
        ) match
        case Left(error)  => fail(s"unexpected fit error: $error")
        case Right(value) => value

    assertEquals(
      prepared.artifact.run(Point(2.0, 3.0)).map(_.values.toVector),
      prepared.fitted.artifact.run(Point(2.0, 3.0)).map(_.values.toVector)
    )
  }

  test("Reject reports the named constant coordinate") {
    val original =
      data(Vector(Point(1.0, 4.0), Point(2.0, 4.0), Point(3.0, 4.0)))
    scaler(ZeroVariance.Reject).fit(original)(using context).value match
      case Left(Failure(_, ScaleFitError.ConstantCoordinate(name))) =>
        assertEquals(name, "y")
      case other => fail(s"expected constant-coordinate failure, got $other")
  }

  test("nonfinite fitting and serving inputs fail explicitly") {
    val invalid =
      data(Vector(Point(1.0, 2.0), Point(Double.NaN, 3.0)))
    scaler(ZeroVariance.EmitZero).fit(invalid)(using context).value match
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
    val fitted = scaler(ZeroVariance.EmitZero).fit(valid)(using context).value match
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
    val prepared = scaleOnly(ZeroVariance.Reject).fit(original)(using context).value match
      case Left(error) => fail(s"unexpected fit error: $error")
      case Right(value) => value
    val rawRows = TestData.rowsOf(original).map(_._2)
    val scaledRows = TestData.rowsOf(prepared.rows).map(_._2)
    rawRows.zip(scaledRows).foreach { (raw, scaled) =>
      val rawValues = Coordinates[Point].read(raw) match
        case Left(error) => fail(s"unexpected raw coordinate error: $error")
        case Right(value) => value
      rawValues.indices.foreach { index =>
        assertEquals(rawValues(index) == 0.0, scaled(index) == 0.0)
      }
    }
  }

  test("mixed Double/Int records standardize to Dense without InvalidIntegral") {
    val houses =
      TestData.nonEmpty(
        Vector(
          RowId(0L) -> MixedHouse(60.0, 1, 40.0),
          RowId(1L) -> MixedHouse(75.0, 2, 25.0),
          RowId(2L) -> MixedHouse(90.0, 2, 15.0)
        ),
        new DataFingerprint(
          FingerprintPolicy.Summary("mixed-house"),
          "mixed-house"
        )
      ) match
        case Some(value) => value
        case None        => fail("test data must be nonempty")
    val houseScaler =
      StandardScaler.sync[MixedHouse](ZeroVariance.EmitZero) match
        case Left(error)  => fail(s"unexpected scaler construction: $error")
        case Right(value) => value
    val prepared =
      houseScaler.fit(houses)(using context).value match
        case Left(error)  => fail(s"unexpected mixed-house fit error: $error")
        case Right(value) => value
    val dense = prepared.artifact.run(MixedHouse(80.0, 3, 20.0)) match
      case Left(error)  => fail(s"unexpected run error: $error")
      case Right(value) => value
    assertEquals(dense.size, 3)
    assert(dense.values.forall(_.isFinite))
  }

  test("representation brands prevent implicit substitution") {
    val denseBrandErrors = typeCheckErrors(
      """import alder.preprocess.*
import alder.data.Dense
def illegal(value: Dense[Standardized[Point]]): Dense[Scaled[Point]] = value
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
    assert(denseBrandErrors.nonEmpty)
    assert(centeringSwitchErrors.nonEmpty)
  }

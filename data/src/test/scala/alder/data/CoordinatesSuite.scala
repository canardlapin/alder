package alder.data

import alder.kernel.*
import org.scalacheck.{Gen, Prop, Test}
import scala.compiletime.testing.typeCheckErrors

final case class House(
    areaM2: Double,
    bedrooms: Int,
    ageYears: Float,
    parcelId: Long
) derives Schema,
      Coordinates,
      CanEqual

enum DwellingKind derives Schema, CanEqual:
  case Detached
  case Apartment(units: Int)

class CoordinatesSuite extends munit.FunSuite:
  private def array(values: Double*): IArray[Double] =
    IArray.from(values)

  test("derived coordinates retain declaration order and round trip") {
    val coordinates = Coordinates[House]
    val house = House(142.5, 3, 27.25f, 9007199254740992L)

    assertEquals(coordinates.names.toVector, Vector("areaM2", "bedrooms", "ageYears", "parcelId"))
    assertEquals(coordinates.size, 4)
    coordinates.read(house) match
      case Left(error) => fail(s"unexpected read error: $error")
      case Right(values) =>
        assertEquals(values.toVector, Vector(142.5, 3.0, 27.25, 9007199254740992.0))
        assertEquals(coordinates.build(values), Right(house))
  }

  test("numeric product round trips over generated exactly representable values") {
    val coordinates = Coordinates[House]
    val property = Prop.forAll(
      Gen.choose(-1.0e12, 1.0e12),
      Gen.choose(Int.MinValue, Int.MaxValue),
      Gen.choose(-1000000, 1000000),
      Gen.choose(-9007199254740992L, 9007199254740992L)
    ) { (area, bedrooms, rawAge, parcelId) =>
      val age = rawAge.toFloat
      val house = House(area, bedrooms, age, parcelId)
      coordinates.read(house).flatMap(coordinates.build) == Right(house)
    }
    val result = Test.check(
      Test.Parameters.default.withMinSuccessfulTests(200),
      property
    )
    assert(result.passed, result.toString)
  }

  test("read reports a Long that cannot survive the Double boundary exactly") {
    val house = House(1.0, 2, 3.0f, Long.MaxValue)
    assertEquals(
      Coordinates[House].read(house),
      Left(
        CoordinateError.LongNotExactlyRepresentable(
          3,
          "parcelId",
          Long.MaxValue
        )
      )
    )
  }

  test("build reports arity, fractional integral, and range errors") {
    val coordinates = Coordinates[House]
    assertEquals(
      coordinates.build(array(1.0, 2.0)),
      Left(CoordinateError.ArityMismatch(4, 2))
    )
    assertEquals(
      coordinates.build(array(1.0, 2.5, 3.0, 4.0)),
      Left(
        CoordinateError.InvalidIntegral(
          1,
          "bedrooms",
          2.5,
          CoordinateKind.Int32
        )
      )
    )
    assertEquals(
      coordinates.build(array(1.0, 2.0, Double.MaxValue, 4.0)),
      Left(
        CoordinateError.OutOfRange(
          2,
          "ageYears",
          Double.MaxValue,
          CoordinateKind.Float32
        )
      )
    )
    assertEquals(
      coordinates.build(array(1.0, 2.0, 0.1, 4.0)),
      Left(
        CoordinateError.LossyConversion(
          2,
          "ageYears",
          0.1,
          CoordinateKind.Float32
        )
      )
    )
  }

  test("writeTo materializes directly into a backend-owned destination") {
    val destination = new RecordingWriter(4)
    val house = House(88.0, 2, 11.5f, 17L)

    assertEquals(Coordinates[House].writeTo(house, destination), Right(()))
    assertEquals(
      destination.writes,
      Vector(
        (0, "areaM2", 88.0),
        (1, "bedrooms", 2.0),
        (2, "ageYears", 11.5),
        (3, "parcelId", 17.0)
      )
    )
  }

  test("writeTo rejects destination arity before performing a partial write") {
    val destination = new RecordingWriter(3)
    assertEquals(
      Coordinates[House].writeTo(House(1.0, 2, 3.0f, 4L), destination),
      Left(CoordinateError.DestinationArityMismatch(4, 3))
    )
    assertEquals(destination.writes, Vector.empty)
  }

  test("Coordinates derivation excludes optional and nonnumeric fields") {
    val optionalErrors = typeCheckErrors(
      """import alder.data.*
final case class Incomplete(value: Option[Double], count: Int)
val coordinates = Coordinates.derived[Incomplete]
"""
    )
    val stringErrors = typeCheckErrors(
      """import alder.data.*
final case class Nonnumeric(value: Double, label: String)
val coordinates = Coordinates.derived[Nonnumeric]
"""
    )
    assert(optionalErrors.nonEmpty)
    assert(stringErrors.nonEmpty)
  }

  test("schema derivation is structural, policy tagged, and stable") {
    val house = summon[Schema[House]]
    val dwelling = summon[Schema[DwellingKind]]

    assertEquals(
      house.descriptor,
      "product[5:House40:field[6:areaM222:primitive:scala.Double]39:field[8:bedrooms19:primitive:scala.Int]41:field[8:ageYears21:primitive:scala.Float]40:field[8:parcelId20:primitive:scala.Long]]"
    )
    assertEquals(house.fingerprint.digest, "3c48e2631f1fe773")
    assertEquals(house.fingerprint, summon[Schema[House]].fingerprint)
    house.fingerprint.policy match
      case FingerprintPolicy.ContentDigest(algorithm) =>
        assertEquals(algorithm, "alder-schema-fnv1a64-v1")
      case policy => fail(s"expected content digest policy, got $policy")
    assertEquals(
      dwelling.descriptor,
      "sum[12:DwellingKind19:product[8:Detached]59:product[9:Apartment36:field[5:units19:primitive:scala.Int]]]"
    )
    assertEquals(dwelling.fingerprint.digest, "caf449572979ebd1")
  }

  private final class RecordingWriter(val size: Int)
      extends CoordinateWriter:
    var writes: Vector[(Int, String, Double)] = Vector.empty

    def write(
        index: Int,
        name: String,
        value: Double
    ): Either[CoordinateError, Unit] =
      writes = writes :+ ((index, name, value))
      Right(())

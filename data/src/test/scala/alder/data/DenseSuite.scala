package alder.data

import alder.kernel.*
import scala.compiletime.testing.typeCheckErrors

sealed trait RawFeatures
sealed trait StandardizedFeatures

class DenseSuite extends munit.FunSuite:
  private def schema[S](names: String*): FeatureSchema[S] =
    FeatureSchema.named[S](IArray.from(names)) match
      case Left(error) => fail(s"unexpected schema error: $error")
      case Right(value) => value

  test("feature schemas reject empty and ambiguous coordinate names") {
    assertEquals(
      FeatureSchema.named[RawFeatures](IArray.empty[String]),
      Left(SchemaError.Empty)
    )
    assertEquals(
      FeatureSchema.named[RawFeatures](IArray("x", "")),
      Left(SchemaError.EmptyCoordinateName(1))
    )
    assertEquals(
      FeatureSchema.named[RawFeatures](IArray("x", "x")),
      Left(SchemaError.DuplicateCoordinateName("x"))
    )
  }

  test("dense construction validates the runtime schema dimension") {
    val features = schema[RawFeatures]("x", "y")
    assertEquals(
      Dense.from(IArray(1.0), features),
      Left(DenseError.DimensionMismatch(2, 1))
    )
    Dense.from(IArray(1.0, 2.0), features) match
      case Left(error) => fail(s"unexpected dense error: $error")
      case Right(value) =>
        assertEquals(value.size, 2)
        assertEquals(value.values.toVector, Vector(1.0, 2.0))
        assertEquals(value.schema.fingerprint.digest, features.fingerprint.digest)
  }

  test("same semantic brand still requires the expected runtime schema") {
    val xy = schema[RawFeatures]("x", "y")
    val uv = schema[RawFeatures]("u", "v")
    val value = Dense.from(IArray(1.0, 2.0), uv) match
      case Left(error) => fail(s"unexpected dense error: $error")
      case Right(dense) => dense

    assertEquals(
      xy.validate(value),
      Left(
        DenseError.SchemaMismatch(
          expected = xy.fingerprint,
          actual = uv.fingerprint
        )
      )
    )
    assertEquals(uv.validate(value), Right(()))
  }

  test("feature schema and dense values take immutable ownership") {
    val mutableNames = Array("x", "y")
    val features =
      FeatureSchema.named[RawFeatures](IArray.unsafeFromArray(mutableNames)) match
        case Left(error) => fail(s"unexpected schema error: $error")
        case Right(value) => value
    val mutableValues = Array(1.0, 2.0)
    val dense =
      Dense.from(
        IArray.unsafeFromArray(mutableValues),
        features
      ) match
        case Left(error) => fail(s"unexpected dense error: $error")
        case Right(value) => value

    mutableNames(0) = "changed"
    mutableValues(0) = 99.0
    assertEquals(features.names.toVector, Vector("x", "y"))
    assertEquals(dense.values.toVector, Vector(1.0, 2.0))
  }

  test("feature schema fingerprints are order-sensitive and stable") {
    val xy = schema[RawFeatures]("x", "y")
    val replay = schema[RawFeatures]("x", "y")
    val yx = schema[RawFeatures]("y", "x")

    assertEquals(xy.fingerprint.digest, "d70b7f9805733ab6")
    assertEquals(replay.fingerprint.digest, xy.fingerprint.digest)
    assertNotEquals(yx.fingerprint.digest, xy.fingerprint.digest)
    xy.fingerprint.policy match
      case FingerprintPolicy.ContentDigest(algorithm) =>
        assertEquals(algorithm, "alder-feature-schema-fnv1a64-v1")
      case policy => fail(s"expected content digest policy, got $policy")
  }

  test("semantic feature brands cannot be mixed") {
    val errors = typeCheckErrors(
      """import alder.data.*
sealed trait Raw
sealed trait Standardized
def consume(value: Dense[Standardized]): Int = value.size
def illegal(value: Dense[Raw]): Int = consume(value)
"""
    )
    assert(errors.nonEmpty)
  }

package alder.data

import alder.kernel.*

enum SchemaError derives CanEqual:
  case Empty
  case EmptyCoordinateName(index: Int)
  case DuplicateCoordinateName(name: String)

enum DenseError derives CanEqual:
  case DimensionMismatch(expected: Int, actual: Int)
  case SchemaMismatch(
      expected: SchemaFingerprint,
      actual: SchemaFingerprint
  )

/** Validated runtime feature schema tied to the semantic brand `S`. */
final class FeatureSchema[S] private (
    private val coordinateNames: IArray[String],
    val fingerprint: SchemaFingerprint
):
  def size: Int = coordinateNames.length
  def names: IArray[String] = coordinateNames

  def validate(value: Dense[S]): Either[DenseError, Unit] =
    if fingerprint == value.schema.fingerprint then Right(())
    else
      Left(
        DenseError.SchemaMismatch(
          expected = fingerprint,
          actual = value.schema.fingerprint
        )
      )

object FeatureSchema:
  private val fingerprintAlgorithm = "alder-feature-schema-fnv1a64-v1"

  def named[S](names: IArray[String]): Either[SchemaError, FeatureSchema[S]] =
    if names.isEmpty then Left(SchemaError.Empty)
    else
      var index = 0
      var seen = Set.empty[String]
      var error: Option[SchemaError] = None
      while index < names.length && error.isEmpty do
        val name = names(index)
        if name.isEmpty then
          error = Some(SchemaError.EmptyCoordinateName(index))
        else if seen.contains(name) then
          error = Some(SchemaError.DuplicateCoordinateName(name))
        else seen = seen + name
        index += 1
      error match
        case Some(value) => Left(value)
        case None =>
          val copied = copyStrings(names)
          val descriptor =
            copied.iterator.map(escape).mkString("features(", ",", ")")
          Right(
            new FeatureSchema[S](
              copied,
              new SchemaFingerprint(
                FingerprintPolicy.ContentDigest(fingerprintAlgorithm),
                StableHash.fnv1a64(descriptor)
              )
            )
          )

  private def copyStrings(values: IArray[String]): IArray[String] =
    IArray.tabulate(values.length)(values)

  private def escape(value: String): String =
    s"${value.length}:$value"

/** Immutable dense coordinates retaining both a semantic compile-time brand
  * and the runtime schema that validates their dimension.
  */
final class Dense[S] private (
    private val coordinates: IArray[Double],
    val schema: FeatureSchema[S]
):
  def size: Int = coordinates.length
  def apply(index: Int): Double = coordinates(index)
  def values: IArray[Double] = coordinates

object Dense:
  def from[S](
      values: IArray[Double],
      schema: FeatureSchema[S]
  ): Either[DenseError, Dense[S]] =
    if values.length != schema.size then
      Left(DenseError.DimensionMismatch(schema.size, values.length))
    else
      Right(
        new Dense[S](
          IArray.tabulate(values.length)(values),
          schema
        )
      )

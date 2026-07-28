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

  /** Schema-bound coordinates for a dense feature space. */
  def coordinates[S](schema: FeatureSchema[S]): Coordinates[Dense[S]] =
    new Coordinates[Dense[S]]:
      val names: IArray[String] = schema.names
      val size: Int = schema.size
      val featureSchema: FeatureSchema[?] = schema

      def read(
          value: Dense[S]
      ): Either[CoordinateError, IArray[Double]] =
        schema.validate(value) match
          case Left(DenseError.SchemaMismatch(_, _)) =>
            Left(
              CoordinateError.ArityMismatch(schema.size, value.size)
            )
          case Left(DenseError.DimensionMismatch(expected, actual)) =>
            Left(CoordinateError.ArityMismatch(expected, actual))
          case Right(_) => Right(value.values)

      def writeTo(
          value: Dense[S],
          destination: CoordinateWriter
      ): Either[CoordinateError, Unit] =
        read(value).flatMap { values =>
          if destination.size != values.length then
            Left(
              CoordinateError.DestinationArityMismatch(
                values.length,
                destination.size
              )
            )
          else
            var index = 0
            var error: Option[CoordinateError] = None
            while index < values.length && error.isEmpty do
              destination.write(index, names(index), values(index)) match
                case Left(failure) => error = Some(failure)
                case Right(_)      => ()
              index += 1
            error match
              case Some(failure) => Left(failure)
              case None          => Right(())
        }

      def build(
          values: IArray[Double]
      ): Either[CoordinateError, Dense[S]] =
        Dense.from(values, schema).left.map {
          case DenseError.DimensionMismatch(expected, actual) =>
            CoordinateError.ArityMismatch(expected, actual)
          case DenseError.SchemaMismatch(_, _) =>
            CoordinateError.ArityMismatch(schema.size, values.length)
        }

  /** Schema-bound feature view for a dense feature space. */
  def featureView[S](schema: FeatureSchema[S]): FeatureView[Dense[S]] =
    coordinates(schema)

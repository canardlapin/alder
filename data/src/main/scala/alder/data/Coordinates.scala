package alder.data

import alder.kernel.*
import scala.compiletime.{constValue, erasedValue, summonInline}
import scala.deriving.Mirror

/** Storage kind used when reporting a numeric coordinate conversion failure. */
enum CoordinateKind derives CanEqual:
  case Float32
  case Int64
  case Int32
  case Int16
  case Int8

/** A structural or numeric failure while reading or rebuilding coordinates. */
enum CoordinateError derives CanEqual:
  case ArityMismatch(expected: Int, actual: Int)
  case DestinationArityMismatch(expected: Int, actual: Int)
  case InvalidIntegral(
      index: Int,
      name: String,
      value: Double,
      target: CoordinateKind
  )
  case OutOfRange(
      index: Int,
      name: String,
      value: Double,
      target: CoordinateKind
  )
  case LongNotExactlyRepresentable(
      index: Int,
      name: String,
      value: Long
  )
  case LossyConversion(
      index: Int,
      name: String,
      value: Double,
      target: CoordinateKind
  )
  case WriterRejected(index: Int, name: String, reason: String)

/** Mutable backend materialization boundary. Implementations write directly
  * into their own storage; Alder never requires an intermediate row array.
  */
trait CoordinateWriter:
  /** Number of coordinates accepted by this destination. */
  def size: Int

  /** Writes one named coordinate at its stable, zero-based position. */
  def write(
      index: Int,
      name: String,
      value: Double
  ): Either[CoordinateError, Unit]

/** Ordered numeric view of an application value for materialization and
  * prediction. Learners ordinarily need only this capability.
  */
trait FeatureView[-A]:
  /** Coordinate names in the same order used by every read and write. */
  def names: IArray[String]

  /** Number of coordinates in this representation. */
  def size: Int

  /** Runtime schema fingerprint of the selected numerical feature view. */
  def featureSchema: FeatureSchema[?]

  /** Reads all coordinates into an immutable, ordered array. */
  def read(value: A): Either[CoordinateError, IArray[Double]]

  /** Writes coordinates directly into backend-owned storage. */
  def writeTo(
      value: A,
      destination: CoordinateWriter
  ): Either[CoordinateError, Unit]

  /** Stable audit identity for this exact numerical representation. */
  final def featureViewDescriptor: AuditValue =
    AuditValue.record(
      "fingerprintPolicy" -> AuditValue.text(
        featureSchema.fingerprint.policy match
          case FingerprintPolicy.ContentDigest(algorithm) =>
            s"content-digest:$algorithm"
          case FingerprintPolicy.SourceIdentity(uri, version) =>
            s"source-identity:$uri:$version"
          case FingerprintPolicy.Summary(policyId) =>
            s"summary:$policyId"
      ),
      "fingerprint" -> AuditValue.text(featureSchema.fingerprint.digest),
      "names" -> AuditValue.sequence(
        names.iterator.map(AuditValue.text).toVector*
      )
    )

/** Rebuilds an application value from arbitrary doubles. Required only by
  * transforms that preserve the source representation.
  */
trait CoordinateBuilder[A]:
  def build(values: IArray[Double]): Either[CoordinateError, A]

/** Complete, ordered numeric coordinates for an application value. */
trait Coordinates[A] extends FeatureView[A], CoordinateBuilder[A]:

  /** Reuses this coordinate representation through an isomorphism. */
  final def imap[B](to: A => B)(from: B => A): Coordinates[B] =
    val underlying = this
    new Coordinates[B]:
      def names: IArray[String] = underlying.names
      def size: Int = underlying.size
      def featureSchema: FeatureSchema[?] = underlying.featureSchema
      def read(value: B): Either[CoordinateError, IArray[Double]] =
        underlying.read(from(value))
      def writeTo(
          value: B,
          destination: CoordinateWriter
      ): Either[CoordinateError, Unit] =
        underlying.writeTo(from(value), destination)
      def build(values: IArray[Double]): Either[CoordinateError, B] =
        underlying.build(values).map(to)

object Coordinates:
  /** Summons the coordinate representation for `A`. */
  def apply[A](using coordinates: Coordinates[A]): Coordinates[A] = coordinates

  /** Derives coordinates for a product whose fields all have supported
    * numeric types.
    *
    * Field labels become coordinate names and constructor order determines
    * coordinate order. Supported fields are `Double`, `Float`, `Long`, `Int`,
    * `Short`, and `Byte`; narrowing conversions reject loss or overflow.
    */
  inline def derived[A <: Product](
      using mirror: Mirror.ProductOf[A]
  ): Coordinates[A] =
    val coordinateNames = IArray.from(labels[mirror.MirroredElemLabels])
    instance(
      coordinateNames,
      value =>
        val destination = new Array[Double](coordinateNames.length)
        readElements[mirror.MirroredElemTypes](
          value,
          coordinateNames,
          destination,
          0
        ).map(_ => IArray.unsafeFromArray(destination)),
      (value, destination) =>
        writeElements[mirror.MirroredElemTypes](
          value,
          coordinateNames,
          destination,
          0
        ),
      values =>
        buildElements[mirror.MirroredElemTypes](values, coordinateNames, 0)
          .map(mirror.fromProduct)
    )

  private def instance[A](
      coordinateNames: IArray[String],
      readValue: A => Either[CoordinateError, IArray[Double]],
      writeValue: (A, CoordinateWriter) => Either[CoordinateError, Unit],
      buildValue: IArray[Double] => Either[CoordinateError, A]
  ): Coordinates[A] =
    val schema = FeatureSchema.named[A](coordinateNames) match
      case Right(value) => value
      case Left(error) =>
        throw IllegalArgumentException(
          s"derived coordinate schema rejected: $error"
        )
    new Coordinates[A]:
      val names: IArray[String] = coordinateNames
      val size: Int = coordinateNames.length
      val featureSchema: FeatureSchema[?] = schema

      def read(value: A): Either[CoordinateError, IArray[Double]] =
        readValue(value)

      def writeTo(
          value: A,
          destination: CoordinateWriter
      ): Either[CoordinateError, Unit] =
        if destination.size != size then
          Left(
            CoordinateError.DestinationArityMismatch(
              size,
              destination.size
            )
          )
        else writeValue(value, destination)

      def build(values: IArray[Double]): Either[CoordinateError, A] =
        if values.length != size then
          Left(CoordinateError.ArityMismatch(size, values.length))
        else buildValue(values)

  private inline def labels[Labels <: Tuple]: Vector[String] =
    inline erasedValue[Labels] match
      case _: EmptyTuple => Vector.empty
      case _: (label *: labels) =>
        // Mirror labels are compiler-proven String singleton types. Scala 3.3
        // does not retain that bound through this erased tuple match.
        constValue[label].asInstanceOf[String] +: labels[labels]

  private inline def readElements[Elements <: Tuple](
      product: Product,
      names: IArray[String],
      destination: Array[Double],
      index: Int
  ): Either[CoordinateError, Unit] =
    inline erasedValue[Elements] match
      case _: EmptyTuple => Right(())
      case _: (element *: elementsTail) =>
        // Mirror.ProductOf guarantees productElement has this declared type
        // and order. Accessing it directly avoids allocating an intermediate
        // Tuple or coordinate array on the write hot path.
        val value = product.productElement(index).asInstanceOf[element]
        summonInline[CoordinateField[element]]
          .read(value, index, names(index))
          .flatMap { value =>
            destination(index) = value
            readElements[elementsTail](
              product,
              names,
              destination,
              index + 1
            )
          }

  private inline def writeElements[Elements <: Tuple](
      product: Product,
      names: IArray[String],
      destination: CoordinateWriter,
      index: Int
  ): Either[CoordinateError, Unit] =
    inline erasedValue[Elements] match
      case _: EmptyTuple => Right(())
      case _: (element *: elementsTail) =>
        // See readElements: Mirror establishes this productElement type.
        val value = product.productElement(index).asInstanceOf[element]
        val name = names(index)
        summonInline[CoordinateField[element]]
          .read(value, index, name)
          .flatMap(destination.write(index, name, _))
          .flatMap(_ =>
            writeElements[elementsTail](
              product,
              names,
              destination,
              index + 1
            )
          )

  private inline def buildElements[Elements <: Tuple](
      values: IArray[Double],
      names: IArray[String],
      index: Int
  ): Either[CoordinateError, Elements] =
    inline erasedValue[Elements] match
      // Scala 3.3 does not refine Elements in these branches; both casts
      // reintroduce only the tuple equality established by erasedValue.
      case _: EmptyTuple => Right(EmptyTuple.asInstanceOf[Elements])
      case _: (element *: elementsTail) =>
        summonInline[CoordinateField[element]]
          .build(values(index), index, names(index))
          .flatMap(head =>
            buildElements[elementsTail](values, names, index + 1)
              .map(tail => (head *: tail).asInstanceOf[Elements])
          )

private trait CoordinateField[A]:
  def read(
      value: A,
      index: Int,
      name: String
  ): Either[CoordinateError, Double]
  def build(
      value: Double,
      index: Int,
      name: String
  ): Either[CoordinateError, A]

private object CoordinateField:
  given CoordinateField[Double] with
    def read(
        value: Double,
        index: Int,
        name: String
    ): Either[CoordinateError, Double] =
      Right(value)
    def build(
        value: Double,
        index: Int,
        name: String
    ): Either[CoordinateError, Double] =
      Right(value)

  given CoordinateField[Float] with
    def read(
        value: Float,
        index: Int,
        name: String
    ): Either[CoordinateError, Double] =
      Right(value.toDouble)
    def build(
        value: Double,
        index: Int,
        name: String
    ): Either[CoordinateError, Float] =
      val result = value.toFloat
      if result.toDouble == value || (result.isNaN && value.isNaN) then
        Right(result)
      else if value.isFinite && result.isInfinite then
        Left(
          CoordinateError.OutOfRange(
            index,
            name,
            value,
            CoordinateKind.Float32
          )
        )
      else
        Left(
          CoordinateError.LossyConversion(
            index,
            name,
            value,
            CoordinateKind.Float32
          )
        )

  given CoordinateField[Long] with
    def read(
        value: Long,
        index: Int,
        name: String
    ): Either[CoordinateError, Double] =
      if exactlyRepresentable(value) then Right(value.toDouble)
      else
        Left(
          CoordinateError.LongNotExactlyRepresentable(
            index,
            name,
            value
          )
        )
    def build(
        value: Double,
        index: Int,
        name: String
    ): Either[CoordinateError, Long] =
      val minimum = -9223372036854775808.0
      val exclusiveMaximum = 9223372036854775808.0
      if !value.isFinite || value < minimum || value >= exclusiveMaximum then
        Left(
          CoordinateError.OutOfRange(
            index,
            name,
            value,
            CoordinateKind.Int64
          )
        )
      else
        val result = value.toLong
        if result.toDouble == value && exactlyRepresentable(result) then
          Right(result)
        else
          Left(
            CoordinateError.InvalidIntegral(
              index,
              name,
              value,
              CoordinateKind.Int64
            )
          )

    private def exactlyRepresentable(value: Long): Boolean =
      if value == 0L then true
      else
        val magnitudeBits =
          if value == Long.MinValue then 64
          else
            64 - java.lang.Long.numberOfLeadingZeros(math.abs(value))
        val discardedBits = math.max(0, magnitudeBits - 53)
        java.lang.Long.numberOfTrailingZeros(value) >= discardedBits

  given CoordinateField[Int] =
    integral(
      CoordinateKind.Int32,
      Int.MinValue.toDouble,
      Int.MaxValue.toDouble,
      _.toInt,
      _.toDouble
    )

  given CoordinateField[Short] =
    integral(
      CoordinateKind.Int16,
      Short.MinValue.toDouble,
      Short.MaxValue.toDouble,
      _.toShort,
      _.toDouble
    )

  given CoordinateField[Byte] =
    integral(
      CoordinateKind.Int8,
      Byte.MinValue.toDouble,
      Byte.MaxValue.toDouble,
      _.toByte,
      _.toDouble
    )

  private def integral[A](
      target: CoordinateKind,
      minimum: Double,
      maximum: Double,
      narrow: Double => A,
      widen: A => Double
  ): CoordinateField[A] =
    new CoordinateField[A]:
      def read(
          value: A,
          index: Int,
          name: String
      ): Either[CoordinateError, Double] =
        Right(widen(value))

      def build(
          value: Double,
          index: Int,
          name: String
      ): Either[CoordinateError, A] =
        if !value.isFinite || value < minimum || value > maximum then
          Left(CoordinateError.OutOfRange(index, name, value, target))
        else
          val result = narrow(value)
          if widen(result) == value then Right(result)
          else Left(CoordinateError.InvalidIntegral(index, name, value, target))

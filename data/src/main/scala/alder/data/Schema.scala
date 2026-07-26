package alder.data

import alder.kernel.*
import scala.compiletime.{constValue, erasedValue, summonInline}
import scala.deriving.Mirror

/** A stable description of the serialization-facing shape of `A`. */
trait Schema[A]:
  def descriptor: String
  def fingerprint: SchemaFingerprint

object Schema:
  private val fingerprintAlgorithm = "alder-schema-fnv1a64-v1"

  given Schema[Double] = primitive("scala.Double")
  given Schema[Float] = primitive("scala.Float")
  given Schema[Long] = primitive("scala.Long")
  given Schema[Int] = primitive("scala.Int")
  given Schema[Short] = primitive("scala.Short")
  given Schema[Byte] = primitive("scala.Byte")
  given Schema[Boolean] = primitive("scala.Boolean")
  given Schema[String] = primitive("java.lang.String")

  given [A](using element: Schema[A]): Schema[Option[A]] =
    fromDescriptor(s"option(${element.descriptor})")

  inline def derived[A](using mirror: Mirror.Of[A]): Schema[A] =
    inline mirror match
      case product: Mirror.ProductOf[A] =>
        val label = constValue[product.MirroredLabel]
        val fields =
          productFields[
            product.MirroredElemLabels,
            product.MirroredElemTypes
          ]
        fromDescriptor(productDescriptor(label, fields))
      case sum: Mirror.SumOf[A] =>
        val label = constValue[sum.MirroredLabel]
        val cases = sumCases[sum.MirroredElemTypes]
        fromDescriptor(sumDescriptor(label, cases))

  private def primitive[A](name: String): Schema[A] =
    fromDescriptor(s"primitive:$name")

  private def fromDescriptor[A](value: String): Schema[A] =
    new Schema[A]:
      val descriptor: String = value
      val fingerprint: SchemaFingerprint =
        new SchemaFingerprint(
          FingerprintPolicy.ContentDigest(fingerprintAlgorithm),
          StableHash.fnv1a64(value)
        )

  private inline def productFields[
      Labels <: Tuple,
      Elements <: Tuple
  ]: Vector[String] =
    inline (erasedValue[Labels], erasedValue[Elements]) match
      case (_: EmptyTuple, _: EmptyTuple) => Vector.empty
      case (
            _: (label *: labels),
            _: (element *: elements)
          ) =>
        // Mirror labels are compiler-proven String singleton types. Scala 3.3
        // does not retain that bound through this erased tuple match.
        val fieldName = constValue[label].asInstanceOf[String]
        val fieldSchema = summonInline[Schema[element]]
        fieldDescriptor(fieldName, fieldSchema.descriptor) +:
          productFields[labels, elements]

  private inline def sumCases[Elements <: Tuple]: Vector[String] =
    inline erasedValue[Elements] match
      case _: EmptyTuple => Vector.empty
      case _: (element *: elements) =>
        mirrorDescriptor(summonInline[Mirror.Of[element]]) +:
          sumCases[elements]

  private inline def mirrorDescriptor[A](mirror: Mirror.Of[A]): String =
    inline mirror match
      case product: Mirror.ProductOf[A] =>
        val label = constValue[product.MirroredLabel]
        val fields =
          productFields[
            product.MirroredElemLabels,
            product.MirroredElemTypes
          ]
        productDescriptor(label, fields)
      case sum: Mirror.SumOf[A] =>
        val label = constValue[sum.MirroredLabel]
        sumDescriptor(label, sumCases[sum.MirroredElemTypes])

  private def productDescriptor(
      label: String,
      fields: Vector[String]
  ): String =
    tagged("product", label, fields)

  private def sumDescriptor(label: String, cases: Vector[String]): String =
    tagged("sum", label, cases)

  private def fieldDescriptor(name: String, schema: String): String =
    s"field[${token(name)}${token(schema)}]"

  private def tagged(
      tag: String,
      label: String,
      children: Vector[String]
  ): String =
    s"$tag[${token(label)}${children.map(token).mkString}]"

  private def token(value: String): String =
    s"${value.length}:$value"

private[data] object StableHash:
  private val offset = 0xcbf29ce484222325L
  private val prime = 0x100000001b3L

  def fnv1a64(value: String): String =
    var hash = offset
    var index = 0
    while index < value.length do
      val codeUnit = value.charAt(index).toInt
      hash = (hash ^ (codeUnit & 0xff).toLong) * prime
      hash = (hash ^ ((codeUnit >>> 8) & 0xff).toLong) * prime
      index += 1
    hex(hash)

  private def hex(value: Long): String =
    val digits = "0123456789abcdef"
    val builder = new StringBuilder(16)
    var shift = 60
    while shift >= 0 do
      val digit = ((value >>> shift) & 0x0fL).toInt
      builder.append(digits.charAt(digit))
      shift -= 4
    builder.result()

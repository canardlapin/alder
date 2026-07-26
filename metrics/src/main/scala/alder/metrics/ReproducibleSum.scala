package alder.metrics

import cats.kernel.CommutativeMonoid

/** Exact sum of finite IEEE-754 binary64 values.
  *
  * Every input is converted to an integer number of minimum subnormal units
  * (2^-1074). BigInt addition is exact, associative, and commutative; the
  * canonical conversion back to binary64 performs round-to-nearest, ties-even.
  */
private[metrics] final class ReproducibleSum private (
    private val scaledInteger: BigInt
):
  def add(value: Double): ReproducibleSum =
    new ReproducibleSum(
      scaledInteger + ReproducibleSum.scaled(value)
    )

  def result: Double =
    ReproducibleSum.toDouble(scaledInteger)

private[metrics] object ReproducibleSum:
  private val fractionMask = (1L << 52) - 1L
  private val hiddenBit = 1L << 52
  private val signMask = Long.MinValue
  private val twoTo52 = BigInt(1) << 52

  val empty: ReproducibleSum = new ReproducibleSum(BigInt(0))

  given CommutativeMonoid[ReproducibleSum] with
    def empty: ReproducibleSum = ReproducibleSum.empty

    def combine(
        left: ReproducibleSum,
        right: ReproducibleSum
    ): ReproducibleSum =
      new ReproducibleSum(left.scaledInteger + right.scaledInteger)

  private def scaled(value: Double): BigInt =
    val bits = java.lang.Double.doubleToRawLongBits(value)
    val negative = (bits & signMask) != 0L
    val exponent = ((bits >>> 52) & 0x7ffL).toInt
    val fraction = bits & fractionMask
    val significand =
      if exponent == 0 then BigInt(fraction)
      else BigInt(hiddenBit | fraction)
    val shifted =
      if exponent == 0 then significand
      else significand << (exponent - 1)
    if negative then -shifted else shifted

  private def toDouble(value: BigInt): Double =
    if value.signum == 0 then 0.0
    else
      val negative = value.signum < 0
      val magnitude = value.abs
      val rawMagnitude =
        if magnitude < twoTo52 then magnitude.toLong
        else normalBits(magnitude)
      val raw =
        if negative then rawMagnitude | signMask
        else rawMagnitude
      java.lang.Double.longBitsToDouble(raw)

  private def normalBits(magnitude: BigInt): Long =
    val bitLength = magnitude.bitLength
    val shift = bitLength - 53
    val truncated =
      if shift == 0 then magnitude
      else magnitude >> shift
    val rounded =
      if shift == 0 then truncated
      else
        val remainder = magnitude - (truncated << shift)
        val halfway = BigInt(1) << (shift - 1)
        val roundUp =
          remainder > halfway ||
            (remainder == halfway && truncated.testBit(0))
        if roundUp then truncated + 1 else truncated
    val carried = rounded.bitLength > 53
    val significand = if carried then rounded >> 1 else rounded
    val exponent =
      bitLength - 1 - 1074 + (if carried then 1 else 0)
    if exponent > 1023 then 0x7ff0000000000000L
    else
      val exponentBits = (exponent + 1023).toLong << 52
      val fractionBits = (significand - twoTo52).toLong
      exponentBits | fractionBits

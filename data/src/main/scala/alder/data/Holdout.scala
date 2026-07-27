package alder.data

import alder.kernel.*
import scala.collection.mutable.ArrayBuffer

/** A positive, exact number of rows in a held-out partition. */
opaque type Rows = Long

object Rows:
  def apply(value: Long): Either[DataError, Rows] =
    if value > 0L then Right(value)
    else Left(DataError.InvalidRows(value))

  extension (rows: Rows) def value: Long = rows

  given CanEqual[Rows, Rows] = CanEqual.derived

/** An exact reduced fraction strictly between zero and one. */
final class Fraction private (
    val numerator: Long,
    val denominator: Long
) derives CanEqual:
  override def equals(other: Any): Boolean =
    other match
      case fraction: Fraction =>
        numerator == fraction.numerator &&
          denominator == fraction.denominator
      case _ => false

  override def hashCode(): Int =
    31 * java.lang.Long.hashCode(numerator) +
      java.lang.Long.hashCode(denominator)

  override def toString: String = s"$numerator/$denominator"

object Fraction:
  def apply(
      numerator: Long,
      denominator: Long
  ): Either[DataError, Fraction] =
    if numerator <= 0L || denominator <= 0L || numerator >= denominator then
      Left(DataError.InvalidFraction(numerator, denominator))
    else
      val divisor = greatestCommonDivisor(numerator, denominator)
      Right(new Fraction(numerator / divisor, denominator / divisor))

  private def greatestCommonDivisor(left: Long, right: Long): Long =
    var a = left
    var b = right
    while b != 0L do
      val remainder = a % b
      a = b
      b = remainder
    a

/** Exact held-out-size policy used by a split specification. */
enum SplitAmount derives CanEqual:
  case Count(rows: Rows)
  case Proportion(fraction: Fraction)

/** Complete semantic policy committed by a [[SplitReceipt]]. */
enum SplitPolicy derives CanEqual:
  case Holdout(test: SplitAmount)
  case Validation(validation: SplitAmount)
  case TrainValidationTest(
      validation: SplitAmount,
      test: SplitAmount
  )

enum SplitRole derives CanEqual:
  case Train
  case Validation
  case Test

enum SplitAlgorithm derives CanEqual:
  case RankV1

/** Fingerprint field that violated the RankV1 byte contract. */
enum RankTextField derives CanEqual:
  case ContentDigestAlgorithm
  case SourceIdentityUri
  case SourceIdentityVersion
  case SummaryPolicyId
  case FingerprintDigest

/** Why a text value could not be encoded by RankV1. */
enum RankTextError derives CanEqual:
  case UnpairedSurrogate(codeUnitIndex: Int)
  case Utf8LengthExceedsU32(byteLength: Long)

/** Checked, unseeded Train/Test split specification. */
final class HoldoutSpec private (val test: SplitAmount):
  def policy: SplitPolicy = SplitPolicy.Holdout(test)

object HoldoutSpec:
  def apply(test: Rows): HoldoutSpec =
    new HoldoutSpec(SplitAmount.Count(test))

  def apply(test: Fraction): HoldoutSpec =
    new HoldoutSpec(SplitAmount.Proportion(test))

  def rows(value: Long): Either[DataError, HoldoutSpec] =
    Rows(value).map(apply)

  def fraction(
      numerator: Long,
      denominator: Long
  ): Either[DataError, HoldoutSpec] =
    Fraction(numerator, denominator).map(apply)

/** Checked, unseeded Train/Validation split specification. */
final class ValidationSpec private (val validation: SplitAmount):
  def policy: SplitPolicy = SplitPolicy.Validation(validation)

object ValidationSpec:
  def apply(validation: Rows): ValidationSpec =
    new ValidationSpec(SplitAmount.Count(validation))

  def apply(validation: Fraction): ValidationSpec =
    new ValidationSpec(SplitAmount.Proportion(validation))

  def rows(value: Long): Either[DataError, ValidationSpec] =
    Rows(value).map(apply)

  def fraction(
      numerator: Long,
      denominator: Long
  ): Either[DataError, ValidationSpec] =
    Fraction(numerator, denominator).map(apply)

/** Checked, unseeded Train/Validation/Test split specification. */
final class TrainValidationTestSpec private (
    val validation: SplitAmount,
    val test: SplitAmount
):
  def policy: SplitPolicy =
    SplitPolicy.TrainValidationTest(validation, test)

object TrainValidationTestSpec:
  def apply(
      validation: SplitAmount,
      test: SplitAmount
  ): Either[DataError, TrainValidationTestSpec] =
    (validation, test) match
      case (
            SplitAmount.Proportion(validationFraction),
            SplitAmount.Proportion(testFraction)
          ) =>
        val numerator =
          BigInt(validationFraction.numerator) *
            BigInt(testFraction.denominator) +
            BigInt(testFraction.numerator) *
              BigInt(validationFraction.denominator)
        val denominator =
          BigInt(validationFraction.denominator) *
            BigInt(testFraction.denominator)
        if numerator >= denominator then
          Left(
            DataError.InvalidThreeWayFractionSum(
              validationFraction,
              testFraction
            )
          )
        else Right(new TrainValidationTestSpec(validation, test))
      case _ =>
        Right(new TrainValidationTestSpec(validation, test))

  def rows(
      validation: Long,
      test: Long
  ): Either[DataError, TrainValidationTestSpec] =
    for
      validationRows <- Rows(validation)
      testRows <- Rows(test)
      specification <- apply(
        SplitAmount.Count(validationRows),
        SplitAmount.Count(testRows)
      )
    yield specification

  def fractions(
      validationNumerator: Long,
      validationDenominator: Long,
      testNumerator: Long,
      testDenominator: Long
  ): Either[DataError, TrainValidationTestSpec] =
    for
      validation <- Fraction(
        validationNumerator,
        validationDenominator
      )
      test <- Fraction(testNumerator, testDenominator)
      specification <- apply(
        SplitAmount.Proportion(validation),
        SplitAmount.Proportion(test)
      )
    yield specification

/** One output role committed by a split receipt. */
final case class SplitPartitionReceipt(
    role: SplitRole,
    fingerprint: DataFingerprint,
    count: Long
)

/** Reproducible evidence for one complete split assignment. */
final class SplitReceipt private[data] (
    val source: DataFingerprint,
    val policy: ProtocolFingerprint,
    val seed: Seed,
    val partitions: Vector[SplitPartitionReceipt],
    val algorithm: SplitAlgorithm
)

/** A typed, non-empty Train/Test split. */
final class Holdout[+A] private[data] (
    val train: NonEmptyData[Use.Train, A],
    val test: NonEmptyData[Use.Test, A],
    val receipt: SplitReceipt
)

/** A typed, non-empty Train/Validation split. */
final class ValidationSplit[+A] private[data] (
    val train: NonEmptyData[Use.Train, A],
    val validation: NonEmptyData[Use.Validation, A],
    val receipt: SplitReceipt
)

/** A typed, non-empty Train/Validation/Test split from one assignment. */
final class TrainValidationTestSplit[+A] private[data] (
    val train: NonEmptyData[Use.Train, A],
    val validation: NonEmptyData[Use.Validation, A],
    val test: NonEmptyData[Use.Test, A],
    val receipt: SplitReceipt
)

object Split:
  /** Produces one non-empty Train/Test assignment for precommitted testing. */
  def holdout[A](
      data: Data[Use.Unsplit, A],
      specification: HoldoutSpec,
      seed: Seed
  ): Either[DataError, Holdout[A]] =
    splitRows(data, specification.policy, seed).flatMap { assigned =>
      for
        train <- partition[Use.Train, A](
          data.fingerprint,
          SplitRole.Train,
          assigned.train
        )
        test <- partition[Use.Test, A](
          data.fingerprint,
          SplitRole.Test,
          assigned.test
        )
      yield
        val receipt = splitReceipt(
          data.fingerprint,
          specification.policy,
          seed,
          Vector(
            SplitPartitionReceipt(
              SplitRole.Train,
              train.fingerprint,
              train.size
            ),
            SplitPartitionReceipt(
              SplitRole.Test,
              test.fingerprint,
              test.size
            )
          )
        )
        new Holdout(train, test, receipt)
    }

  /** Produces one non-empty Train/Validation assignment. */
  def validation[A](
      data: Data[Use.Unsplit, A],
      specification: ValidationSpec,
      seed: Seed
  ): Either[DataError, ValidationSplit[A]] =
    splitRows(data, specification.policy, seed).flatMap { assigned =>
      for
        train <- partition[Use.Train, A](
          data.fingerprint,
          SplitRole.Train,
          assigned.train
        )
        validation <- partition[Use.Validation, A](
          data.fingerprint,
          SplitRole.Validation,
          assigned.validation
        )
      yield
        val receipt = splitReceipt(
          data.fingerprint,
          specification.policy,
          seed,
          Vector(
            SplitPartitionReceipt(
              SplitRole.Train,
              train.fingerprint,
              train.size
            ),
            SplitPartitionReceipt(
              SplitRole.Validation,
              validation.fingerprint,
              validation.size
            )
          )
        )
        new ValidationSplit(train, validation, receipt)
    }

  /** Produces Train, Validation, and Test from one total assignment. */
  def trainValidationTest[A](
      data: Data[Use.Unsplit, A],
      specification: TrainValidationTestSpec,
      seed: Seed
  ): Either[DataError, TrainValidationTestSplit[A]] =
    splitRows(data, specification.policy, seed).flatMap { assigned =>
      for
        train <- partition[Use.Train, A](
          data.fingerprint,
          SplitRole.Train,
          assigned.train
        )
        validation <- partition[Use.Validation, A](
          data.fingerprint,
          SplitRole.Validation,
          assigned.validation
        )
        test <- partition[Use.Test, A](
          data.fingerprint,
          SplitRole.Test,
          assigned.test
        )
      yield
        val receipt = splitReceipt(
          data.fingerprint,
          specification.policy,
          seed,
          Vector(
            SplitPartitionReceipt(
              SplitRole.Train,
              train.fingerprint,
              train.size
            ),
            SplitPartitionReceipt(
              SplitRole.Validation,
              validation.fingerprint,
              validation.size
            ),
            SplitPartitionReceipt(
              SplitRole.Test,
              test.fingerprint,
              test.size
            )
          )
        )
        new TrainValidationTestSplit(train, validation, test, receipt)
    }

  private final case class Assigned[A](
      train: Vector[(RowId, A)],
      validation: Vector[(RowId, A)],
      test: Vector[(RowId, A)]
  )

  private def splitRows[A](
      data: Data[Use.Unsplit, A],
      policy: SplitPolicy,
      seed: Seed
  ): Either[DataError, Assigned[A]] =
    val rows = DataRows.collect(data)
    for
      encodedFingerprint <- RankV1.encode(data.fingerprint)
      _ <- rejectDuplicateRows(rows)
      counts <- countsFor(rows.length.toLong, policy)
      ranked = rows.zipWithIndex
        .map { (row, sourceIndex) =>
          (
            RankV1.rank(encodedFingerprint, seed, row._1),
            row,
            sourceIndex
          )
        }
        .sortBy(entry => (entry._1, entry._2._1.value))
      validationIndexes = ranked
        .take(counts._1)
        .iterator
        .map(_._3)
        .toSet
      testIndexes = ranked
        .slice(counts._1, counts._1 + counts._2)
        .iterator
        .map(_._3)
        .toSet
      assigned = rows.zipWithIndex.foldLeft(
        Assigned[A](Vector.empty, Vector.empty, Vector.empty)
      ) { (result, indexed) =>
        val (row, index) = indexed
        if validationIndexes.contains(index) then
          result.copy(validation = result.validation :+ row)
        else if testIndexes.contains(index) then
          result.copy(test = result.test :+ row)
        else result.copy(train = result.train :+ row)
      }
    yield assigned

  private def countsFor(
      availableRows: Long,
      policy: SplitPolicy
  ): Either[DataError, (Int, Int)] =
    val (validation, test) =
      policy match
        case SplitPolicy.Holdout(amount) =>
          (BigInt(0), apportioned(availableRows, amount))
        case SplitPolicy.Validation(amount) =>
          (apportioned(availableRows, amount), BigInt(0))
        case SplitPolicy.TrainValidationTest(validationAmount, testAmount) =>
          (
            apportioned(availableRows, validationAmount),
            apportioned(availableRows, testAmount)
          )
    val available = BigInt(availableRows)
    val train = available - validation - test
    if validation + test >= available then
      Left(DataError.ExhaustiveSplit(availableRows, policy))
    else
      val emptyRole =
        policy match
          case SplitPolicy.Holdout(_) if test.signum == 0 =>
            Some(SplitRole.Test)
          case SplitPolicy.Validation(_) if validation.signum == 0 =>
            Some(SplitRole.Validation)
          case SplitPolicy.TrainValidationTest(_, _)
              if validation.signum == 0 =>
            Some(SplitRole.Validation)
          case SplitPolicy.TrainValidationTest(_, _) if test.signum == 0 =>
            Some(SplitRole.Test)
          case _ if train.signum == 0 =>
            Some(SplitRole.Train)
          case _ =>
            None
      emptyRole match
        case Some(role) =>
          Left(DataError.EmptySplitRole(role, availableRows, policy))
        case None =>
          Right((validation.toInt, test.toInt))

  private def apportioned(
      availableRows: Long,
      amount: SplitAmount
  ): BigInt =
    amount match
      case SplitAmount.Count(rows) => BigInt(rows.value)
      case SplitAmount.Proportion(fraction) =>
        BigInt(availableRows) * BigInt(fraction.numerator) /
          BigInt(fraction.denominator)

  private def rejectDuplicateRows[A](
      rows: Vector[(RowId, A)]
  ): Either[DataError, Unit] =
    rows
      .foldLeft((Set.empty[RowId], Option.empty[RowId])) {
        case ((seen, duplicate @ Some(_)), _) =>
          (seen, duplicate)
        case ((seen, None), (id, _)) =>
          if seen.contains(id) then (seen, Some(id))
          else (seen + id, None)
      }
      ._2 match
      case Some(id) => Left(DataError.DuplicateSourceRow(id))
      case None     => Right(())

  private def partition[U <: Use, A](
      source: DataFingerprint,
      role: SplitRole,
      rows: Vector[(RowId, A)]
  ): Either[DataError, NonEmptyData[U, A]] =
    DataRows.nonEmpty(
      rows,
      Fingerprints.partition(source, roleLabel(role), rows)
    )

  private def splitReceipt(
      source: DataFingerprint,
      policy: SplitPolicy,
      seed: Seed,
      partitions: Vector[SplitPartitionReceipt]
  ): SplitReceipt =
    new SplitReceipt(
      source,
      Fingerprints.splitPolicy(policy),
      seed,
      partitions,
      SplitAlgorithm.RankV1
    )

  private def roleLabel(role: SplitRole): String =
    role match
      case SplitRole.Train      => "split/train"
      case SplitRole.Validation => "split/validation"
      case SplitRole.Test       => "split/test"

object Holdout:
  /** Compatibility entry point. New code should construct a checked
    * [[HoldoutSpec]] and call [[Split.holdout]].
    */
  def split[A](
      data: Data[Use.Unsplit, A],
      testSize: Int,
      seed: Seed
  ): Either[DataError, Holdout[A]] =
    if testSize <= 0 || testSize.toLong >= data.size then
      Left(DataError.InvalidHoldoutSize(testSize, data.size))
    else
      HoldoutSpec
        .rows(testSize.toLong)
        .flatMap(Split.holdout(data, _, seed))

private[data] object RankV1:
  private val offset = 0xcbf29ce484222325L
  private val prime = 0x100000001b3L

  private[data] final class EncodedFingerprint(
      private[data] val bytes: Vector[Byte]
  )

  def encode(
      fingerprint: DataFingerprint
  ): Either[DataError, EncodedFingerprint] =
    val bytes = ArrayBuffer.empty[Byte]
    appendAsciiText(bytes, "alder.split.rank-v1")
    val encodedPolicy =
      fingerprint.policy match
        case FingerprintPolicy.ContentDigest(algorithm) =>
          appendAsciiText(bytes, "content-digest")
          appendValidatedText(
            bytes,
            RankTextField.ContentDigestAlgorithm,
            algorithm
          )
        case FingerprintPolicy.SourceIdentity(uri, version) =>
          appendAsciiText(bytes, "source-identity")
          appendValidatedText(
            bytes,
            RankTextField.SourceIdentityUri,
            uri
          ).flatMap(_ =>
            appendValidatedText(
              bytes,
              RankTextField.SourceIdentityVersion,
              version
            )
          )
        case FingerprintPolicy.Summary(policyId) =>
          appendAsciiText(bytes, "summary")
          appendValidatedText(
            bytes,
            RankTextField.SummaryPolicyId,
            policyId
          )
    encodedPolicy
      .flatMap(_ =>
        appendValidatedText(
          bytes,
          RankTextField.FingerprintDigest,
          fingerprint.digest
        )
      )
      .map(_ => new EncodedFingerprint(bytes.toVector))

  def rank(
      fingerprint: DataFingerprint,
      seed: Seed,
      id: RowId
  ): Either[DataError, Long] =
    encode(fingerprint).map(rank(_, seed, id))

  def rank(
      fingerprint: EncodedFingerprint,
      seed: Seed,
      id: RowId
  ): Long =
    val bytes = ArrayBuffer.from(fingerprint.bytes)
    appendLong(bytes, seed.value)
    appendLong(bytes, id.value)
    var hash = offset
    bytes.foreach { byte =>
      hash = (hash ^ (byte.toLong & 0xffL)) * prime
    }
    splitmixFinalizer(hash)

  private def appendValidatedText(
      bytes: ArrayBuffer[Byte],
      field: RankTextField,
      value: String
  ): Either[DataError, Unit] =
    utf8Length(value) match
      case Left(reason) =>
        Left(DataError.InvalidRankText(field, reason))
      case Right(length) =>
        if length > 0xffffffffL then
          Left(
            DataError.InvalidRankText(
              field,
              RankTextError.Utf8LengthExceedsU32(length)
            )
          )
        else
          appendText(bytes, value, length)
          Right(())

  private def utf8Length(value: String): Either[RankTextError, Long] =
    var index = 0
    var length = 0L
    var error: Option[RankTextError] = None
    while index < value.length && error.isEmpty do
      val codeUnit = value.charAt(index)
      if Character.isHighSurrogate(codeUnit) then
        if index + 1 >= value.length ||
            !Character.isLowSurrogate(value.charAt(index + 1))
        then
          error = Some(RankTextError.UnpairedSurrogate(index))
        else
          length += 4L
          index += 1
      else if Character.isLowSurrogate(codeUnit) then
        error = Some(RankTextError.UnpairedSurrogate(index))
      else if codeUnit.toInt <= 0x7f then length += 1L
      else if codeUnit.toInt <= 0x7ff then length += 2L
      else length += 3L
      index += 1
    error match
      case Some(reason) => Left(reason)
      case None         => Right(length)

  private def appendText(
      bytes: ArrayBuffer[Byte],
      value: String,
      length: Long
  ): Unit =
    bytes += ((length >>> 24) & 0xffL).toByte
    bytes += ((length >>> 16) & 0xffL).toByte
    bytes += ((length >>> 8) & 0xffL).toByte
    bytes += (length & 0xffL).toByte
    var index = 0
    while index < value.length do
      val codeUnit = value.charAt(index)
      val codePoint =
        if Character.isHighSurrogate(codeUnit) then
          val high = codeUnit.toInt - 0xd800
          val low = value.charAt(index + 1).toInt - 0xdc00
          index += 1
          0x10000 + (high << 10) + low
        else codeUnit.toInt
      appendCodePoint(bytes, codePoint)
      index += 1

  private def appendAsciiText(
      bytes: ArrayBuffer[Byte],
      value: String
  ): Unit =
    val length = value.length.toLong
    bytes += ((length >>> 24) & 0xffL).toByte
    bytes += ((length >>> 16) & 0xffL).toByte
    bytes += ((length >>> 8) & 0xffL).toByte
    bytes += (length & 0xffL).toByte
    var index = 0
    while index < value.length do
      bytes += value.charAt(index).toByte
      index += 1

  private def appendCodePoint(
      bytes: ArrayBuffer[Byte],
      codePoint: Int
  ): Unit =
    if codePoint <= 0x7f then
      bytes += codePoint.toByte
    else if codePoint <= 0x7ff then
      bytes += (0xc0 | (codePoint >>> 6)).toByte
      bytes += (0x80 | (codePoint & 0x3f)).toByte
    else if codePoint <= 0xffff then
      bytes += (0xe0 | (codePoint >>> 12)).toByte
      bytes += (0x80 | ((codePoint >>> 6) & 0x3f)).toByte
      bytes += (0x80 | (codePoint & 0x3f)).toByte
    else
      bytes += (0xf0 | (codePoint >>> 18)).toByte
      bytes += (0x80 | ((codePoint >>> 12) & 0x3f)).toByte
      bytes += (0x80 | ((codePoint >>> 6) & 0x3f)).toByte
      bytes += (0x80 | (codePoint & 0x3f)).toByte

  private def appendLong(
      bytes: ArrayBuffer[Byte],
      value: Long
  ): Unit =
    var shift = 56
    while shift >= 0 do
      bytes += ((value >>> shift) & 0xffL).toByte
      shift -= 8

  private def splitmixFinalizer(input: Long): Long =
    var value = input
    value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L
    value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL
    value ^ (value >>> 31)

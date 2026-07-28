package alder.data

import alder.kernel.{Seed as AlderSeed, *}
import cats.kernel.Hash
import tessera.core.*

/** Interpretation of Tessera's ordinal plans as Alder row partitions.
  *
  * The `complete` constructor is total because `Coverage.ExactOnce` proves
  * exactly-once assessment coverage over the whole plan. It performs no
  * runtime coverage validation. Size, seed, and population-fingerprint checks
  * remain split-time compatibility checks between a bound plan and Alder data.
  */
object TesseraResampler:
  /** Bind an exact-once plan and its verification receipt as a complete Alder
    * resampler.
    *
    * The receipt must have been generated from the `Compiled` value that owns
    * `plan`. Prefer `fromCompiled` when both values are still available.
    */
  def complete[A](
      plan: Plan[Split[Selection], Coverage.ExactOnce],
      receipt: PlanReceipt
  ): CompleteResampler[A] =
    new TesseraCompleteResampler(plan, ReceiptMapping.render(receipt))

  /** Generate the receipt and bind its plan without allowing them to diverge. */
  def fromCompiled[A](
      compiled: Compiled[Split[Selection], Coverage.ExactOnce],
      population: Fingerprint
  )(using
      algorithm: DigestAlgorithm
  ): Either[DigestError, CompleteResampler[A]] =
    compiled
      .receipt(population)
      .map(receipt => complete(compiled.plan, receipt))

  /** Translate Alder's population identity into Tessera's policy-tagged
    * fingerprint vocabulary for receipt generation.
    */
  def populationFingerprint(
      fingerprint: DataFingerprint
  ): Either[DataError, Fingerprint] =
    ReceiptMapping.population(fingerprint)

  /** Canonically code Alder group metadata by first population ordinal. */
  def groupLabels[U <: Use, X, Y, M](
      data: NonEmptyData[U, Example[X, Y, M]]
  )(using
      groupOf: GroupOf[M],
      keyHash: Hash[groupOf.Key]
  ): Either[DesignError, Labels] =
    val rows = DataRows.collect(data.data)
    type Key = groupOf.Key
    val accepted = scala.collection.mutable.ArrayBuffer.empty[Key]
    val byHash =
      scala.collection.mutable.HashMap.empty[Int, scala.collection.mutable.ArrayBuffer[Int]]
    val codes = new Array[Int](rows.length)
    var index = 0
    while index < rows.length do
      val key: Key = groupOf(rows(index)._2.meta)
      val hash = keyHash.hash(key)
      val candidates =
        byHash.getOrElseUpdate(
          hash,
          scala.collection.mutable.ArrayBuffer.empty[Int]
        )
      var found = -1
      var candidateIndex = 0
      while candidateIndex < candidates.length && found < 0 do
        val acceptedIndex = candidates(candidateIndex)
        if keyHash.eqv(accepted(acceptedIndex), key) then found = acceptedIndex
        candidateIndex += 1
      if found >= 0 then codes(index) = found
      else
        codes(index) = accepted.length
        accepted += key
        candidates += (accepted.length - 1)
      index += 1
    Labels.dense(IArray.unsafeFromArray(codes), rows.length)

private final class TesseraCompleteResampler[A](
    plan: Plan[Split[Selection], Coverage.ExactOnce],
    receipt: TesseraPlanReceipt
) extends CompleteResampler[A]:
  val fingerprint: ResamplerFingerprint = receipt.design

  private[alder] def split[U <: Use.Fit](
      data: NonEmptyData[U, A],
      seed: AlderSeed
  ): Either[DataError, ResamplingPlan[U, A]] =
    val expectedSize = plan.first.assessment.codomain
    if data.size > Int.MaxValue.toLong then
      Left(DataError.TesseraPopulationTooLarge(data.size))
    else if data.size != expectedSize.toLong then
      Left(
        DataError.TesseraPopulationSizeMismatch(
          expectedSize,
          data.size
        )
      )
    else if seed.value != receipt.planSeed.value then
      Left(
        DataError.TesseraSeedMismatch(
          receipt.planSeed.value,
          seed.value
        )
      )
    else
      ReceiptMapping.population(data.fingerprint).flatMap { observed =>
        if !ReceiptMapping.same(observed, receipt.population) then
          Left(DataError.TesseraPopulationFingerprintMismatch)
        else materialize(data)
      }

  private def materialize[U <: Use.Fit](
      data: NonEmptyData[U, A]
  ): Either[DataError, ResamplingPlan[U, A]] =
    val rows = DataRows.collect(data.data)
    val initial =
      Right(Vector.empty): Either[
        DataError,
        Vector[ResamplingFold[U, A]]
      ]
    plan.iterator
      .map(_._2)
      .zipWithIndex
      .foldLeft(initial) { (result, indexed) =>
        val (split, foldIndex) = indexed
        result.flatMap { accepted =>
          val analysisRows =
            selectUnchecked(rows, split.analysis.toIArray)
          val assessmentRows =
            selectUnchecked(rows, split.assessment.toIArray)
          val analysisFingerprint =
            Fingerprints.partition(
              data.fingerprint,
              s"tessera/fold/$foldIndex/analysis",
              analysisRows
            )
          val assessmentFingerprint =
            Fingerprints.partition(
              data.fingerprint,
              s"tessera/fold/$foldIndex/assessment",
              assessmentRows
            )
          for
            analysis <- DataRows.nonEmpty(
              analysisRows,
              analysisFingerprint,
              data.refit
            )
            assessment <- DataRows.nonEmpty(
              assessmentRows,
              assessmentFingerprint,
              data.refit
            )
          yield
            accepted :+ new ResamplingFold(
              foldIndex,
              analysis,
              assessment,
              receipt.assignment
            )
        }
      }
      .map(folds =>
        new ResamplingPlan(
          folds,
          receipt.design,
          receipt.assignment,
          Some(receipt)
        )
      )

  /** Index validity follows from the validated Tessera selection and the
    * preceding codomain/data-size equality check.
    */
  private def selectUnchecked(
      rows: Vector[(RowId, A)],
      indices: IArray[Int]
  ): Vector[(RowId, A)] =
    Vector.tabulate(indices.length)(index => rows(indices(index)))

private object ReceiptMapping:
  def render(receipt: PlanReceipt): TesseraPlanReceipt =
    val design = protocol(receipt.design)
    new TesseraPlanReceipt(
      designAlgorithm = receipt.algorithm.value,
      digestAlgorithm = receipt.design.algorithm.value,
      design = design,
      population = data(receipt.population),
      labels = receipt.labels.map(data),
      planSeed = AlderSeed(receipt.seed.value),
      assignment = data(receipt.assignment)
    )

  def population(
      fingerprint: DataFingerprint
  ): Either[DataError, Fingerprint] =
    val invalid =
      DataError.InvalidTesseraPopulationFingerprint(
        fingerprint.policy,
        fingerprint.digest
      )
    fingerprint.policy match
      case FingerprintPolicy.ContentDigest(algorithm) =>
        for
          id <- DigestAlgorithmId
            .of(normalizeAlgorithm(algorithm))
            .left
            .map(_ => invalid)
          bytes <- decodeHex(fingerprint.digest).toRight(invalid)
          value <- DigestValue.fromBytes(bytes).left.map(_ => invalid)
        yield ContentDigest.of(id, value)
      case FingerprintPolicy.SourceIdentity(uri, version) =>
        SourceIdentity.of(uri, version).left.map(_ => invalid)
      case FingerprintPolicy.Summary(policyId) =>
        Summary
          .of(policyId, stableHash(fingerprint.digest))
          .left
          .map(_ => invalid)

  def same(left: Fingerprint, right: DataFingerprint): Boolean =
    same(data(left), right)

  private def same(
      left: DataFingerprint,
      right: DataFingerprint
  ): Boolean =
    left.policy == right.policy && left.digest == right.digest

  private def protocol(value: ContentDigest): ProtocolFingerprint =
    new ProtocolFingerprint(
      FingerprintPolicy.ContentDigest(value.algorithm.value),
      hex(value.value.toIArray)
    )

  private def data(value: Fingerprint): DataFingerprint =
    value match
      case digest: ContentDigest =>
        new DataFingerprint(
          FingerprintPolicy.ContentDigest(digest.algorithm.value),
          hex(digest.value.toIArray)
        )
      case source: SourceIdentity =>
        new DataFingerprint(
          FingerprintPolicy.SourceIdentity(source.uri, source.version),
          source.version
        )
      case summary: Summary =>
        new DataFingerprint(
          FingerprintPolicy.Summary(summary.policyId),
          summary.value.toString
        )

  private def normalizeAlgorithm(value: String): String =
    if value.lastIndexOf("/v") > 0 then value else s"$value/v1"

  private def decodeHex(value: String): Option[IArray[Byte]] =
    if value.isEmpty then None
    else
      val normalized = if value.length % 2 == 0 then value else s"0$value"
      val bytes = new Array[Byte](normalized.length / 2)
      var index = 0
      var valid = true
      while index < bytes.length && valid do
        val high = nibble(normalized.charAt(index * 2))
        val low = nibble(normalized.charAt(index * 2 + 1))
        if high < 0 || low < 0 then valid = false
        else bytes(index) = ((high << 4) | low).toByte
        index += 1
      if valid then Some(IArray.unsafeFromArray(bytes)) else None

  private def nibble(value: Char): Int =
    if value >= '0' && value <= '9' then value - '0'
    else if value >= 'a' && value <= 'f' then value - 'a' + 10
    else if value >= 'A' && value <= 'F' then value - 'A' + 10
    else -1

  private def stableHash(value: String): Long =
    var hash = 0xcbf29ce484222325L
    var index = 0
    while index < value.length do
      hash = (hash ^ value.charAt(index).toLong) * 0x100000001b3L
      index += 1
    hash

  private def hex(value: IArray[Byte]): String =
    val digits = "0123456789abcdef"
    val builder = new StringBuilder(value.length * 2)
    var index = 0
    while index < value.length do
      val byte = value(index).toInt & 0xff
      builder.append(digits.charAt(byte >>> 4))
      builder.append(digits.charAt(byte & 0x0f))
      index += 1
    builder.result()

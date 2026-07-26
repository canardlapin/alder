package alder.data

import alder.kernel.*

/** Domain name for the kernel's policy-tagged protocol fingerprint. */
type ResamplerFingerprint = ProtocolFingerprint

private[data] object Fingerprints:
  private val offset = 0xcbf29ce484222325L
  private val prime = 0x100000001b3L

  def configuration(parts: String*): ResamplerFingerprint =
    new ProtocolFingerprint(
      FingerprintPolicy.ContentDigest("fnv1a64"),
      hex(hashStrings(parts))
    )

  def partition(
      parent: DataFingerprint,
      label: String,
      rows: Vector[(RowId, ?)]
  ): DataFingerprint =
    val initial = hashString(hashString(offset, parent.digest), label)
    val digest = rows.foldLeft(initial)((hash, row) => hashLong(hash, row._1.value))
    new DataFingerprint(
      FingerprintPolicy.ContentDigest("fnv1a64"),
      hex(digest)
    )

  def assignment(
      parent: DataFingerprint,
      seed: Seed,
      assignments: Vector[(RowId, Int)]
  ): DataFingerprint =
    val initial = hashLong(hashString(offset, parent.digest), seed.value)
    val digest = assignments.foldLeft(initial) { (hash, assignment) =>
      hashLong(hashLong(hash, assignment._1.value), assignment._2.toLong)
    }
    new DataFingerprint(
      FingerprintPolicy.ContentDigest("fnv1a64"),
      hex(digest)
    )

  def derived(
      parent: DataFingerprint,
      label: String,
      parts: String*
  ): DataFingerprint =
    val initial = hashString(hashString(offset, parent.digest), label)
    val digest = parts.foldLeft(initial)(hashString)
    new DataFingerprint(
      FingerprintPolicy.Summary("alder.cross-fitted-derivation-fnv1a64"),
      hex(digest)
    )

  def rank(seed: Seed, id: RowId): Long =
    splitmix(seed.value ^ id.value)

  def observed(sources: Vector[ObservedSource]): DataFingerprint =
    val parts = sources.flatMap { source =>
      Vector(
        sourceRole(source.role),
        policy(source.fingerprint.policy),
        source.fingerprint.digest
      )
    }
    new DataFingerprint(
      FingerprintPolicy.Summary("alder.observed-sources-fnv1a64-v1"),
      hex(hashFramed("alder.observed-sources-v1" +: parts))
    )

  def evaluationReceipt(
      audit: Audit,
      observed: DataFingerprint,
      authorizingRole: EvaluationRole
  ): EvaluationReceiptId =
    EvaluationReceiptId(
      hex(
        hashFramed(
          Seq(
            "alder.evaluation-receipt-v1",
            audit.plan.render,
            policy(audit.data.policy),
            audit.data.digest,
            audit.component.id.render,
            audit.component.version.render,
            evaluationRole(authorizingRole),
            policy(observed.policy),
            observed.digest
          )
        )
      )
    )

  private def hashStrings(values: Seq[String]): Long =
    values.foldLeft(offset)(hashString)

  private def hashFramed(values: Seq[String]): Long =
    hashStrings(values.map(value => s"${value.length}:$value"))

  private def hashString(initial: Long, value: String): Long =
    var hash = initial
    var index = 0
    while index < value.length do
      hash = (hash ^ value.charAt(index).toLong) * prime
      index += 1
    hash

  private def hashLong(initial: Long, value: Long): Long =
    var hash = initial
    var shift = 0
    while shift < 64 do
      hash = (hash ^ ((value >>> shift) & 0xffL)) * prime
      shift += 8
    hash

  private def policy(value: FingerprintPolicy): String =
    value match
      case FingerprintPolicy.ContentDigest(algorithm) =>
        s"content:${algorithm.length}:$algorithm"
      case FingerprintPolicy.SourceIdentity(uri, version) =>
        s"source:${uri.length}:$uri:${version.length}:$version"
      case FingerprintPolicy.Summary(policyId) =>
        s"summary:${policyId.length}:$policyId"

  private def sourceRole(value: ObservedSourceRole): String =
    value match
      case ObservedSourceRole.Train      => "train"
      case ObservedSourceRole.Validation => "validation"
      case ObservedSourceRole.Test       => "test"

  private def evaluationRole(value: EvaluationRole): String =
    value match
      case EvaluationRole.Validation => "validation"
      case EvaluationRole.Test       => "test"

  private def hex(value: Long): String =
    val digits = "0123456789abcdef"
    val builder = new StringBuilder(16)
    var shift = 60
    var started = false
    while shift >= 0 do
      val digit = ((value >>> shift) & 0x0fL).toInt
      if digit != 0 || started || shift == 0 then
        builder.append(digits.charAt(digit))
        started = true
      shift -= 4
    builder.result()

  private def splitmix(input: Long): Long =
    var value = input
    value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L
    value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL
    value ^ (value >>> 31)

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
    val initial = hashFramed(
      Seq(
        "alder.data-partition-v2",
        policy(parent.policy),
        parent.digest,
        label
      )
    )
    val digest = rows.foldLeft(initial)((hash, row) => hashLong(hash, row._1.value))
    new DataFingerprint(
      FingerprintPolicy.ContentDigest("fnv1a64"),
      hex(digest)
    )

  def splitPolicy(value: SplitPolicy): ProtocolFingerprint =
    val parts =
      value match
        case SplitPolicy.Holdout(test) =>
          Vector("holdout", amount(test))
        case SplitPolicy.Validation(validation) =>
          Vector("validation", amount(validation))
        case SplitPolicy.TrainValidationTest(validation, test) =>
          Vector(
            "train-validation-test",
            amount(validation),
            amount(test)
          )
    new ProtocolFingerprint(
      FingerprintPolicy.Summary(
        "alder.split-policy-rank-v1-fnv1a64"
      ),
      hex(
        hashFramed(
          Vector(
            "alder.split-policy-v1",
            "rank-v1",
            "fraction-floor-total-n",
            "assignment-order:validation-test-train",
            "receipt-order:train-validation-test",
            "utf8-u32be-i64be",
            "fnv1a64-splitmix64-finalizer"
          ) ++ parts
        )
      )
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

  def crossFittedDerived(
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

  def evaluationDerived(
      parent: DataFingerprint,
      label: String,
      parts: String*
  ): DataFingerprint =
    val initial = hashFramed(
      Seq(
        "alder.evaluation-derivation-v1",
        policy(parent.policy),
        parent.digest,
        label
      )
    )
    val digest = parts.foldLeft(initial)(hashString)
    new DataFingerprint(
      FingerprintPolicy.Summary(
        "alder.evaluation-derivation-fnv1a64-v1"
      ),
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

  def predictionReceipt(
      audit: Audit,
      observed: DataFingerprint,
      authorizingRole: EvaluationRole
  ): PredictionReceiptId =
    val fitted = AuditFingerprint(audit)
    PredictionReceiptId(
      hex(
        hashFramed(
          Seq(
            "alder.prediction-receipt-v1",
            policy(fitted.policy),
            fitted.digest,
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

  private def amount(value: SplitAmount): String =
    value match
      case SplitAmount.Count(rows) =>
        s"rows:${rows.value}"
      case SplitAmount.Proportion(fraction) =>
        s"fraction:${fraction.numerator}/${fraction.denominator}"

  private def hex(value: Long): String =
    val digits = "0123456789abcdef"
    val builder = new StringBuilder(16)
    var shift = 60
    while shift >= 0 do
      val digit = ((value >>> shift) & 0x0fL).toInt
      builder.append(digits.charAt(digit))
      shift -= 4
    builder.result()

  private def splitmix(input: Long): Long =
    var value = input
    value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L
    value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL
    value ^ (value >>> 31)

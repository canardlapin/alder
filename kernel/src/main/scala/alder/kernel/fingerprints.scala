package alder.kernel

/** Every fingerprint records which policy produced it (D15 commitment 2). */
enum FingerprintPolicy derives CanEqual:
  case ContentDigest(algorithm: String)
  case SourceIdentity(uri: String, version: String)
  case Summary(policyId: String)

/** Fingerprint of a dataset's contents under a declared policy. */
final class DataFingerprint(val policy: FingerprintPolicy, val digest: String)

object DataFingerprint:
  /** Records an externally managed data identity.
    *
    * This is a convenience for applications that already version their input
    * data but do not compute a content digest inside Alder. The audit records
    * that the value is an external identity, not a content hash.
    */
  def external(identity: String): DataFingerprint =
    new DataFingerprint(
      FingerprintPolicy.Summary("alder.external-data-identity"),
      identity
    )

/** Policy-tagged identity of a protocol configuration, such as a resampler. */
final class ProtocolFingerprint(
    val policy: FingerprintPolicy,
    val digest: String
)

/** Policy-tagged fingerprint of a normalized logical plan.
  *
  * Stage identities and seed derivations key off both the policy and digest,
  * never off runtime combinator nesting. A summary identity is useful at an
  * application boundary, but does not claim that Alder hashed the plan.
  */
final case class PlanFingerprint(
    policy: FingerprintPolicy,
    digest: String
) derives CanEqual

object PlanFingerprint:
  /** Compatibility shorthand for an externally managed plan identity. */
  def apply(value: String): PlanFingerprint = external(value)

  def external(identity: String): PlanFingerprint =
    new PlanFingerprint(
      FingerprintPolicy.Summary("alder.external-plan-identity"),
      identity
    )

  def content(algorithm: String, digest: String): PlanFingerprint =
    new PlanFingerprint(
      FingerprintPolicy.ContentDigest(algorithm),
      digest
    )

  extension (fingerprint: PlanFingerprint)
    def render: String = fingerprint.digest

/** Canonical identity of a complete fitted audit.
  *
  * Prediction receipts use this internal value so a different seed, backend,
  * numerical mode, component configuration, lineage, or refit authority cannot
  * hide behind the same plan and source identities.
  */
private[alder] object AuditFingerprint:
  private val offset = 0xcbf29ce484222325L
  private val prime = 0x100000001b3L

  def apply(audit: Audit): ProtocolFingerprint =
    val bytes = AuditBinaryCodec.encode(audit)
    var hash = offset
    var index = 0
    while index < bytes.length do
      hash = (hash ^ (bytes(index).toLong & 0xffL)) * prime
      index += 1
    new ProtocolFingerprint(
      FingerprintPolicy.ContentDigest(
        "alder-audit-binary-v1-fnv1a64"
      ),
      hex(hash)
    )

  private def hex(value: Long): String =
    val digits = "0123456789abcdef"
    val builder = new StringBuilder(16)
    var shift = 60
    while shift >= 0 do
      builder.append(
        digits.charAt(((value >>> shift) & 0x0fL).toInt)
      )
      shift -= 4
    builder.result()

/** Policy-tagged fingerprint of an observation schema. */
final case class SchemaFingerprint(
    val policy: FingerprintPolicy,
    val digest: String
) derives CanEqual

object SchemaFingerprint:
  /** Compatibility constructor for callers supplying an externally managed
    * schema identity. Even this shorthand remains explicitly policy tagged.
    */
  def apply(value: String): SchemaFingerprint =
    new SchemaFingerprint(
      FingerprintPolicy.Summary("alder.external-schema-identity"),
      value
    )

  extension (fingerprint: SchemaFingerprint)
    def render: String = fingerprint.digest

/** Identity of the numerical backend a component actually used. Backends must
  * capture their configuration at construction and fingerprint it at exactly
  * that moment (D7); never summon ambient configuration inside a solve.
  */
final class BackendFingerprint(
    val id: String,
    val version: String,
    val details: AuditValue
)

/** Numeric determinism policy, recorded in every audit. */
enum NumericMode derives CanEqual:
  case Deterministic
  case FastMath
  case NonDeterministic(description: String)

/** Root and derived randomness. Child seeds derive from the parent seed and the
  * stable stage ordinal (splitmix64), so parenthesization cannot change them.
  */
opaque type Seed = Long

object Seed:
  def apply(value: Long): Seed = value

  given CanEqual[Seed, Seed] = CanEqual.derived

  extension (seed: Seed)
    def value: Long = seed
    private[alder] def child(
        plan: PlanFingerprint,
        ordinal: Int
    ): Seed =
      splitmix(
        seed ^ stablePlanHash(plan) ^
          (0x9e3779b97f4a7c15L * (ordinal.toLong + 1L))
      )

  /** Stable across JVM, Scala.js, and Scala Native. In particular, this does
    * not delegate to a platform String hash implementation.
    */
  private def stablePlanHash(plan: PlanFingerprint): Long =
    var hash = 0xcbf29ce484222325L
    hash = hashText(hash, "alder.plan-seed-v1")
    plan.policy match
      case FingerprintPolicy.ContentDigest(algorithm) =>
        hash = hashText(hash, "content-digest")
        hash = hashText(hash, algorithm)
      case FingerprintPolicy.SourceIdentity(uri, version) =>
        hash = hashText(hash, "source-identity")
        hash = hashText(hash, uri)
        hash = hashText(hash, version)
      case FingerprintPolicy.Summary(policyId) =>
        hash = hashText(hash, "summary")
        hash = hashText(hash, policyId)
    hashText(hash, plan.digest)

  private def hashText(initial: Long, value: String): Long =
    var hash = initial
    hash = hashLong(hash, value.length.toLong)
    var index = 0
    while index < value.length do
      val codeUnit = value.charAt(index).toLong
      hash = (hash ^ ((codeUnit >>> 8) & 0xffL)) * 0x100000001b3L
      hash = (hash ^ (codeUnit & 0xffL)) * 0x100000001b3L
      index += 1
    hash

  private def hashLong(initial: Long, value: Long): Long =
    var hash = initial
    var shift = 56
    while shift >= 0 do
      hash = (hash ^ ((value >>> shift) & 0xffL)) * 0x100000001b3L
      shift -= 8
    hash

  private def splitmix(input: Long): Long =
    var z = input
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
    z ^ (z >>> 31)

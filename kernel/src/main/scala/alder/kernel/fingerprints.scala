package alder.kernel

/** Every fingerprint records which policy produced it (D15 commitment 2). */
enum FingerprintPolicy derives CanEqual:
  case ContentDigest(algorithm: String)
  case SourceIdentity(uri: String, version: String)
  case Summary(policyId: String)

/** Fingerprint of a dataset's contents under a declared policy. */
final class DataFingerprint(val policy: FingerprintPolicy, val digest: String)

/** Fingerprint of a normalized logical plan. Stage identities and seed
  * derivations key off this, never off runtime combinator nesting.
  */
opaque type PlanFingerprint = String

object PlanFingerprint:
  def apply(value: String): PlanFingerprint = value
  extension (fingerprint: PlanFingerprint) def render: String = fingerprint

/** Fingerprint of the observation schema. */
opaque type SchemaFingerprint = String

object SchemaFingerprint:
  def apply(value: String): SchemaFingerprint = value
  extension (fingerprint: SchemaFingerprint) def render: String = fingerprint

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
    def child(ordinal: Int): Seed =
      splitmix(seed + 0x9e3779b97f4a7c15L * (ordinal.toLong + 1L))

  private def splitmix(input: Long): Long =
    var z = input
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
    z ^ (z >>> 31)

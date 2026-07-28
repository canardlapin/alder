package alder.kernel

/** Stable identity for a behaviour-bearing metadata or feature policy. */
final case class PolicyDescriptor(
    id: String,
    version: String,
    parameters: AuditValue = AuditValue.record()
):
  def asAuditValue: AuditValue =
    AuditValue.record(
      "id" -> AuditValue.text(id),
      "version" -> AuditValue.text(version),
      "parameters" -> parameters
    )

/** A training observation. `M` is real application metadata, never a string
  * map; requirements on it are typeclasses (WeightOf, GroupOf, TimeOf) visible
  * in signatures.
  */
final case class Example[+X, +Y, +M](input: X, target: Y, meta: M)

/** A scored observation: ground truth paired with a model's prediction. */
final case class Scored[+Y, +P, +M](truth: Y, prediction: P, meta: M)

/** Metadata capability: observation weight. */
trait WeightOf[M]:
  def apply(meta: M): Double

/** Auditable weight interpretation. Prefer this over a bare [[WeightOf]]. */
trait WeightPolicy[M] extends WeightOf[M]:
  def descriptor: PolicyDescriptor

/** Metadata capability: grouping key for grouped resampling. */
trait GroupOf[M]:
  type Key
  def apply(meta: M): Key

/** Auditable grouping interpretation. Prefer this over a bare [[GroupOf]]. */
trait GroupPolicy[M] extends GroupOf[M]:
  def descriptor: PolicyDescriptor

/** Metadata capability: observation time for rolling-origin resampling. */
trait TimeOf[M]:
  type Instant
  def apply(meta: M): Instant

/** Auditable time interpretation. Prefer this over a bare [[TimeOf]]. */
trait TimePolicy[M] extends TimeOf[M]:
  def descriptor: PolicyDescriptor

/** Named, versioned total function for audited FeatureMap postprocessing. */
final case class NamedMap[A, B](
    name: String,
    version: String,
    run: A => B
):
  def apply(value: A): B = run(value)

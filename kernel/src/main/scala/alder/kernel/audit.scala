package alder.kernel

/** Structured audit parameters supplied by components. */
enum AuditValue derives CanEqual:
  case Integer(value: Long)
  case Decimal(value: Double)
  case Text(value: String)
  case Bool(value: Boolean)
  case Sequence(values: Vector[AuditValue])
  case Record(fields: Vector[(String, AuditValue)])

object AuditValue:
  def integer(value: Long): AuditValue = Integer(value)
  def decimal(value: Double): AuditValue = Decimal(value)
  def text(value: String): AuditValue = Text(value)
  def bool(value: Boolean): AuditValue = Bool(value)
  def sequence(values: AuditValue*): AuditValue = Sequence(values.toVector)
  def record(fields: (String, AuditValue)*): AuditValue = Record(fields.toVector)

opaque type ComponentId = String

object ComponentId:
  def apply(value: String): ComponentId = value
  extension (id: ComponentId) def render: String = id

opaque type ComponentVersion = String

object ComponentVersion:
  def apply(value: String): ComponentVersion = value
  extension (version: ComponentVersion) def render: String = version

/** What a plugin contributes to its audit: identity, version, validated
  * configuration, and the backend it explicitly selected. Everything else is
  * framework-supplied via [[FitContext.complete]] and cannot be omitted.
  */
final case class ComponentDescriptor(
    id: ComponentId,
    version: ComponentVersion,
    parameters: AuditValue,
    backend: BackendFingerprint
)

/** Runtime tag mirroring the phantom [[Preparation]] scope, for audits. */
enum PreparationScopeTag derives CanEqual:
  case Reusable
  case LearnerReady

/** How prepared training rows were produced. Plan-shaped and per-fold in
  * production — never per-row (D15). Full schema is deliberately deferred
  * until FeatureMap.crossFitted forces it (O6); this type is a final class so
  * it can grow fields compatibly.
  */
final class PreparationLineage private[alder] (
    val stage: StagePath,
    val scope: PreparationScopeTag,
    val children: Vector[PreparationLineage]
)

object PreparationLineage:
  private[alder] def leaf(
      stage: StagePath,
      scope: PreparationScopeTag
  ): PreparationLineage =
    new PreparationLineage(stage, scope, Vector.empty)

  private[alder] def sequence(
      stage: StagePath,
      scope: PreparationScopeTag,
      children: Vector[PreparationLineage]
  ): PreparationLineage =
    new PreparationLineage(stage, scope, children)

/** Mandatory provenance, part of the result of fitting — not an
  * experiment-tracker side channel. Final class, not a case class: it sits on
  * the frozen compatibility surface and must be able to gain fields (D15).
  */
final class Audit private[alder] (
    val plan: PlanFingerprint,
    val data: DataFingerprint,
    val schema: SchemaFingerprint,
    val seed: Seed,
    val backend: BackendFingerprint,
    val numerics: NumericMode,
    val preparation: PreparationLineage,
    val component: ComponentDescriptor,
    val children: Vector[Audit]
)

/** A fitted artifact paired with its mandatory audit. Constructed only through
  * [[FitContext.complete]] (D11): the framework, not the plugin, supplies
  * framework-level provenance.
  */
final class Trained[+A] private[alder] (val artifact: A, val audit: Audit)

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

/** Role of one exact source committed by an evaluation receipt. */
enum ObservedSourceRole derives CanEqual:
  case Train
  case Validation
  case Test

/** A role on which predictions may be evaluated. Train is intentionally
  * absent.
  */
enum EvaluationRole derives CanEqual:
  case Validation
  case Test

/** One source in the ordered manifest authorizing a refit. */
final case class ObservedSource(
    role: ObservedSourceRole,
    fingerprint: DataFingerprint
)

/** Stable public identity of a complete prediction pass. */
opaque type PredictionReceiptId = String

object PredictionReceiptId:
  private[alder] def apply(value: String): PredictionReceiptId = value
  extension (id: PredictionReceiptId) def render: String = id
  given CanEqual[PredictionReceiptId, PredictionReceiptId] = CanEqual.derived

/** Stable public identity of a scored evaluation. The authority itself is
  * private and cannot be reconstructed from this identifier.
  */
opaque type EvaluationReceiptId = String

object EvaluationReceiptId:
  private[alder] def apply(value: String): EvaluationReceiptId = value
  extension (id: EvaluationReceiptId) def render: String = id
  given CanEqual[EvaluationReceiptId, EvaluationReceiptId] = CanEqual.derived

/** Stable public identity of an explicit model-selection decision. */
opaque type SelectionReceiptId = String

object SelectionReceiptId:
  private[alder] def apply(value: String): SelectionReceiptId = value
  extension (id: SelectionReceiptId) def render: String = id
  given CanEqual[SelectionReceiptId, SelectionReceiptId] = CanEqual.derived

/** Explicit audit statement about the artifact produced by a refit. */
enum RefitEvaluationClaim derives CanEqual:
  case ArtifactNotEvaluatedOnAuthorizingValidation
  case ArtifactNotEvaluatedOnAuthorizingTest

/** Receipt-gated authority attached to `Use.Refit` data and copied into every
  * audit produced from it. Construction remains framework-owned.
  */
final class RefitAudit private[alder] (
    val sources: Vector[ObservedSource],
    val evaluationReceipt: EvaluationReceiptId,
    val selectionReceipt: Option[SelectionReceiptId],
    val claim: RefitEvaluationClaim
):
  /** Compatibility name for the evaluation evidence in this refit. */
  def receipt: EvaluationReceiptId = evaluationReceipt

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

private[alder] enum PreparationLineageShape derives CanEqual:
  case Leaf
  case Sequence
  case CrossFitted

/** One compact cross-fitting fold receipt. Membership is committed by the
  * parent assignment digest; production audits contain no RowId lists.
  */
final class CrossFitFoldLineage private[alder] (
    val index: Int,
    val analysis: DataFingerprint,
    val assessment: DataFingerprint,
    val fittedState: PreparationLineage
)

/** Alder-owned, dependency-neutral rendering of a Resample4s verification
  * receipt. Policy tags and digest values are retained without making
  * alder-kernel depend on resample4s-core.
  */
final class Resample4sPlanReceipt private[alder] (
    val designAlgorithm: String,
    val digestAlgorithm: String,
    val design: ProtocolFingerprint,
    val population: DataFingerprint,
    val labels: Option[DataFingerprint],
    val planSeed: Seed,
    val assignment: DataFingerprint
)

/** Reproducible, privacy-bounded cross-fitting protocol receipt (D21). */
final class CrossFitLineage private[alder] (
    val resampler: ProtocolFingerprint,
    val seed: Seed,
    val assignment: DataFingerprint,
    val folds: Vector[CrossFitFoldLineage],
    val serving: PreparationLineage,
    val resample4s: Option[Resample4sPlanReceipt]
)

/** How prepared training rows were produced. Plan-shaped and per-fold in
  * production — never per-row (D15). Full schema is deliberately deferred
  * until FeatureMap.crossFitted forces it (O6); this type is a final class so
  * it can grow fields compatibly.
  */
final class PreparationLineage private[alder] (
    val stage: StagePath,
    val scope: PreparationScopeTag,
    val children: Vector[PreparationLineage],
    val crossFit: Option[CrossFitLineage],
    private[alder] val shape: PreparationLineageShape
):
  private[alder] def flattenedSequence: Vector[PreparationLineage] =
    shape match
      case PreparationLineageShape.Leaf     => Vector(this)
      case PreparationLineageShape.Sequence =>
        children.flatMap(_.flattenedSequence)
      case PreparationLineageShape.CrossFitted => Vector(this)

object PreparationLineage:
  private[alder] def leaf(
      stage: StagePath,
      scope: PreparationScopeTag
  ): PreparationLineage =
    new PreparationLineage(
      stage,
      scope,
      Vector.empty,
      None,
      PreparationLineageShape.Leaf
    )

  private[alder] def sequence(
      stage: StagePath,
      scope: PreparationScopeTag,
      children: Vector[PreparationLineage]
  ): PreparationLineage =
    new PreparationLineage(
      stage,
      scope,
      children.flatMap(_.flattenedSequence),
      None,
      PreparationLineageShape.Sequence
    )

  private[alder] def crossFitted(
      stage: StagePath,
      receipt: CrossFitLineage
  ): PreparationLineage =
    new PreparationLineage(
      stage,
      PreparationScopeTag.LearnerReady,
      receipt.folds.map(_.fittedState) :+ receipt.serving,
      Some(receipt),
      PreparationLineageShape.CrossFitted
    )

private[alder] enum AuditShape derives CanEqual:
  case Leaf
  case Composite
  case TransformSequence
  case FeatureMapSequence
  case FoldEncoderSequence
  case WorkflowSequence

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
    val children: Vector[Audit],
    val refit: Option[RefitAudit],
    private[alder] val shape: AuditShape
):
  private[alder] def flattenedTransformSequence: Vector[Audit] =
    shape match
      case AuditShape.TransformSequence =>
        children.flatMap(_.flattenedTransformSequence)
      case AuditShape.Leaf | AuditShape.Composite |
          AuditShape.FeatureMapSequence | AuditShape.FoldEncoderSequence |
          AuditShape.WorkflowSequence =>
        Vector(this)

  private[alder] def flattenedPreparationSequence: Vector[Audit] =
    shape match
      case AuditShape.TransformSequence | AuditShape.FeatureMapSequence |
          AuditShape.FoldEncoderSequence | AuditShape.WorkflowSequence =>
        children.flatMap(_.flattenedPreparationSequence)
      case AuditShape.Leaf | AuditShape.Composite => Vector(this)

/** A fitted artifact paired with its mandatory audit. Constructed only through
  * [[FitContext.complete]] (D11): the framework, not the plugin, supplies
  * framework-level provenance.
  */
final class Trained[+A] private[alder] (val artifact: A, val audit: Audit)

extension [X, E, B, A <: Pipe[X, E, B]](trained: Trained[A])
  /** Runs a trained pipe while retaining the audit wrapper for later
    * inspection or serialization.
    */
  def run(input: X): Either[Failure[E], B] =
    trained.artifact.run(input)

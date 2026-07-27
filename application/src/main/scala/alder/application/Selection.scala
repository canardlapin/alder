package alder.application

import alder.data.*
import alder.kernel.*
import alder.metrics.*

/** Explicit decision that may grant broader fitting-data access. */
enum SelectionPolicy derives CanEqual:
  case SingleCandidate

val SingleCandidate: SelectionPolicy.SingleCandidate.type =
  SelectionPolicy.SingleCandidate

/** Explicit evidence that one validation-scored candidate was selected. */
final class SelectionReceipt[
    L,
    Mt,
    S
] private[application] (
    val id: SelectionReceiptId,
    val plan: PlanFingerprint,
    val evaluation: EvaluationReceiptId,
    val source: DataFingerprint,
    val scored: DataFingerprint,
    val descriptor: MetricDescriptor,
    val score: S,
    val auditedScore: AuditValue,
    val direction: ObjectiveDirection,
    val policy: SelectionPolicy,
    val learner: L,
    val metric: Mt,
    val sources: Vector[ObservedSource],
    private[alder] val authority: PromotionAuthority[Use.Validation]
)

extension [
    X,
    Y,
    M,
    P,
    S,
    Mt <: ObjectiveMetric[Scored[Y, P, M], S]
](
    evaluation: ScoredEvaluation[
      Use.Validation,
      X,
      Y,
      M,
      P,
      S,
      Mt
    ]
)
  /** Selects the only candidate explicitly, authorizing a later refit on the
    * exact Train+Validation manifest.
    */
  def select[L](
      learner: L,
      policy: SelectionPolicy.SingleCandidate.type
  ): SelectionReceipt[L, Mt, S] =
    val metric = evaluation.metric
    val auditedScore = metric.auditScore(evaluation.score)
    val id = ReceiptHash.selection(
      evaluation.plan,
      evaluation.receipt.id,
      metric.descriptor,
      auditedScore,
      metric.direction,
      policy
    )
    new SelectionReceipt(
      id,
      evaluation.plan,
      evaluation.receipt.id,
      evaluation.allObserved.fingerprint,
      evaluation.scored.fingerprint,
      metric.descriptor,
      evaluation.score,
      auditedScore,
      metric.direction,
      policy,
      learner,
      metric,
      evaluation.receipt.sources,
      evaluation.receipt.authority
    )

/** Runtime rejection of same-role receipt substitution or one-shot reuse. */
enum ApplicationRefitError derives CanEqual:
  case SelectionReceiptMismatch(receipt: SelectionReceiptId)
  case EvaluationReceiptMismatch(receipt: EvaluationReceiptId)
  case SelectionReceiptAlreadyUsed(receipt: SelectionReceiptId)
  case EvaluationReceiptAlreadyUsed(receipt: EvaluationReceiptId)

extension (refit: Refit.type)
  def after[L, Mt, S](
      receipt: SelectionReceipt[L, Mt, S]
  ): SelectedRefitPromotion[L, Mt, S] =
    val _ = refit
    new SelectedRefitPromotion(receipt)

  def after(
      receipt: EvaluationReceipt[Use.Test]
  ): DeploymentRefitPromotion =
    val _ = refit
    new DeploymentRefitPromotion(receipt)

final class SelectedRefitPromotion[
    L,
    Mt,
    S
] private[application] (
    receipt: SelectionReceipt[L, Mt, S]
):
  /** Promotes only the validation bundle bound to this selection receipt. */
  def from[A](
      observed: AllObserved[Use.Validation, A]
  ): Either[
    ApplicationRefitError,
    NonEmptyData[Use.Refit, A]
  ] =
    val audit = new RefitAudit(
      receipt.sources,
      receipt.evaluation,
      Some(receipt.id),
      RefitEvaluationClaim
        .ArtifactNotEvaluatedOnAuthorizingValidation
    )
    Promotion
      .refit(receipt.authority, observed, audit)
      .left
      .map {
        case PromotionError.AuthorityMismatch =>
          ApplicationRefitError.SelectionReceiptMismatch(
            receipt.id
          )
        case PromotionError.AuthorityAlreadyUsed =>
          ApplicationRefitError.SelectionReceiptAlreadyUsed(
            receipt.id
          )
      }

final class DeploymentRefitPromotion private[application] (
    receipt: EvaluationReceipt[Use.Test]
):
  /** Promotes only the Test bundle bound to this evaluation receipt. */
  def from[A](
      observed: AllObserved[Use.Test, A]
  ): Either[
    ApplicationRefitError,
    NonEmptyData[Use.Refit, A]
  ] =
    val audit = new RefitAudit(
      receipt.sources,
      receipt.id,
      receipt.priorSelection,
      RefitEvaluationClaim.ArtifactNotEvaluatedOnAuthorizingTest
    )
    Promotion
      .refit(receipt.authority, observed, audit)
      .left
      .map {
        case PromotionError.AuthorityMismatch =>
          ApplicationRefitError.EvaluationReceiptMismatch(
            receipt.id
          )
        case PromotionError.AuthorityAlreadyUsed =>
          ApplicationRefitError.EvaluationReceiptAlreadyUsed(
            receipt.id
          )
      }

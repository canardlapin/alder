package alder.data

import alder.kernel.*
import java.util.concurrent.atomic.AtomicBoolean

/** Invalid evaluation-source or prior-refit provenance. */
enum RefitError derives CanEqual:
  case EmptyEvaluationSource(role: EvaluationRole)
  case DuplicateObservedRow(id: RowId)
  case MissingPriorRefitAudit
  case PriorRefitWasNotSelected
  case PriorSourcesMustEndInValidation(
      actual: Vector[ObservedSourceRole]
  )
  case PriorFingerprintMismatch(
      expected: DataFingerprint,
      actual: DataFingerprint
  )

/** Exact refit evidence copied into fitted-data and model audits. */
final case class RefitEvidence(
    evaluation: EvaluationReceiptId,
    selection: Option[SelectionReceiptId]
) derives CanEqual

sealed trait EvaluationError[+E]

object EvaluationError:
  final case class FitSourceMismatch(
      expected: DataFingerprint,
      actual: DataFingerprint
  ) extends EvaluationError[Nothing]

  final case class FitAuditMismatch(
      expected: Option[RefitEvidence],
      actual: Option[RefitEvidence]
  ) extends EvaluationError[Nothing]

  final case class PredictionFailed[E](
      failure: Failure[E]
  ) extends EvaluationError[E]

/** Exact fitting and held-out sources for one prediction or evaluation run.
  *
  * Construction is role-specific and validates nonemptiness, disjoint RowIds,
  * and any prior refit provenance before a model is executed.
  */
final class EvaluationSources[U <: Use.Evaluation, +A] private[data] (
    private[alder] val fitted: NonEmptyData[Use.Fit, A],
    private[alder] val heldOut: NonEmptyData[U, A],
    private[alder] val observedRows: Vector[(RowId, A)],
    val sources: Vector[ObservedSource],
    val authorizingRole: EvaluationRole,
    val fingerprint: DataFingerprint
)

object EvaluationSources:
  def validation[A](
      train: NonEmptyData[Use.Train, A],
      validation: Data[Use.Validation, A]
  ): Either[RefitError, EvaluationSources[Use.Validation, A]] =
    val manifest = Vector(
      ObservedSource(ObservedSourceRole.Train, train.fingerprint),
      ObservedSource(
        ObservedSourceRole.Validation,
        validation.fingerprint
      )
    )
    build(train, validation, manifest, EvaluationRole.Validation)

  /** Builds the honest Train/Test route for a precommitted holdout.
    *
    * This route makes no validation, selection, or final-test claim.
    */
  def precommittedTest[A](
      train: NonEmptyData[Use.Train, A],
      test: Data[Use.Test, A]
  ): Either[RefitError, EvaluationSources[Use.Test, A]] =
    val manifest = Vector(
      ObservedSource(ObservedSourceRole.Train, train.fingerprint),
      ObservedSource(ObservedSourceRole.Test, test.fingerprint)
    )
    build(train, test, manifest, EvaluationRole.Test)

  /** Builds the final Test route after an explicitly selected validation
    * candidate was refitted on the exact Train+Validation manifest.
    */
  def finalTest[A](
      fitted: NonEmptyData[Use.Refit, A],
      test: Data[Use.Test, A]
  ): Either[RefitError, EvaluationSources[Use.Test, A]] =
    fitted.refit match
      case None => Left(RefitError.MissingPriorRefitAudit)
      case Some(prior) =>
        val roles = prior.sources.map(_.role)
        if roles != Vector(
            ObservedSourceRole.Train,
            ObservedSourceRole.Validation
          )
        then Left(RefitError.PriorSourcesMustEndInValidation(roles))
        else if prior.selectionReceipt.isEmpty then
          Left(RefitError.PriorRefitWasNotSelected)
        else
          val expected = Fingerprints.observed(prior.sources)
          if !sameFingerprint(expected, fitted.fingerprint) then
            Left(
              RefitError.PriorFingerprintMismatch(
                expected,
                fitted.fingerprint
              )
            )
          else
            build(
              fitted,
              test,
              prior.sources :+
                ObservedSource(ObservedSourceRole.Test, test.fingerprint),
              EvaluationRole.Test
            )

  private def build[U <: Use.Evaluation, A](
      fitted: NonEmptyData[Use.Fit, A],
      heldOut: Data[U, A],
      manifest: Vector[ObservedSource],
      role: EvaluationRole
  ): Either[RefitError, EvaluationSources[U, A]] =
    val heldOutRows = DataRows.collect(heldOut)
    if heldOutRows.isEmpty then Left(RefitError.EmptyEvaluationSource(role))
    else
      val fittedRows = DataRows.collect(fitted.data)
      val observedRows = fittedRows ++ heldOutRows
      firstDuplicate(observedRows) match
        case Some(id) => Left(RefitError.DuplicateObservedRow(id))
        case None =>
          val nonEmptyHeldOut =
            new NonEmptyData(
              new InMemoryData[U, A](
                heldOutRows,
                heldOut.fingerprint
              )
            )
          Right(
            new EvaluationSources(
              fitted,
              nonEmptyHeldOut,
              observedRows,
              manifest,
              role,
              Fingerprints.observed(manifest)
            )
          )

  private def firstDuplicate[A](
      rows: Vector[(RowId, A)]
  ): Option[RowId] =
    rows
      .foldLeft((Set.empty[RowId], Option.empty[RowId])) {
        case ((seen, duplicate @ Some(_)), _) =>
          (seen, duplicate)
        case ((seen, None), (id, _)) =>
          if seen.contains(id) then (seen, Some(id))
          else (seen + id, None)
      }
      ._2

  private def sameFingerprint(
      left: DataFingerprint,
      right: DataFingerprint
  ): Boolean =
    left.policy == right.policy && left.digest == right.digest

/** The exact observations covered by one successful prediction pass.
  *
  * There is no public constructor or generic retag operation. The role
  * parameter prevents validation and test evidence from being exchanged.
  */
final class AllObserved[
    U <: Use.Evaluation,
    +A
] private[data] (
    private[alder] val rows: Vector[(RowId, A)],
    val fingerprint: DataFingerprint,
    val sources: Vector[ObservedSource],
    private[alder] val authority: PromotionAuthority[U]
):
  def size: Long = rows.length.toLong

  /** Folds the observed values for reporting without laundering them back
    * through an `Unsplit` data constructor.
    */
  def foldRows[B](initial: B)(step: (B, RowId, A) => B): B =
    rows.foldLeft(initial)((acc, row) => step(acc, row._1, row._2))

/** Reproducible evidence that every held-out row produced a prediction.
  *
  * A prediction receipt grants no refit authority. Scoring in
  * `alder-application` must finish successfully before stronger evidence can
  * be minted.
  */
final class PredictionReceipt[
    U <: Use.Evaluation
] private[alder] (
    val id: PredictionReceiptId,
    val sources: Vector[ObservedSource],
    val role: EvaluationRole,
    val priorSelection: Option[SelectionReceiptId]
)

/** One original held-out observation paired with its successful prediction. */
final case class Predicted[+A, +B](observation: A, prediction: B)

/** Successful held-out predictions plus the exact observed-data bundle to
  * which later scored evidence may be bound.
  */
final class PredictionResult[
    U <: Use.Evaluation,
    +A,
    +B
] private[data] (
    val predictions: NonEmptyData[U, B],
    val predicted: NonEmptyData[U, Predicted[A, B]],
    val receipt: PredictionReceipt[U],
    val allObserved: AllObserved[U, A],
    private[alder] val heldOut: NonEmptyData[U, A],
    private[alder] val authority: PromotionAuthority[U]
)

object Prediction:
  /** Predicts every held-out value with a model fitted on the declared source. */
  def run[
      U <: Use.Evaluation,
      A,
      E,
      B,
      P <: Pipe[A, E, B]
  ](
      trained: Trained[P],
      sources: EvaluationSources[U, A]
  ): Either[EvaluationError[E], PredictionResult[U, A, B]] =
    runBy(trained, sources)(identity)

  /** Predicts from a projection while preserving the original held-out rows.
    *
    * Scored evaluation uses this to pass `Example.input` to a trained model
    * without losing truth, metadata, or RowId.
    */
  def runBy[
      U <: Use.Evaluation,
      A,
      X,
      E,
      B,
      P <: Pipe[X, E, B]
  ](
      trained: Trained[P],
      sources: EvaluationSources[U, A]
  )(
      input: A => X
  ): Either[EvaluationError[E], PredictionResult[U, A, B]] =
    if !sameFingerprint(trained.audit.data, sources.fitted.fingerprint) then
      Left(
        EvaluationError.FitSourceMismatch(
          expected = sources.fitted.fingerprint,
          actual = trained.audit.data
        )
      )
    else if evidence(trained.audit.refit) != evidence(sources.fitted.refit)
    then
      Left(
        EvaluationError.FitAuditMismatch(
          expected = evidence(sources.fitted.refit),
          actual = evidence(trained.audit.refit)
        )
      )
    else
      val predictions = sources.heldOut.data.foldRows[
        Either[
          EvaluationError[E],
          Vector[(RowId, Predicted[A, B])]
        ]
      ](Right(Vector.empty)) {
        case (Left(error), _, _) => Left(error)
        case (Right(rows), id, value) =>
          trained.artifact
            .run(input(value))
            .left
            .map(EvaluationError.PredictionFailed(_))
            .map(prediction =>
              rows :+ (id, Predicted(value, prediction))
            )
      }
      predictions.map { rows =>
        val authority = new PromotionAuthority[U]
        val receiptId = Fingerprints.predictionReceipt(
          trained.audit,
          sources.fingerprint,
          sources.authorizingRole
        )
        val receipt = new PredictionReceipt[U](
          receiptId,
          sources.sources,
          sources.authorizingRole,
          trained.audit.refit.flatMap(_.selectionReceipt)
        )
        val observed = new AllObserved[U, A](
          sources.observedRows,
          sources.fingerprint,
          sources.sources,
          authority
        )
        val predictionFingerprint = Fingerprints.evaluationDerived(
          sources.heldOut.fingerprint,
          "evaluation/predictions",
          receiptId.render
        )
        val predictedFingerprint = Fingerprints.evaluationDerived(
          sources.heldOut.fingerprint,
          "evaluation/predicted-observations",
          receiptId.render
        )
        new PredictionResult(
          new NonEmptyData(
            new InMemoryData[U, B](
              rows.map((id, value) => (id, value.prediction)),
              predictionFingerprint
            )
          ),
          new NonEmptyData(
            new InMemoryData[U, Predicted[A, B]](
              rows,
              predictedFingerprint
            )
          ),
          receipt,
          observed,
          sources.heldOut,
          authority
        )
      }

  private def sameFingerprint(
      left: DataFingerprint,
      right: DataFingerprint
  ): Boolean =
    left.policy == right.policy && left.digest == right.digest

  private def evidence(
      refit: Option[RefitAudit]
  ): Option[RefitEvidence] =
    refit.map(value =>
      RefitEvidence(
        value.evaluationReceipt,
        value.selectionReceipt
      )
    )

/** Alder-internal one-shot link between scored evidence and its exact observed
  * bundle. Public receipt identifiers cannot reconstruct this capability.
  */
private[alder] final class PromotionAuthority[
    U <: Use.Evaluation
]:
  private val used = new AtomicBoolean(false)

  private[alder] def consume[A](
      observed: AllObserved[U, A]
  ): Either[PromotionError, Unit] =
    if this ne observed.authority then
      Left(PromotionError.AuthorityMismatch)
    else if !used.compareAndSet(false, true) then
      Left(PromotionError.AuthorityAlreadyUsed)
    else Right(())

private[alder] enum PromotionError derives CanEqual:
  case AuthorityMismatch
  case AuthorityAlreadyUsed

/** Data-owned promotion primitive used only by the application receipt layer. */
private[alder] object Promotion:
  def refit[U <: Use.Evaluation, A](
      authority: PromotionAuthority[U],
      observed: AllObserved[U, A],
      audit: RefitAudit
  ): Either[PromotionError, NonEmptyData[Use.Refit, A]] =
    authority.consume(observed).map { _ =>
      new NonEmptyData(
        new InMemoryData[Use.Refit, A](
          observed.rows,
          observed.fingerprint
        ),
        Some(audit)
      )
    }

/** Namespace extended by alder-application with receipt-gated transitions. */
object Refit

package alder.data

import alder.kernel.*
import java.util.concurrent.atomic.AtomicBoolean

enum RefitError derives CanEqual:
  case EmptyEvaluationSource(role: EvaluationRole)
  case DuplicateObservedRow(id: RowId)
  case MissingPriorRefitAudit
  case PriorSourcesMustEndInValidation(
      actual: Vector[ObservedSourceRole]
  )
  case PriorFingerprintMismatch(
      expected: DataFingerprint,
      actual: DataFingerprint
  )
  case ReceiptMismatch(receipt: EvaluationReceiptId)
  case ReceiptAlreadyUsed(receipt: EvaluationReceiptId)

sealed trait EvaluationError[+E]

object EvaluationError:
  final case class FitSourceMismatch(
      expected: DataFingerprint,
      actual: DataFingerprint
  ) extends EvaluationError[Nothing]

  final case class FitAuditMismatch(
      expected: Option[EvaluationReceiptId],
      actual: Option[EvaluationReceiptId]
  ) extends EvaluationError[Nothing]

  final case class PredictionFailed[E](
      failure: Failure[E]
  ) extends EvaluationError[E]

/** Exact fitting and held-out sources for one evaluation run. Construction is
  * role-specific and validates nonemptiness, disjoint RowIds, and prior refit
  * provenance before a model is executed.
  */
final class EvaluationSources[U <: Use.Evaluation, +A] private[data] (
    private[data] val fitted: NonEmptyData[Use.Fit, A],
    private[data] val heldOut: NonEmptyData[U, A],
    private[data] val observedRows: Vector[(RowId, A)],
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
      ObservedSource(ObservedSourceRole.Validation, validation.fingerprint)
    )
    build(
      train,
      validation,
      manifest,
      EvaluationRole.Validation
    )

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
        then
          Left(RefitError.PriorSourcesMustEndInValidation(roles))
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
              new InMemoryData[U, A](heldOutRows, heldOut.fingerprint)
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

/** The exact observations paired with one successful evaluation receipt.
  * There is deliberately no public constructor or generic retag operation.
  */
final class AllObserved[+A] private[data] (
    private[data] val rows: Vector[(RowId, A)],
    val fingerprint: DataFingerprint,
    val sources: Vector[ObservedSource],
    private[data] val authority: ReceiptAuthority
) extends Data[Use.Unsplit, A]:
  def size: Long = rows.length.toLong

  def foldRows[B](initial: B)(step: (B, RowId, A) => B): B =
    rows.foldLeft(initial)((acc, row) => step(acc, row._1, row._2))

/** Unforgeable, one-shot authority emitted only after every held-out row was
  * successfully evaluated. `id` is reproducible audit identity; the private
  * authority token is what prevents substitution.
  */
final class EvaluationReceipt private[data] (
    val id: EvaluationReceiptId,
    val sources: Vector[ObservedSource],
    val authorizingRole: EvaluationRole,
    private[data] val authority: ReceiptAuthority
):
  // Deliberate, localized authority state: compare-and-set is the runtime
  // enforcement of the receipt's one-shot law, including concurrent callers.
  private val used = new AtomicBoolean(false)

  private[data] def authorize[A](
      observed: AllObserved[A]
  ): Either[RefitError, RefitAudit] =
    if authority ne observed.authority then
      Left(RefitError.ReceiptMismatch(id))
    else if !used.compareAndSet(false, true) then
      Left(RefitError.ReceiptAlreadyUsed(id))
    else
      val claim =
        authorizingRole match
          case EvaluationRole.Validation =>
            RefitEvaluationClaim
              .ArtifactNotEvaluatedOnAuthorizingValidation
          case EvaluationRole.Test =>
            RefitEvaluationClaim.ArtifactNotEvaluatedOnAuthorizingTest
      Right(new RefitAudit(sources, id, claim))

private[data] final class ReceiptAuthority

/** Successful predictions plus the only data bundle the emitted receipt may
  * promote.
  */
final class EvaluationResult[
    U <: Use.Evaluation,
    +A,
    +B
] private[data] (
    val predictions: NonEmptyData[U, B],
    val receipt: EvaluationReceipt,
    val allObserved: AllObserved[A]
)

object Evaluation:
  def run[
      U <: Use.Evaluation,
      A,
      E,
      B,
      P <: Pipe[A, E, B]
  ](
      trained: Trained[P],
      sources: EvaluationSources[U, A]
  ): Either[EvaluationError[E], EvaluationResult[U, A, B]] =
    if !sameFingerprint(trained.audit.data, sources.fitted.fingerprint) then
      Left(
        EvaluationError.FitSourceMismatch(
          expected = sources.fitted.fingerprint,
          actual = trained.audit.data
        )
      )
    else if receiptId(trained.audit.refit) != receiptId(sources.fitted.refit)
    then
      Left(
        EvaluationError.FitAuditMismatch(
          expected = receiptId(sources.fitted.refit),
          actual = receiptId(trained.audit.refit)
        )
      )
    else
      val predictions = sources.heldOut.data.foldRows[
        Either[EvaluationError[E], Vector[(RowId, B)]]
      ](Right(Vector.empty)) {
        case (Left(error), _, _) => Left(error)
        case (Right(rows), id, value) =>
          trained.artifact
            .run(value)
            .left
            .map(EvaluationError.PredictionFailed(_))
            .map(prediction => rows :+ (id, prediction))
      }
      predictions.map { rows =>
        val authority = new ReceiptAuthority
        val receiptId = Fingerprints.evaluationReceipt(
          trained.audit,
          sources.fingerprint,
          sources.authorizingRole
        )
        val receipt = new EvaluationReceipt(
          receiptId,
          sources.sources,
          sources.authorizingRole,
          authority
        )
        val observed = new AllObserved(
          sources.observedRows,
          sources.fingerprint,
          sources.sources,
          authority
        )
        val predictionFingerprint = Fingerprints.derived(
          sources.heldOut.fingerprint,
          "evaluation/predictions",
          receiptId.render
        )
        new EvaluationResult(
          new NonEmptyData(
            new InMemoryData[U, B](rows, predictionFingerprint)
          ),
          receipt,
          observed
        )
      }

  private def sameFingerprint(
      left: DataFingerprint,
      right: DataFingerprint
  ): Boolean =
    left.policy == right.policy && left.digest == right.digest

  private def receiptId(
      refit: Option[RefitAudit]
  ): Option[EvaluationReceiptId] =
    refit.map(_.receipt)

object Refit:
  def after(receipt: EvaluationReceipt): RefitPromotion =
    new RefitPromotion(receipt)

final class RefitPromotion private[data] (
    receipt: EvaluationReceipt
):
  def from[A](
      observed: AllObserved[A]
  ): Either[RefitError, NonEmptyData[Use.Refit, A]] =
    receipt.authorize(observed).map { audit =>
      new NonEmptyData(
        new InMemoryData[Use.Refit, A](
          observed.rows,
          observed.fingerprint
        ),
        Some(audit)
      )
    }

package alder.data

import alder.kernel.*

/** An analysis/assessment partition. Both row collections remain protocol
  * resources: callers receive fingerprints and counts, while Alder's
  * cross-fitting/evaluation interpreters consume the rows.
  */
final class ResamplingFold[+U <: Use.Fit, +A] private[alder] (
    val index: Int,
    private[alder] val analysis: NonEmptyData[U, A],
    private[alder] val assessment: NonEmptyData[U, A],
    val assignment: DataFingerprint
):
  def analysisSize: Long = analysis.size
  def assessmentSize: Long = assessment.size

final class ResamplingPlan[+U <: Use.Fit, +A] private[alder] (
    private[alder] val folds: Vector[ResamplingFold[U, A]],
    val resampler: ResamplerFingerprint,
    val assignment: DataFingerprint,
    private[alder] val tessera: Option[TesseraPlanReceipt]
):
  def foldCount: Int = folds.length

/** Evaluation resampling. It does not promise that every row is assessed. */
trait Resampler[A]:
  def fingerprint: ResamplerFingerprint

  private[alder] def split[U <: Use.Fit](
      data: NonEmptyData[U, A],
      seed: Seed
  ): Either[DataError, ResamplingPlan[U, A]]

/** Cross-fitting capability: every input RowId occurs in exactly one assessment
  * partition. K-fold variants implement it; rolling-origin deliberately does
  * not (D19).
  */
trait CompleteResampler[A] extends Resampler[A]

private[data] object ResamplingPlans:
  def complete[U <: Use.Fit, A](
      data: NonEmptyData[U, A],
      seed: Seed,
      resampler: ResamplerFingerprint,
      foldCount: Int,
      assignments: Vector[(RowId, Int)]
  ): Either[DataError, ResamplingPlan[U, A]] =
    val rows = DataRows.collect(data.data)
    val assignmentById =
      assignments.iterator.map(pair => pair._1 -> pair._2).toMap
    val rowIds = rows.iterator.map(_._1).toSet
    val valid =
      rowIds.size == rows.length &&
        assignmentById.size == assignments.length &&
      assignments.length == rows.length &&
        rowIds.forall(assignmentById.contains) &&
        assignments.forall(pair => pair._2 >= 0 && pair._2 < foldCount)
    if !valid then
      Left(DataError.InvalidResamplingAssignment)
    else
      val assignmentFingerprint =
        Fingerprints.assignment(data.fingerprint, seed, assignments)
      val folds = Vector.tabulate(foldCount) { foldIndex =>
        val (assessmentRows, analysisRows) =
          rows.partition(row =>
            assignmentById.get(row._1).contains(foldIndex)
          )
        val analysisFingerprint = Fingerprints.partition(
          data.fingerprint,
          s"fold/$foldIndex/analysis",
          analysisRows
        )
        val assessmentFingerprint = Fingerprints.partition(
          data.fingerprint,
          s"fold/$foldIndex/assessment",
          assessmentRows
        )
        for
          analysis <- DataRows.nonEmpty[U, A](
            analysisRows,
            analysisFingerprint,
            data.refit
          )
          assessment <- DataRows.nonEmpty[U, A](
            assessmentRows,
            assessmentFingerprint,
            data.refit
          )
        yield new ResamplingFold(
          foldIndex,
          analysis,
          assessment,
          assignmentFingerprint
        )
      }
      folds.foldLeft(
        Right(Vector.empty): Either[
          DataError,
          Vector[ResamplingFold[U, A]]
        ]
      )((result, fold) =>
        for
          accepted <- result
          value <- fold
        yield accepted :+ value
      ).map(values =>
        new ResamplingPlan(
          values,
          resampler,
          assignmentFingerprint,
          None
        )
      )

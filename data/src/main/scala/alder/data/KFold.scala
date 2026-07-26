package alder.data

import alder.kernel.*

/** Complete K-fold resampling with deterministic optional shuffling.
  *
  * Each input row occurs in exactly one assessment partition. When shuffling
  * is enabled, assignment is a stable function of the row ID and supplied
  * seed, so input traversal order does not affect the plan.
  */
final class KFold[A] private (
    val folds: Int,
    val shuffle: Boolean,
    val fingerprint: ResamplerFingerprint
) extends CompleteResampler[A]:

  private[alder] def split[U <: Use.Fit](
      data: NonEmptyData[U, A],
      seed: Seed
  ): Either[DataError, ResamplingPlan[U, A]] =
    if data.size < folds.toLong then
      Left(DataError.TooManyFolds(folds, data.size))
    else
      val rows = DataRows.collect(data.data)
      val ordered =
        if shuffle then
          rows.sortBy(row => (Fingerprints.rank(seed, row._1), row._1.value))
        else rows
      val assignments = ordered.zipWithIndex.map { (row, index) =>
        (row._1, index % folds)
      }
      ResamplingPlans.complete(data, seed, fingerprint, folds, assignments)

object KFold:
  /** Creates a K-fold resampler.
    *
    * @return
    *   `InvalidFoldCount` when `folds` is less than two
    */
  def apply[A](
      folds: Int,
      shuffle: Boolean = true
  ): Either[DataError, KFold[A]] =
    if folds < 2 then Left(DataError.InvalidFoldCount(folds))
    else
      Right(
        new KFold(
          folds,
          shuffle,
          Fingerprints.configuration(
            "alder.kfold",
            "1",
            folds.toString,
            shuffle.toString
          )
        )
      )

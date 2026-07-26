package alder.data

import alder.kernel.*

/** A typed train/test split. Construction is controlled by the splitter; no
  * public retagging operation exists.
  */
final class Holdout[+A] private[data] (
    val train: NonEmptyData[Use.Train, A],
    val test: Data[Use.Test, A]
)

object Holdout:
  def split[A](
      data: Data[Use.Unsplit, A],
      testSize: Int,
      seed: Seed
  ): Either[DataError, Holdout[A]] =
    val rows = DataRows.collect(data)
    if testSize <= 0 || testSize >= rows.length then
      Left(DataError.InvalidHoldoutSize(testSize, rows.length.toLong))
    else
      val selected = rows
        .sortBy(row => (Fingerprints.rank(seed, row._1), row._1.value))
        .take(testSize)
        .iterator
        .map(_._1)
        .toSet
      val (testRows, trainRows) =
        rows.partition(row => selected.contains(row._1))
      val trainFingerprint =
        Fingerprints.partition(data.fingerprint, "holdout/train", trainRows)
      val testFingerprint =
        Fingerprints.partition(data.fingerprint, "holdout/test", testRows)
      DataRows
        .nonEmpty[Use.Train, A](trainRows, trainFingerprint)
        .map { train =>
          val test = new InMemoryData[Use.Test, A](testRows, testFingerprint)
          new Holdout(train, test)
        }

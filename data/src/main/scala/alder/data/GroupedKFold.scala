package alder.data

import alder.kernel.*
import cats.kernel.Hash

object GroupedKFold:
  def apply[X, Y, M](
      folds: Int
  )(using
      groupOf: GroupOf[M],
      keyHash: Hash[groupOf.Key]
  ): Either[DataError, CompleteResampler[Example[X, Y, M]]] =
    if folds < 2 then Left(DataError.InvalidFoldCount(folds))
    else
      val resamplerFingerprint = Fingerprints.configuration(
        "alder.grouped-kfold",
        "1",
        folds.toString
      )
      Right(
        new CompleteResampler[Example[X, Y, M]]:
          val fingerprint: ResamplerFingerprint = resamplerFingerprint

          private[alder] def split[U <: Use.Fit](
              data: NonEmptyData[U, Example[X, Y, M]],
              seed: Seed
          ): Either[
            DataError,
            ResamplingPlan[U, Example[X, Y, M]]
          ] =
            val rows = DataRows.collect(data.data)
            val groups = rows.zipWithIndex.foldLeft(
              Vector.empty[
                (
                    groupOf.Key,
                    Int,
                    RowId,
                    Vector[(RowId, Example[X, Y, M])]
                )
              ]
            ) { (buckets, indexedRow) =>
              val (row, position) = indexedRow
              val key = groupOf(row._2.meta)
              val found = buckets.indexWhere(bucket =>
                keyHash.eqv(bucket._1, key)
              )
              if found < 0 then
                buckets :+ (key, position, row._1, Vector(row))
              else
                buckets.zipWithIndex.map { (bucket, index) =>
                  if index == found then
                    (
                      bucket._1,
                      bucket._2,
                      bucket._3,
                      bucket._4 :+ row
                    )
                  else bucket
                }
            }
            if groups.length < folds then
              Left(DataError.TooFewGroups(folds, groups.length))
            else
              val orderedGroups = groups.sortBy(group =>
                (
                  -group._4.length,
                  Fingerprints.rank(seed, group._3),
                  group._2
                )
              )
              val initial =
                Vector.fill(folds)((0, Vector.empty[(RowId, Example[X, Y, M])]))
              val assigned = orderedGroups.foldLeft(initial) {
                (foldRows, group) =>
                  val foldIndex = foldRows.zipWithIndex.minBy { (entry, index) =>
                    (entry._1, index)
                  }._2
                  foldRows.zipWithIndex.map { (current, index) =>
                    if index == foldIndex then
                      (
                        current._1 + group._4.length,
                        current._2 ++ group._4
                      )
                    else current
                  }
              }
              val assignments = assigned.zipWithIndex.flatMap {
                (entry, foldIndex) =>
                  entry._2.map(row => (row._1, foldIndex))
              }
              ResamplingPlans.complete(
                data,
                seed,
                fingerprint,
                folds,
                assignments
              )
      )

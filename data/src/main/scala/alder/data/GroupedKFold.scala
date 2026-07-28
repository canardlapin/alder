package alder.data

import alder.kernel.*
import cats.kernel.Hash
import scala.collection.mutable

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
            val groups = accumulateGroups(rows)
            if groups.length < folds then
              Left(DataError.TooFewGroups(folds, groups.length))
            else
              val orderedGroups = groups.sortBy(group =>
                (
                  -group.rows.length,
                  Fingerprints.rank(seed, group.firstId),
                  group.firstPosition
                )
              )
              val assignments = assignFolds(orderedGroups, folds)
              ResamplingPlans.complete(
                data,
                seed,
                fingerprint,
                folds,
                assignments
              )
      )

  private final class GroupBucket[X, Y, M, K](
      val key: K,
      val firstPosition: Int,
      val firstId: RowId,
      val rows: Vector[(RowId, Example[X, Y, M])]
  )

  private def accumulateGroups[X, Y, M](
      rows: Vector[(RowId, Example[X, Y, M])]
  )(using
      groupOf: GroupOf[M],
      keyHash: Hash[groupOf.Key]
  ): Vector[GroupBucket[X, Y, M, groupOf.Key]] =
    type Key = groupOf.Key
    val groups = mutable.ArrayBuffer.empty[GroupBucket[X, Y, M, Key]]
    val builders =
      mutable.ArrayBuffer.empty[
        mutable.Builder[
          (RowId, Example[X, Y, M]),
          Vector[(RowId, Example[X, Y, M])]
        ]
      ]
    val byHash = mutable.HashMap.empty[Int, mutable.ArrayBuffer[Int]]
    var position = 0
    while position < rows.length do
      val row = rows(position)
      val key: Key = groupOf(row._2.meta)
      val hash = keyHash.hash(key)
      val candidates =
        byHash.getOrElseUpdate(hash, mutable.ArrayBuffer.empty[Int])
      var found = -1
      var candidateIndex = 0
      while candidateIndex < candidates.length && found < 0 do
        val groupIndex = candidates(candidateIndex)
        if keyHash.eqv(groups(groupIndex).key, key) then found = groupIndex
        candidateIndex += 1
      if found < 0 then
        val builder = Vector.newBuilder[(RowId, Example[X, Y, M])]
        builder += row
        builders += builder
        groups += new GroupBucket(key, position, row._1, Vector.empty)
        candidates += (groups.length - 1)
      else builders(found) += row
      position += 1
    Vector.tabulate(groups.length) { index =>
      val group = groups(index)
      new GroupBucket(
        group.key,
        group.firstPosition,
        group.firstId,
        builders(index).result()
      )
    }

  private def assignFolds[X, Y, M, K](
      orderedGroups: Vector[GroupBucket[X, Y, M, K]],
      folds: Int
  ): Vector[(RowId, Int)] =
    val foldSizes = Array.fill(folds)(0)
    val foldBuilders =
      Array.fill(folds)(
        Vector.newBuilder[(RowId, Example[X, Y, M])]
      )
    var groupIndex = 0
    while groupIndex < orderedGroups.length do
      val group = orderedGroups(groupIndex)
      var bestFold = 0
      var bestSize = foldSizes(0)
      var foldIndex = 1
      while foldIndex < folds do
        val size = foldSizes(foldIndex)
        if size < bestSize then
          bestFold = foldIndex
          bestSize = size
        foldIndex += 1
      foldSizes(bestFold) += group.rows.length
      foldBuilders(bestFold) ++= group.rows
      groupIndex += 1
    val assignments = Vector.newBuilder[(RowId, Int)]
    var fold = 0
    while fold < folds do
      val foldRows = foldBuilders(fold).result()
      var rowIndex = 0
      while rowIndex < foldRows.length do
        assignments += ((foldRows(rowIndex)._1, fold))
        rowIndex += 1
      fold += 1
    assignments.result()

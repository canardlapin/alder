package alder.data

import alder.kernel.*
import cats.kernel.Order

object RollingOrigin:
  def apply[X, Y, M](
      initialSize: Int,
      assessmentSize: Int,
      stepSize: Int
  )(using
      timeOf: TimeOf[M],
      timeOrder: Order[timeOf.Instant]
  ): Either[DataError, Resampler[Example[X, Y, M]]] =
    if initialSize <= 0 || assessmentSize <= 0 || stepSize <= 0 then
      Left(
        DataError.InvalidRollingWindow(
          initialSize,
          assessmentSize,
          stepSize
        )
      )
    else
      val resamplerFingerprint = Fingerprints.configuration(
        "alder.rolling-origin",
        "1",
        initialSize.toString,
        assessmentSize.toString,
        stepSize.toString
      )
      Right(
        new Resampler[Example[X, Y, M]]:
          val fingerprint: ResamplerFingerprint = resamplerFingerprint

          private[alder] def split[U <: Use.Fit](
              data: NonEmptyData[U, Example[X, Y, M]],
              seed: Seed
          ): Either[
            DataError,
            ResamplingPlan[U, Example[X, Y, M]]
          ] =
            val rows = DataRows.collect(data.data).zipWithIndex
            val ordered = rows
              .sortWith { (left, right) =>
                val compared = timeOrder.compare(
                  timeOf(left._1._2.meta),
                  timeOf(right._1._2.meta)
                )
                if compared < 0 then true
                else if compared > 0 then false
                else left._2 < right._2
              }
              .map(_._1)
            if ordered.length <= initialSize then
              Left(DataError.NoRollingFolds(ordered.length.toLong, initialSize))
            else
              val starts =
                Iterator
                  .iterate(initialSize)(_ + stepSize)
                  .takeWhile(_ < ordered.length)
                  .toVector
              val foldResults = starts.zipWithIndex.map { (start, foldIndex) =>
                val analysisRows = ordered.take(start)
                val assessmentRows =
                  ordered.slice(start, math.min(start + assessmentSize, ordered.length))
                val foldAssignment = Fingerprints.assignment(
                  data.fingerprint,
                  seed,
                  assessmentRows.map(row => (row._1, foldIndex))
                )
                for
                  analysis <- DataRows.nonEmpty[U, Example[X, Y, M]](
                    analysisRows,
                    Fingerprints.partition(
                      data.fingerprint,
                      s"rolling/$foldIndex/analysis",
                      analysisRows
                    )
                  )
                  assessment <- DataRows.nonEmpty[U, Example[X, Y, M]](
                    assessmentRows,
                    Fingerprints.partition(
                      data.fingerprint,
                      s"rolling/$foldIndex/assessment",
                      assessmentRows
                    )
                  )
                yield new ResamplingFold(
                  foldIndex,
                  analysis,
                  assessment,
                  foldAssignment
                )
              }
              val combined = foldResults.foldLeft(
                Right(Vector.empty): Either[
                  DataError,
                  Vector[ResamplingFold[U, Example[X, Y, M]]]
                ]
              )((result, fold) =>
                for
                  accepted <- result
                  value <- fold
                yield accepted :+ value
              )
              combined.map { folds =>
                val assignments = folds.flatMap(fold =>
                  DataRows
                    .collect(fold.assessment.data)
                    .map(row => (row._1, fold.index))
                )
                new ResamplingPlan(
                  folds,
                  fingerprint,
                  Fingerprints.assignment(
                    data.fingerprint,
                    seed,
                    assignments
                  )
                )
              }
      )

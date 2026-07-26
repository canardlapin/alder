package alder.kernel

/** A collection of observations whose data-use role `U` is tracked in the type.
  * `foldRows` is the semantic access path; `foreachRow` / `foreachBatch` are
  * the non-allocating hot paths (D12). Implementations should override the hot
  * paths when they can do better than the defaults.
  */
trait Data[+U <: Use, +A]:
  def size: Long
  def fingerprint: DataFingerprint

  def foldRows[B](initial: B)(step: (B, RowId, A) => B): B

  def foreachRow(step: (RowId, A) => Unit): Unit =
    val _ = foldRows(())((_, id, value) => step(id, value))

  def foreachBatch(size: BatchSize)(step: RowBatch[A] => Unit): Unit =
    val buffer = scala.collection.mutable.ArrayBuffer.empty[(RowId, A)]
    foreachRow { (id, value) =>
      buffer += ((id, value))
      if buffer.length >= size.value then
        step(RowBatch.fromRows(buffer.toVector))
        buffer.clear()
    }
    if buffer.nonEmpty then step(RowBatch.fromRows(buffer.toVector))

/** A chunk of rows for batched access without per-row allocation. */
trait RowBatch[+A]:
  def length: Int
  def rowId(index: Int): RowId
  def value(index: Int): A

object RowBatch:
  private[alder] def fromRows[A](rows: Vector[(RowId, A)]): RowBatch[A] =
    new RowBatch[A]:
      def length: Int = rows.length
      def rowId(index: Int): RowId = rows(index)._1
      def value(index: Int): A = rows(index)._2

/** Proof of nonemptiness, required by every fitting signature. Constructed only
  * by Alder's splitting and preparation protocols.
  */
final class NonEmptyData[+U <: Use, +A] private[alder] (
    val data: Data[U, A],
    private[alder] val refit: Option[RefitAudit] = None
):
  def size: Long = data.size
  def fingerprint: DataFingerprint = data.fingerprint

/** Internal in-memory rows used by preparation factories (replay, out-of-fold
  * reassembly). Public Data implementations live in alder-data.
  */
private[alder] final class RowVectorData[U <: Use, A](
    rows: Vector[(RowId, A)],
    val fingerprint: DataFingerprint
) extends Data[U, A]:
  def size: Long = rows.length.toLong
  def foldRows[B](initial: B)(step: (B, RowId, A) => B): B =
    rows.foldLeft(initial)((acc, row) => step(acc, row._1, row._2))

enum PreparationError derives CanEqual:
  case DuplicateInputRow(id: RowId)
  case DuplicatePreparedRow(id: RowId)
  case UnknownPreparedRow(id: RowId)
  case MissingPreparedRows(count: Int)

private[alder] final class MappedData[U <: Use, A, B](
    source: Data[U, A],
    f: A => B
) extends Data[U, B]:
  def size: Long = source.size
  def fingerprint: DataFingerprint = source.fingerprint
  def foldRows[C](initial: C)(step: (C, RowId, B) => C): C =
    source.foldRows(initial)((acc, id, value) => step(acc, id, f(value)))

private[alder] object DataOperations:
  def mapNonEmpty[U <: Use, A, B](
      data: NonEmptyData[U, A]
  )(f: A => B): NonEmptyData[U, B] =
    new NonEmptyData(new MappedData(data.data, f), data.refit)

  def traverseNonEmpty[U <: Use, E, A, B](
      data: NonEmptyData[U, A]
  )(
      f: (RowId, A) => Either[Failure[E], B]
  ): Either[Failure[E], NonEmptyData[U, B]] =
    data.data
      .foldRows[Either[Failure[E], Vector[(RowId, B)]]](Right(Vector.empty)) {
        case (Left(failure), _, _) => Left(failure)
        case (Right(rows), id, value) =>
          f(id, value).map(result => rows :+ (id, result))
      }
      .map(rows =>
        new NonEmptyData(RowVectorData(rows, data.fingerprint), data.refit)
      )

  def restoreExamples[U <: Use.Fit, X, Y, M, Z](
      original: NonEmptyData[U, Example[X, Y, M]],
      prepared: NonEmptyData[U, Z],
      stage: StagePath
  ): Either[
    Failure[PreparationError],
    NonEmptyData[U, Example[Z, Y, M]]
  ] =
    val originals = original.data.foldRows[
      Either[PreparationError, Map[RowId, (Y, M)]]
    ](Right(Map.empty)) {
      case (Left(error), _, _) => Left(error)
      case (Right(rows), id, example) =>
        if rows.contains(id) then
          Left(PreparationError.DuplicateInputRow(id))
        else Right(rows.updated(id, (example.target, example.meta)))
    }
    val restored = originals.flatMap { available =>
      prepared.data.foldRows[
        Either[
          PreparationError,
          (Map[RowId, (Y, M)], Set[RowId], Vector[(RowId, Example[Z, Y, M])])
        ]
      ](Right((available, Set.empty, Vector.empty))) {
        case (Left(error), _, _) => Left(error)
        case (Right((remaining, seen, rows)), id, value) =>
          if seen.contains(id) then
            Left(PreparationError.DuplicatePreparedRow(id))
          else
            remaining.get(id) match
              case None => Left(PreparationError.UnknownPreparedRow(id))
              case Some((target, meta)) =>
                Right(
                  (
                    remaining.removed(id),
                    seen + id,
                    rows :+ (id, Example(value, target, meta))
                  )
                )
      }.flatMap { (remaining, _, rows) =>
        if remaining.isEmpty then Right(rows)
        else Left(PreparationError.MissingPreparedRows(remaining.size))
      }
    }
    restored
      .left
      .map(stage.failure)
      .map(rows =>
        new NonEmptyData(
          RowVectorData(rows, original.fingerprint),
          original.refit
        )
      )

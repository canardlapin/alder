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
final class NonEmptyData[+U <: Use, +A] private[alder] (val data: Data[U, A]):
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

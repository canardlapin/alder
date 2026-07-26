package alder.data

import alder.kernel.*

/** An immutable, ownership-safe in-memory Data implementation. The input
  * Vector is already persistent; rows and their stable ids are never exposed
  * as a mutable collection.
  */
final class InMemoryData[+U <: Use, +A] private[data] (
    private val rows: Vector[(RowId, A)],
    val fingerprint: DataFingerprint
) extends Data[U, A]:
  def size: Long = rows.length.toLong

  def foldRows[B](initial: B)(step: (B, RowId, A) => B): B =
    rows.foldLeft(initial)((acc, row) => step(acc, row._1, row._2))

  override def foreachRow(step: (RowId, A) => Unit): Unit =
    rows.foreach(row => step(row._1, row._2))

  override def foreachBatch(
      size: BatchSize
  )(step: RowBatch[A] => Unit): Unit =
    var offset = 0
    while offset < rows.length do
      val batchOffset = offset
      val batchLength = math.min(size.value, rows.length - batchOffset)
      step(
        new RowBatch[A]:
          def length: Int = batchLength
          def rowId(index: Int): RowId = rows(batchOffset + index)._1
          def value(index: Int): A = rows(batchOffset + index)._2
      )
      offset += batchLength

object InMemoryData:
  def unsplit[A](
      values: Vector[A],
      fingerprint: DataFingerprint
  ): Data[Use.Unsplit, A] =
    val rows = values.zipWithIndex.map { (value, index) =>
      (RowId(index.toLong), value)
    }
    new InMemoryData[Use.Unsplit, A](rows, fingerprint)

private[data] object DataRows:
  def collect[U <: Use, A](data: Data[U, A]): Vector[(RowId, A)] =
    data.foldRows(Vector.empty[(RowId, A)])((rows, id, value) =>
      rows :+ (id, value)
    )

  def nonEmpty[U <: Use, A](
      rows: Vector[(RowId, A)],
      fingerprint: DataFingerprint
  ): Either[DataError, NonEmptyData[U, A]] =
    if rows.isEmpty then Left(DataError.EmptyData)
    else Right(new NonEmptyData(new InMemoryData[U, A](rows, fingerprint)))

package alder.kernel

/** Stable identity of a logical row. Supports split-disjointness checks,
  * out-of-fold reconstruction in original order, leakage tracing in law tests,
  * and auditable fold lineage.
  */
opaque type RowId = Long

object RowId:
  private[alder] def apply(value: Long): RowId = value

  given CanEqual[RowId, RowId] = CanEqual.derived

  extension (id: RowId) def value: Long = id

/** Positive batch size for [[Data.foreachBatch]]. */
opaque type BatchSize = Int

object BatchSize:
  def apply(value: Int): Either[String, BatchSize] =
    if value > 0 then Right(value)
    else Left(s"BatchSize must be positive, got $value")

  inline def const(inline value: Int): BatchSize =
    inline if value > 0 then value
    else compiletime.error("BatchSize must be a positive literal")

  extension (size: BatchSize) def value: Int = size

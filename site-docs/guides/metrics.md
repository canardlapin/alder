# Compute and merge metrics

A `Metric[A, S]` exposes a commutative accumulator. You can evaluate one
collection directly or combine independently accumulated partitions.

## Root mean squared error

```scala mdoc
import alder.kernel.Scored
import alder.metrics.*

val first = Vector(
  Scored(1.0, 1.5, ()),
  Scored(2.0, 2.0, ())
)
val second = Vector(
  Scored(3.0, 2.5, ()),
  Scored(4.0, 4.0, ())
)

val rmse = RegressionMetrics.rmse[Unit]
val left = rmse.accumulate(first)
val right = rmse.accumulate(second)
val combined = rmse.accumulator.combine(left, right)

rmse.finish(combined).map(_.value)
```

The numerical accumulator represents finite binary64 sums exactly before the
final rounding step. Partitioning and merge order therefore do not change the
result.

## Weighted metrics

Weight access is typeclass evidence on metadata. There is no optional
`weighted` flag.

```scala mdoc
import alder.kernel.WeightOf

final case class ObservationMeta(weight: Double)

given WeightOf[ObservationMeta] with
  def apply(meta: ObservationMeta): Double = meta.weight

val weighted = Vector(
  Scored(1.0, 2.0, ObservationMeta(1.0)),
  Scored(4.0, 2.0, ObservationMeta(3.0))
)

RegressionMetrics
  .weightedRmse[ObservationMeta]
  .evaluate(weighted)
  .map(_.value)
```

Invalid truths, predictions, weights, residuals, and final results are distinct
`MetricError` cases. Empty input and zero total weight are also explicit
failures.

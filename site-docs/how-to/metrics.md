# Compute and merge metrics correctly

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
`weighted` flag. The explicit policy identity distinguishes different
metadata-to-weight interpretations in evaluation and selection receipts.

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
  .weightedRmse[ObservationMeta](
    WeightPolicyId("observation-meta.weight/v1")
  )
  .evaluate(weighted)
  .map(_.value)
```

Invalid truths, predictions, weights, residuals, and final results are distinct
`MetricError` cases. Empty input and zero total weight are also explicit
failures.

RMSE and accuracy are `ObjectiveMetric` values. Each owns its optimization
direction and canonical score encoding; callers do not supply separate
selection evidence that could disagree with the metric descriptor.

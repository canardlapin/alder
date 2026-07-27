package alder.metrics

import alder.kernel.{Scored, WeightOf}
import cats.kernel.Eq
import scala.compiletime.testing.typeCheckErrors

final case class WeightedMeta(weight: Double)

given WeightOf[WeightedMeta] with
  def apply(meta: WeightedMeta): Double = meta.weight

class MetricSuite extends munit.FunSuite:
  test("exact superaccumulator is cancellation and tie-order invariant") {
    val values =
      Vector(
        Double.MaxValue,
        -Double.MaxValue,
        1.0,
        math.scalb(1.0, -53)
      )
    val forward =
      values.foldLeft(ReproducibleSum.empty)(_.add(_)).result
    val reverse =
      values.reverse.foldLeft(ReproducibleSum.empty)(_.add(_)).result
    assertEquals(
      java.lang.Double.doubleToRawLongBits(forward),
      java.lang.Double.doubleToRawLongBits(reverse)
    )
    assertEquals(forward, 1.0)

    val subnormal =
      ReproducibleSum.empty
        .add(Double.MinPositiveValue)
        .add(Double.MinPositiveValue)
        .result
    assertEquals(
      java.lang.Double.doubleToRawLongBits(subnormal),
      2L
    )
  }

  test("RMSE is exactly invariant to permutation and partition merging") {
    val scored = Vector(
      Scored(1.0e150, 0.0, ()),
      Scored(3.0, 1.0, ()),
      Scored(-1.0e150, 0.0, ()),
      Scored(5.0, 4.0, ())
    )
    val metric = RegressionMetrics.rmse[Unit]
    val forward = metric.evaluate(scored)
    val reverse = metric.evaluate(scored.reverse)
    assertEquals(forward, reverse)

    val left = metric.accumulate(scored.take(2))
    val right = metric.accumulate(scored.drop(2))
    val merged = metric.accumulator.combine(left, right)
    assertEquals(metric.finish(merged), forward)
  }

  test("RMSE rejects nonfinite inputs deterministically") {
    val metric = RegressionMetrics.rmse[Unit]
    val values = Vector(
      Scored(Double.NaN, 1.0, ()),
      Scored(1.0, Double.PositiveInfinity, ())
    )
    metric.evaluate(values.reverse) match
      case Left(MetricError.NonFiniteTruth(value)) =>
        assert(value.isNaN)
      case other => fail(s"expected canonical truth error, got $other")
    assertEquals(metric.evaluate(Vector.empty), Left(MetricError.Empty))
  }

  test("weighted RMSE uses explicit WeightOf evidence and validates weights") {
    val metric =
      RegressionMetrics.weightedRmse[WeightedMeta](
        WeightPolicyId("weighted-meta.weight")
      )
    val values = Vector(
      Scored(2.0, 0.0, WeightedMeta(1.0)),
      Scored(4.0, 0.0, WeightedMeta(3.0))
    )
    metric.evaluate(values) match
      case Right(value) =>
        assertEqualsDouble(value.value, math.sqrt(13.0), 0.0)
      case Left(error) => fail(s"unexpected weighted error: $error")

    assertEquals(
      metric.evaluate(
        Vector(Scored(1.0, 1.0, WeightedMeta(-1.0)))
      ),
      Left(MetricError.NegativeWeight(-1.0))
    )
    assertEquals(
      metric.evaluate(
        Vector(Scored(1.0, 1.0, WeightedMeta(0.0)))
      ),
      Left(MetricError.ZeroTotalWeight)
    )
  }

  test("accuracy has an honest hard-label input type") {
    given Eq[String] = Eq.fromUniversalEquals
    val metric =
      ClassificationMetrics.accuracy[String, Unit](
        EqualityPolicyId("cats.eq.string")
      )
    assertEquals(
      metric.evaluate(
        Vector(
          Scored("cat", "cat", ()),
          Scored("cat", "dog", ()),
          Scored("dog", "dog", ())
        )
      ),
      Right(Accuracy(2.0 / 3.0))
    )
    val typeErrors = typeCheckErrors(
      """import alder.kernel.*
import alder.metrics.*
final case class NoWeight(value: Double)
val illegal = RegressionMetrics.weightedRmse[NoWeight](
  WeightPolicyId("no-weight.value")
)
"""
    )
    assert(typeErrors.nonEmpty)
  }

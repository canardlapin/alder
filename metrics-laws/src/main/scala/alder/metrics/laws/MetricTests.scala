package alder.metrics.laws

import alder.metrics.{Metric, MetricError}
import cats.kernel.Eq
import cats.syntax.eq.*
import org.scalacheck.{Arbitrary, Gen, Prop}
import org.typelevel.discipline.Laws

/** Published Metric Discipline laws for exact partition and permutation
  * invariance.
  */
final class MetricTests[A, S](metric: Metric[A, S]) extends Laws:
  def all(using
      arbitrary: Arbitrary[A],
      resultEq: Eq[Either[MetricError, S]]
  ): RuleSet =
    new DefaultRuleSet(
      "metric",
      None,
      "partition law" -> Prop.forAll { (values: Vector[A]) =>
        val split = values.length / 2
        val left = metric.accumulate(values.take(split))
        val right = metric.accumulate(values.drop(split))
        val merged = metric.accumulator.combine(left, right)
        metric.finish(merged) === metric.evaluate(values)
      },
      "accumulator identity" -> Prop.forAll { (values: Vector[A]) =>
        val accumulated = metric.accumulate(values)
        val left =
          metric.accumulator.combine(
            metric.accumulator.empty,
            accumulated
          )
        val right =
          metric.accumulator.combine(
            accumulated,
            metric.accumulator.empty
          )
        metric.finish(left) === metric.finish(right) &&
        metric.finish(left) === metric.finish(accumulated)
      },
      "accumulator associativity" -> Prop.forAll { (values: Vector[A]) =>
        val firstSplit = values.length / 3
        val secondSplit = (values.length * 2) / 3
        val first = metric.accumulate(values.take(firstSplit))
        val second =
          metric.accumulate(
            values.slice(firstSplit, secondSplit)
          )
        val third = metric.accumulate(values.drop(secondSplit))
        val left =
          metric.accumulator.combine(
            metric.accumulator.combine(first, second),
            third
          )
        val right =
          metric.accumulator.combine(
            first,
            metric.accumulator.combine(second, third)
          )
        metric.finish(left) === metric.finish(right)
      },
      "accumulator commutativity" -> Prop.forAll { (values: Vector[A]) =>
        val split = values.length / 2
        val left = metric.accumulate(values.take(split))
        val right = metric.accumulate(values.drop(split))
        val leftFirst = metric.accumulator.combine(left, right)
        val rightFirst = metric.accumulator.combine(right, left)
        metric.finish(leftFirst) === metric.finish(rightFirst)
      },
      "permutation law" -> Prop.forAll(permutedPairs) {
        (pair: (Vector[A], Vector[A])) =>
          metric.evaluate(pair._1) === metric.evaluate(pair._2)
      }
    )

  private def permutedPairs(using
      arbitrary: Arbitrary[A]
  ): Gen[(Vector[A], Vector[A])] =
    Arbitrary
      .arbitrary[Vector[A]]
      .flatMap(values =>
        Gen.pick(values.length, values).map(permuted =>
          values -> permuted.toVector
        )
      )

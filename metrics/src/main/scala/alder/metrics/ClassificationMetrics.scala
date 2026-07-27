package alder.metrics

import alder.kernel.{AuditValue, Scored, WeightOf}
import cats.kernel.{CommutativeMonoid, Eq}
import cats.syntax.eq.*

final class AccuracyAccumulator private[metrics] (
    private[metrics] val correct: BigInt,
    private[metrics] val count: BigInt
)

object AccuracyAccumulator:
  given monoid: CommutativeMonoid[AccuracyAccumulator] with
    def empty: AccuracyAccumulator =
      new AccuracyAccumulator(BigInt(0), BigInt(0))

    def combine(
        left: AccuracyAccumulator,
        right: AccuracyAccumulator
    ): AccuracyAccumulator =
      new AccuracyAccumulator(
        left.correct + right.correct,
        left.count + right.count
      )

final class WeightedAccuracyAccumulator private[metrics] (
    private[metrics] val correctWeight: ReproducibleSum,
    private[metrics] val totalWeight: ReproducibleSum,
    private[metrics] val count: BigInt,
    private[metrics] val problems: Set[MetricProblem]
)

object WeightedAccuracyAccumulator:
  given monoid: CommutativeMonoid[WeightedAccuracyAccumulator] with
    def empty: WeightedAccuracyAccumulator =
      new WeightedAccuracyAccumulator(
        ReproducibleSum.empty,
        ReproducibleSum.empty,
        BigInt(0),
        MetricProblems.empty
      )

    def combine(
        left: WeightedAccuracyAccumulator,
        right: WeightedAccuracyAccumulator
    ): WeightedAccuracyAccumulator =
      val sum = summon[CommutativeMonoid[ReproducibleSum]]
      new WeightedAccuracyAccumulator(
        sum.combine(left.correctWeight, right.correctWeight),
        sum.combine(left.totalWeight, right.totalWeight),
        left.count + right.count,
        left.problems union right.problems
      )

object ClassificationMetrics:
  /** Exact classification accuracy using the supplied Cats equality. */
  def accuracy[C: Eq, M](
      equalityPolicy: EqualityPolicyId
  ): ObjectiveMetric[Scored[C, C, M], Accuracy] =
    new ObjectiveMetric[Scored[C, C, M], Accuracy]:
      type Acc = AccuracyAccumulator
      given accumulator: CommutativeMonoid[AccuracyAccumulator] =
        AccuracyAccumulator.monoid

      val direction: ObjectiveDirection = ObjectiveDirection.Maximize

      val descriptor: MetricDescriptor =
        MetricDescriptor(
          MetricId("classification-accuracy"),
          MetricVersion("1"),
          AuditValue.record(
            "equality-policy" -> AuditValue.text(equalityPolicy.value)
          ),
          MetricNumericPolicy.Reproducible,
          Some(ObjectiveDescriptor(direction, "binary64-decimal-v1"))
        )

      def auditScore(score: Accuracy): AuditValue =
        AuditValue.decimal(score.value)

      def observe(scored: Scored[C, C, M]): AccuracyAccumulator =
        new AccuracyAccumulator(
          if scored.truth === scored.prediction then BigInt(1)
          else BigInt(0),
          BigInt(1)
        )

      def finish(
          accumulated: AccuracyAccumulator
      ): Either[MetricError, Accuracy] =
        if accumulated.count.signum == 0 then Left(MetricError.Empty)
        else
          val result =
            accumulated.correct.toDouble / accumulated.count.toDouble
          if result.isFinite then Right(Accuracy(result))
          else Left(MetricError.NonFiniteResult)

  /** Classification accuracy weighted from observation metadata.
    *
    * Weights must be finite and non-negative, and their total must be
    * positive. Summation is reproducible across partition shapes.
    */
  def weightedAccuracy[C: Eq, M](
      equalityPolicy: EqualityPolicyId,
      weightPolicy: WeightPolicyId
  )(using
      weightOf: WeightOf[M]
  ): ObjectiveMetric[Scored[C, C, M], Accuracy] =
    new ObjectiveMetric[Scored[C, C, M], Accuracy]:
      type Acc = WeightedAccuracyAccumulator
      given accumulator: CommutativeMonoid[WeightedAccuracyAccumulator] =
        WeightedAccuracyAccumulator.monoid

      val direction: ObjectiveDirection = ObjectiveDirection.Maximize

      val descriptor: MetricDescriptor =
        MetricDescriptor(
          MetricId("weighted-classification-accuracy"),
          MetricVersion("1"),
          AuditValue.record(
            "equality-policy" -> AuditValue.text(equalityPolicy.value),
            "weight-policy" -> AuditValue.text(weightPolicy.value)
          ),
          MetricNumericPolicy.Reproducible,
          Some(ObjectiveDescriptor(direction, "binary64-decimal-v1"))
        )

      def auditScore(score: Accuracy): AuditValue =
        AuditValue.decimal(score.value)

      def observe(
          scored: Scored[C, C, M]
      ): WeightedAccuracyAccumulator =
        val weight = weightOf(scored.meta)
        val problems =
          if !weight.isFinite then Set(MetricProblem.weight(weight))
          else if weight < 0.0 then
            Set(MetricProblem.negativeWeight(weight))
          else MetricProblems.empty
        if problems.nonEmpty then
          new WeightedAccuracyAccumulator(
            ReproducibleSum.empty,
            ReproducibleSum.empty,
            BigInt(0),
            problems
          )
        else
          new WeightedAccuracyAccumulator(
            if scored.truth === scored.prediction then
              ReproducibleSum.empty.add(weight)
            else ReproducibleSum.empty,
            ReproducibleSum.empty.add(weight),
            BigInt(1),
            MetricProblems.empty
          )

      def finish(
          accumulated: WeightedAccuracyAccumulator
      ): Either[MetricError, Accuracy] =
        MetricProblems.first(accumulated.problems) match
          case Some(error) => Left(error)
          case None =>
            if accumulated.count.signum == 0 then Left(MetricError.Empty)
            else
              val totalWeight = accumulated.totalWeight.result
              if totalWeight == 0.0 then
                Left(MetricError.ZeroTotalWeight)
              else
                val result =
                  accumulated.correctWeight.result / totalWeight
                if result.isFinite then Right(Accuracy(result))
                else Left(MetricError.NonFiniteResult)

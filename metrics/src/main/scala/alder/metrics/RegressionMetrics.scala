package alder.metrics

import alder.kernel.{AuditValue, Scored, WeightOf}
import cats.kernel.CommutativeMonoid

final class RmseAccumulator private[metrics] (
    private[metrics] val squaredError: ReproducibleSum,
    private[metrics] val count: BigInt,
    private[metrics] val problems: Set[MetricProblem]
)

object RmseAccumulator:
  private[metrics] val empty =
    new RmseAccumulator(
      ReproducibleSum.empty,
      BigInt(0),
      MetricProblems.empty
    )

  given monoid: CommutativeMonoid[RmseAccumulator] with
    def empty: RmseAccumulator = RmseAccumulator.empty

    def combine(
        left: RmseAccumulator,
        right: RmseAccumulator
    ): RmseAccumulator =
      new RmseAccumulator(
        summon[CommutativeMonoid[ReproducibleSum]]
          .combine(left.squaredError, right.squaredError),
        left.count + right.count,
        left.problems union right.problems
      )

final class WeightedRmseAccumulator private[metrics] (
    private[metrics] val weightedSquaredError: ReproducibleSum,
    private[metrics] val totalWeight: ReproducibleSum,
    private[metrics] val count: BigInt,
    private[metrics] val problems: Set[MetricProblem]
)

object WeightedRmseAccumulator:
  private[metrics] val empty =
    new WeightedRmseAccumulator(
      ReproducibleSum.empty,
      ReproducibleSum.empty,
      BigInt(0),
      MetricProblems.empty
    )

  given monoid: CommutativeMonoid[WeightedRmseAccumulator] with
    def empty: WeightedRmseAccumulator = WeightedRmseAccumulator.empty

    def combine(
        left: WeightedRmseAccumulator,
        right: WeightedRmseAccumulator
    ): WeightedRmseAccumulator =
      val sum = summon[CommutativeMonoid[ReproducibleSum]]
      new WeightedRmseAccumulator(
        sum.combine(
          left.weightedSquaredError,
          right.weightedSquaredError
        ),
        sum.combine(left.totalWeight, right.totalWeight),
        left.count + right.count,
        left.problems union right.problems
      )

object RegressionMetrics:
  /** Reproducible, unweighted root mean squared error.
    *
    * The accumulator is commutative and uses an exact superaccumulator, so
    * repartitioning or changing the reduction tree does not change the result.
    */
  def rmse[M]: ObjectiveMetric[
    Scored[Double, Double, M],
    RootMeanSquaredError
  ] =
    new ObjectiveMetric[
      Scored[Double, Double, M],
      RootMeanSquaredError
    ]:
      type Acc = RmseAccumulator
      given accumulator: CommutativeMonoid[RmseAccumulator] =
        RmseAccumulator.monoid

      val direction: ObjectiveDirection = ObjectiveDirection.Minimize

      val descriptor: MetricDescriptor =
        MetricDescriptor(
          MetricId("root-mean-squared-error"),
          MetricVersion("1"),
          AuditValue.record(),
          MetricNumericPolicy.Reproducible,
          Some(ObjectiveDescriptor(direction, "binary64-decimal-v1"))
        )

      def auditScore(score: RootMeanSquaredError): AuditValue =
        AuditValue.decimal(score.value)

      def observe(
          scored: Scored[Double, Double, M]
      ): RmseAccumulator =
        unweightedObservation(scored.truth, scored.prediction)

      def finish(
          accumulated: RmseAccumulator
      ): Either[MetricError, RootMeanSquaredError] =
        MetricProblems.first(accumulated.problems) match
          case Some(error) => Left(error)
          case None =>
            if accumulated.count.signum == 0 then Left(MetricError.Empty)
            else
              rootMeanSquared(
                accumulated.squaredError.result,
                accumulated.count.toDouble
              )

  /** Reproducible root mean squared error weighted from observation metadata.
    *
    * Weights must be finite and non-negative, and their total must be
    * positive. A zero-weight observation is valid and contributes no squared
    * error.
    */
  def weightedRmse[M](
      weightPolicy: WeightPolicyId
  )(using
      weightOf: WeightOf[M]
  ): ObjectiveMetric[
    Scored[Double, Double, M],
    RootMeanSquaredError
  ] =
    new ObjectiveMetric[
      Scored[Double, Double, M],
      RootMeanSquaredError
    ]:
      type Acc = WeightedRmseAccumulator
      given accumulator: CommutativeMonoid[WeightedRmseAccumulator] =
        WeightedRmseAccumulator.monoid

      val direction: ObjectiveDirection = ObjectiveDirection.Minimize

      val descriptor: MetricDescriptor =
        MetricDescriptor(
          MetricId("weighted-root-mean-squared-error"),
          MetricVersion("1"),
          AuditValue.record(
            "weight-policy" -> AuditValue.text(weightPolicy.value)
          ),
          MetricNumericPolicy.Reproducible,
          Some(ObjectiveDescriptor(direction, "binary64-decimal-v1"))
        )

      def auditScore(score: RootMeanSquaredError): AuditValue =
        AuditValue.decimal(score.value)

      def observe(
          scored: Scored[Double, Double, M]
      ): WeightedRmseAccumulator =
        weightedObservation(
          scored.truth,
          scored.prediction,
          weightOf(scored.meta)
        )

      def finish(
          accumulated: WeightedRmseAccumulator
      ): Either[MetricError, RootMeanSquaredError] =
        MetricProblems.first(accumulated.problems) match
          case Some(error) => Left(error)
          case None =>
            if accumulated.count.signum == 0 then Left(MetricError.Empty)
            else
              val totalWeight = accumulated.totalWeight.result
              if totalWeight == 0.0 then
                Left(MetricError.ZeroTotalWeight)
              else
                rootMeanSquared(
                  accumulated.weightedSquaredError.result,
                  totalWeight
                )

  private def unweightedObservation(
      truth: Double,
      prediction: Double
  ): RmseAccumulator =
    problems(truth, prediction) match
      case issues if issues.nonEmpty =>
        new RmseAccumulator(
          ReproducibleSum.empty,
          BigInt(0),
          issues
        )
      case _ =>
        val residual = truth - prediction
        if !residual.isFinite then
          new RmseAccumulator(
            ReproducibleSum.empty,
            BigInt(0),
            Set(MetricProblem.residual(truth, prediction))
          )
        else
          val squared = residual * residual
          if !squared.isFinite then
            new RmseAccumulator(
              ReproducibleSum.empty,
              BigInt(0),
              Set(MetricProblem.squared(residual))
            )
          else
            new RmseAccumulator(
              ReproducibleSum.empty.add(squared),
              BigInt(1),
              MetricProblems.empty
            )

  private def weightedObservation(
      truth: Double,
      prediction: Double,
      weight: Double
  ): WeightedRmseAccumulator =
    val inputProblems = problems(truth, prediction) ++ weightProblems(weight)
    if inputProblems.nonEmpty then
      new WeightedRmseAccumulator(
        ReproducibleSum.empty,
        ReproducibleSum.empty,
        BigInt(0),
        inputProblems
      )
    else
      val residual = truth - prediction
      if !residual.isFinite then
        invalidWeighted(MetricProblem.residual(truth, prediction))
      else
        val squared = residual * residual
        if !squared.isFinite then
          invalidWeighted(MetricProblem.squared(residual))
        else
          val weighted = squared * weight
          if !weighted.isFinite then
            invalidWeighted(MetricProblem.weighted(squared, weight))
          else
            new WeightedRmseAccumulator(
              ReproducibleSum.empty.add(weighted),
              ReproducibleSum.empty.add(weight),
              BigInt(1),
              MetricProblems.empty
            )

  private def invalidWeighted(
      problem: MetricProblem
  ): WeightedRmseAccumulator =
    new WeightedRmseAccumulator(
      ReproducibleSum.empty,
      ReproducibleSum.empty,
      BigInt(0),
      Set(problem)
    )

  private def problems(
      truth: Double,
      prediction: Double
  ): Set[MetricProblem] =
    val truthProblems =
      if truth.isFinite then Set.empty
      else Set(MetricProblem.truth(truth))
    val predictionProblems =
      if prediction.isFinite then Set.empty
      else Set(MetricProblem.prediction(prediction))
    truthProblems union predictionProblems

  private def weightProblems(weight: Double): Set[MetricProblem] =
    if !weight.isFinite then Set(MetricProblem.weight(weight))
    else if weight < 0.0 then Set(MetricProblem.negativeWeight(weight))
    else Set.empty

  private def rootMeanSquared(
      numerator: Double,
      denominator: Double
  ): Either[MetricError, RootMeanSquaredError] =
    val mean = numerator / denominator
    val result = math.sqrt(mean)
    if result.isFinite then Right(RootMeanSquaredError(result))
    else Left(MetricError.NonFiniteResult)

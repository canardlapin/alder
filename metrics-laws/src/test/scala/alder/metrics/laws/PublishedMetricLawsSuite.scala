package alder.metrics.laws

import alder.kernel.{Scored, WeightOf}
import alder.metrics.*
import cats.kernel.Eq
import munit.DisciplineSuite
import org.scalacheck.Arbitrary

final case class LawWeight(value: Double)

given WeightOf[LawWeight] with
  def apply(meta: LawWeight): Double = meta.value

class PublishedMetricLawsSuite extends DisciplineSuite:
  private given unitScoredArbitrary
      : Arbitrary[Scored[Double, Double, Unit]] =
    Arbitrary(MetricGenerators.scoredDoubles(()))

  private given resultEq
      : Eq[Either[MetricError, RootMeanSquaredError]] =
    Eq.fromUniversalEquals

  checkAll(
    "RMSE",
    new MetricTests(RegressionMetrics.rmse[Unit]).all
  )

  private given weightedScoredArbitrary
      : Arbitrary[Scored[Double, Double, LawWeight]] =
    Arbitrary(
      MetricGenerators
        .scoredDoubles(LawWeight(1.0))
    )

  checkAll(
    "weighted RMSE",
    new MetricTests(
      RegressionMetrics.weightedRmse[LawWeight](
        WeightPolicyId("law-weight")
      )
    ).all
  )

  private given intEq: Eq[Int] = Eq.fromUniversalEquals

  private given intScoredArbitrary
      : Arbitrary[Scored[Int, Int, Unit]] =
    Arbitrary(
      for
        truth <- org.scalacheck.Gen.choose(-5, 5)
        prediction <- org.scalacheck.Gen.choose(-5, 5)
      yield Scored(truth, prediction, ())
    )

  private given accuracyResultEq
      : Eq[Either[MetricError, Accuracy]] =
    Eq.fromUniversalEquals

  checkAll(
    "accuracy",
    new MetricTests(
      ClassificationMetrics.accuracy[Int, Unit](
        EqualityPolicyId("cats.eq.int")
      )
    ).all
  )

  private given weightedIntScoredArbitrary
      : Arbitrary[Scored[Int, Int, LawWeight]] =
    Arbitrary(
      for
        truth <- org.scalacheck.Gen.choose(-5, 5)
        prediction <- org.scalacheck.Gen.choose(-5, 5)
      yield Scored(truth, prediction, LawWeight(1.0))
    )

  checkAll(
    "weighted accuracy",
    new MetricTests(
      ClassificationMetrics.weightedAccuracy[Int, LawWeight](
        EqualityPolicyId("cats.eq.int"),
        WeightPolicyId("law-weight")
      )
    ).all
  )

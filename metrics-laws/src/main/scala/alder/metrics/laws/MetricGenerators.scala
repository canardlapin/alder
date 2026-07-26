package alder.metrics.laws

import alder.kernel.Scored
import alder.testkit.AlderGenerators
import org.scalacheck.Gen

object MetricGenerators:
  val adversarialFiniteDouble: Gen[Double] =
    Gen.frequency(
      8 -> AlderGenerators.finiteDouble,
      1 -> Gen.oneOf(
        0.0,
        -0.0,
        Double.MinPositiveValue,
        -Double.MinPositiveValue,
        java.lang.Double.MIN_NORMAL,
        -java.lang.Double.MIN_NORMAL,
        1.0e150,
        -1.0e150
      )
    )

  def scoredDoubles[M](
      meta: M
  ): Gen[Scored[Double, Double, M]] =
    for
      truth <- adversarialFiniteDouble
      prediction <- adversarialFiniteDouble
    yield Scored(truth, prediction, meta)

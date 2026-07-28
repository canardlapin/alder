package alder.models.linear

import alder.data.FeatureView
import alder.kernel.*

/** Per-coordinate linear contribution of a ridge prediction. */
final case class RidgeAttribution(
    intercept: Double,
    contributions: IArray[Double],
    prediction: Double
)

given [X]: Coefficients[RidgeModel[X]] with
  def coefficientCount(trained: Trained[RidgeModel[X]]): Int =
    trained.terminal.solution.coefficientCount

  def coefficient(trained: Trained[RidgeModel[X]], index: Int): Double =
    trained.terminal.solution.coefficient(index)

  def coefficients(trained: Trained[RidgeModel[X]]): IArray[Double] =
    trained.terminal.solution.coefficientsCopy

  def intercept(trained: Trained[RidgeModel[X]]): Double =
    trained.terminal.solution.intercept

given [X](using features: FeatureView[X]): Explain[RidgeModel[X], X] with
  type Attribution = RidgeAttribution

  def apply(
      trained: Trained[RidgeModel[X]],
      input: X
  ): Either[ExplainError, RidgeAttribution] =
    features
      .read(input)
      .left
      .map(error =>
        ExplainError.NotExplainable(s"feature view failed: $error")
      )
      .map { values =>
        val solution = trained.terminal.solution
        val contributions = IArray.tabulate(values.length) { index =>
          values(index) * solution.coefficient(index)
        }
        var prediction = solution.intercept
        var index = 0
        while index < contributions.length do
          prediction += contributions(index)
          index += 1
        RidgeAttribution(solution.intercept, contributions, prediction)
      }

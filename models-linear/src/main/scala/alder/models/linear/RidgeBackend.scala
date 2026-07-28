package alder.models.linear

import alder.data.FeatureView
import alder.kernel.*

/** Numerical backend boundary for ridge fitting.
  *
  * Implementations receive validated application coordinates, explicit row
  * weights, and the framework's numerical mode. They must return a solution
  * carrying solver evidence and their exact backend fingerprint.
  */
trait RidgeBackend[F[_]]:
  /** Solves one ridge problem on fitting-authorized data. */
  def solve[X, M, U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Double, M]],
      features: FeatureView[X],
      config: RidgeConfig,
      weights: RowWeights,
      context: BackendContext
  ): FitResult[F, RidgeBackendError, RidgeSolution]

  /** Identity and configuration of the backend used for audit records. */
  def fingerprint: BackendFingerprint

private[alder] final class RidgeProblem(
    val rowIds: IArray[RowId],
    val rows: Int,
    val columns: Int,
    val design: IArray[Double],
    val targets: IArray[Double],
    val weights: IArray[Double],
    val featureMeans: IArray[Double],
    val targetMean: Double,
    val weightSum: Double
):
  def feature(row: Int, column: Int): Double =
    design(row * columns + column)

  def centeredFeature(
      row: Int,
      column: Int,
      fitIntercept: Boolean
  ): Double =
    val value = feature(row, column)
    if fitIntercept then value - featureMeans(column) else value

  def centeredTarget(row: Int, fitIntercept: Boolean): Double =
    val value = targets(row)
    if fitIntercept then value - targetMean else value

private[alder] object RidgeProblem:
  def materialize[X, M, U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Double, M]],
      features: FeatureView[X],
      rowWeights: RowWeights
  ): Either[RidgeBackendError, RidgeProblem] =
    if data.size > Int.MaxValue.toLong then
      Left(RidgeBackendError.TooManyRows(data.size))
    else if features.size == 0 then
      Left(RidgeBackendError.EmptyCoordinateSpace)
    else
      val expectedRows = data.size.toInt
      val suppliedWeights = rowWeights match
        case _: RowWeights.Uniform.type => None
        case values: RowWeights.ByRow   => Some(values.valuesCopy)
      suppliedWeights match
        case Some(values) if values.length != expectedRows =>
          Left(
            RidgeBackendError.InvalidWeightCount(
              expectedRows,
              values.length
            )
          )
        case _ =>
          val ids = new Array[RowId](expectedRows)
          val design = new Array[Double](expectedRows * features.size)
          val targets = new Array[Double](expectedRows)
          val weights = new Array[Double](expectedRows)
          var nextRow = 0
          var failure: Option[RidgeBackendError] = None
          data.data.foreachRow { (id, example) =>
            if failure.isEmpty then
              ids(nextRow) = id
              val weight = suppliedWeights.fold(1.0)(_(nextRow))
              if !weight.isFinite || weight < 0.0 then
                failure = Some(RidgeBackendError.InvalidWeight(id, weight))
              else if !example.target.isFinite then
                failure =
                  Some(
                    RidgeBackendError.NonFiniteTarget(id, example.target)
                  )
              else
                features.read(example.input) match
                  case Left(error) =>
                    failure =
                      Some(RidgeBackendError.Coordinate(id, error))
                  case Right(values) =>
                    var column = 0
                    while column < values.length && failure.isEmpty do
                      val value = values(column)
                      if !value.isFinite then
                        failure = Some(
                          RidgeBackendError.NonFiniteFeature(
                            id,
                            features.names(column),
                            value
                          )
                        )
                      else
                        design(nextRow * features.size + column) = value
                      column += 1
                    targets(nextRow) = example.target
                    weights(nextRow) = weight
              nextRow += 1
          }
          failure match
            case Some(error) => Left(error)
            case None =>
              val weightSum = weights.sum
              if !weightSum.isFinite || weightSum <= 0.0 then
                Left(RidgeBackendError.NonPositiveTotalWeight(weightSum))
              else
                val means = new Array[Double](features.size)
                var row = 0
                var targetTotal = 0.0
                while row < expectedRows do
                  val weight = weights(row)
                  targetTotal += weight * targets(row)
                  var column = 0
                  while column < features.size do
                    means(column) +=
                      weight * design(row * features.size + column)
                    column += 1
                  row += 1
                var column = 0
                while column < means.length do
                  means(column) /= weightSum
                  column += 1
                Right(
                  new RidgeProblem(
                    IArray.unsafeFromArray(ids),
                    expectedRows,
                    features.size,
                    IArray.unsafeFromArray(design),
                    IArray.unsafeFromArray(targets),
                    IArray.unsafeFromArray(weights),
                    IArray.unsafeFromArray(means),
                    targetTotal / weightSum,
                    weightSum
                  )
                )

  def evidence(
      problem: RidgeProblem,
      coefficients: IArray[Double],
      intercept: Double,
      penalty: Double
  ): (Double, Double) =
    var objective = 0.0
    val gradient = new Array[Double](problem.columns)
    var row = 0
    while row < problem.rows do
      var prediction = intercept
      var column = 0
      while column < problem.columns do
        prediction += problem.feature(row, column) * coefficients(column)
        column += 1
      val residual = prediction - problem.targets(row)
      objective += problem.weights(row) * residual * residual
      column = 0
      while column < problem.columns do
        gradient(column) +=
          problem.weights(row) * problem.feature(row, column) * residual
        column += 1
      row += 1
    var coefficientNorm = 0.0
    var column = 0
    var kktSquared = 0.0
    while column < problem.columns do
      val coefficient = coefficients(column)
      coefficientNorm += coefficient * coefficient
      val kkt = gradient(column) + penalty * coefficient
      kktSquared += kkt * kkt
      column += 1
    (
      objective + penalty * coefficientNorm,
      math.sqrt(kktSquared)
    )

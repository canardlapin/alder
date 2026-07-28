package alder.quickstart

import alder.kernel.Scored
import alder.metrics.{
  ObjectiveMetric,
  RegressionMetrics,
  RootMeanSquaredError
}

/** Built-in metrics for the ordinary supervised path. */
object Metrics:

  /** Root mean squared error for the ordinary `Unit`-metadata workflow. */
  val rmse: ObjectiveMetric[
    Scored[Double, Double, Unit],
    RootMeanSquaredError
  ] =
    RegressionMetrics.rmse[Unit]

  /** Root mean squared error retaining explicit metadata type `M`. */
  def rmseOf[M]: ObjectiveMetric[
    Scored[Double, Double, M],
    RootMeanSquaredError
  ] =
    RegressionMetrics.rmse[M]

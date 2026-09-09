package alder.quickstart

import alder.data.{Dense, FeatureView}
import alder.models.linear.{
  RidgeConfig,
  RidgeConfigError,
  RidgePenalty,
  RidgeTolerance,
  RidgeRegression
}
import alder.preprocess.Standardized
import alder.ridge.linop4s.Linop4sRidgeBackend
import cats.Id

/** Named ridge presets. The solver identity remains visible. */
object Ridge:

  /** Validated ridge configuration with the linop4s LSQR backend, targeting
    * dense standardized features of `A`.
    */
  def lsqr[A](
      penalty: RidgePenalty,
      fitIntercept: Boolean = true,
      tolerance: RidgeTolerance = RidgeTolerance.default
  )(using
      FeatureView[A]
  ): RidgeRegression[Id, Dense[Standardized[A]], Unit] =
    lsqr(RidgeConfig(penalty, fitIntercept, tolerance))

  /** Uses a previously validated ridge configuration with the concrete
    * linop4s LSQR backend.
    */
  def lsqr[A](config: RidgeConfig)(using
      FeatureView[A]
  ): RidgeRegression[Id, Dense[Standardized[A]], Unit] =
    given FeatureView[Dense[Standardized[A]]] = Standardized.coordinates[A]
    RidgeRegression.sync(
      config,
      Linop4sRidgeBackend.lsqr[Id]()
    )

  /** Checked dynamic counterpart to [[lsqr]]. */
  def lsqrChecked[A](
      penalty: Double,
      fitIntercept: Boolean = true,
      tolerance: Double = 1.0e-10
  )(using
      FeatureView[A]
  ): Either[
    RidgeConfigError,
    RidgeRegression[Id, Dense[Standardized[A]], Unit]
  ] =
    RidgeConfig
      .create(penalty, fitIntercept, tolerance)
      .map(lsqr[A])

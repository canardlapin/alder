package alder.quickstart

import alder.data.{Dense, FeatureView, SchemaError}
import alder.models.linear.{
  RidgeConfig,
  RidgeConfigError,
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
      penalty: Double,
      fitIntercept: Boolean = true,
      tolerance: Double = 1.0e-10
  )(using
      FeatureView[A]
  ): Either[
    RidgeConfigError | SchemaError,
    RidgeRegression[Id, Dense[Standardized[A]], Unit]
  ] =
    for
      config <- RidgeConfig
        .create(penalty, fitIntercept, tolerance)
        .left
        .map(error => error: RidgeConfigError | SchemaError)
      coordinates <- Standardized
        .coordinates[A]
        .left
        .map(error => error: RidgeConfigError | SchemaError)
    yield
      given FeatureView[Dense[Standardized[A]]] = coordinates
      RidgeRegression.sync(
        config,
        Linop4sRidgeBackend.lsqr[Id]()
      )

package alder.quickstart

import alder.data.FeatureView
import alder.preprocess.{ScaleFitError, StandardScaler, ZeroVariance}
import cats.Id

/** Named standardization presets expanding to [[StandardScaler]]. */
object Standardize:

  /** Center and scale, emitting zeros for zero-variance coordinates. */
  def emitZero[A](using
      FeatureView[A]
  ): Either[ScaleFitError, StandardScaler[Id, A]] =
    StandardScaler.sync(ZeroVariance.EmitZero)

  /** Center and scale, rejecting zero-variance coordinates at fit time. */
  def reject[A](using
      FeatureView[A]
  ): Either[ScaleFitError, StandardScaler[Id, A]] =
    StandardScaler.sync(ZeroVariance.Reject)

  /** Explicit zero-variance policy expansion. */
  def apply[A](zeroVariance: ZeroVariance)(using
      FeatureView[A]
  ): Either[ScaleFitError, StandardScaler[Id, A]] =
    StandardScaler.sync(zeroVariance)

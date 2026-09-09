package alder.quickstart

import alder.data.FeatureView
import alder.preprocess.{StandardScaler, ZeroVariance}
import cats.Id

/** Synchronous standardization expanding to [[StandardScaler]]. */
object Standardize:

  /** Center and scale under an explicit zero-variance policy. Construction is
    * total because [[FeatureView]] already owns a validated feature schema.
    */
  def apply[A](zeroVariance: ZeroVariance)(using
      FeatureView[A]
  ): StandardScaler[Id, A] =
    StandardScaler.sync(zeroVariance)

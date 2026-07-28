package alder.application

import alder.data.{CompleteResampler, CrossFittedFeatureMap, crossFitted}
import alder.kernel.*
import cats.{Id, Monad}

/** Staged construction over exact core component values.
  *
  * Every operation expands to an ordinary Transform / FeatureMap / Learner
  * combinator. Wrappers contribute no audit node, stage ordinal, or seed.
  */
object Blueprint:

  /** Starts a supervised blueprint with explicit effect and metadata types. */
  def apply[F[_], X, Y, M]: Empty[F, X, Y, M] =
    new Empty

  /** Synchronous supervised blueprint with `Unit` metadata. */
  def supervised[X, Y]: Empty[Id, X, Y, Unit] =
    new Empty

  /** Input / target / metadata intent before any component is attached. */
  final class Empty[F[_], X, Y, M] private[application]:
    def via[Z, T <: Transform[F, X, Z]](transform: T)(using
        Monad[F]
    ): TargetBlind[F, X, Y, M, Z, T] =
      new TargetBlind(transform)

    def withFeatureMap[Z, FM <: FeatureMap[F, X, Y, M, Z]](
        featureMap: FM
    )(using Monad[F]): LearnerReady[F, X, Y, M, Z, FM] =
      new LearnerReady(featureMap)

    def crossFit[
        Z,
        E <: FoldEncoder[F, X, Y, M, Z]
    ](
        encoder: E,
        resampler: CompleteResampler[Example[X, Y, M]]
    )(using Monad[F]): LearnerReady[
      F,
      X,
      Y,
      M,
      Z,
      CrossFittedFeatureMap[F, X, Y, M, Z, E]
    ] =
      new LearnerReady(FeatureMap.crossFitted(encoder, resampler))

    def learn[P, L <: Learner[F, X, Y, M, P]](
        learner: L
    ): Complete[F, X, Y, M, P, L] =
      new Complete(learner)

  /** Target-blind preparation retaining the exact transform value. */
  final class TargetBlind[
      F[_],
      X,
      Y,
      M,
      Z,
      T <: Transform[F, X, Z]
  ] private[application] (
      val transform: T
  )(using Monad[F]):
    def via[W, R <: Transform[F, Z, W]](next: R): TargetBlind[
      F,
      X,
      Y,
      M,
      W,
      ThenTransform[F, X, Z, W, transform.type, R]
    ] =
      new TargetBlind(transform.andThen(next))

    def withFeatureMap[W, FM <: FeatureMap[F, Z, Y, M, W]](
        featureMap: FM
    ): LearnerReady[
      F,
      X,
      Y,
      M,
      W,
      ThenFeatureMap[F, X, Y, M, Z, W, transform.type, FM]
    ] =
      new LearnerReady(transform.andThen(featureMap))

    def crossFit[
        W,
        E <: FoldEncoder[F, Z, Y, M, W]
    ](
        encoder: E,
        resampler: CompleteResampler[Example[Z, Y, M]]
    ): LearnerReady[
      F,
      X,
      Y,
      M,
      W,
      ThenFeatureMap[
        F,
        X,
        Y,
        M,
        Z,
        W,
        transform.type,
        CrossFittedFeatureMap[F, Z, Y, M, W, E]
      ]
    ] =
      new LearnerReady(
        transform.andThen(FeatureMap.crossFitted(encoder, resampler))
      )

    def learn[P, L <: Learner[F, Z, Y, M, P]](learner: L): Complete[
      F,
      X,
      Y,
      M,
      P,
      LearnedWith[
        F,
        X,
        Y,
        M,
        Z,
        P,
        InputOnlyFeatureMap[F, X, Y, M, Z, transform.type],
        L
      ]
    ] =
      new Complete(transform.learnWith(learner))

  /** Learner-ready preparation retaining the exact feature-map value. */
  final class LearnerReady[
      F[_],
      X,
      Y,
      M,
      Z,
      FM <: FeatureMap[F, X, Y, M, Z]
  ] private[application] (
      val featureMap: FM
  )(using Monad[F]):
    def mapOutput[W](f: Z => W): LearnerReady[
      F,
      X,
      Y,
      M,
      W,
      MappedOutputFeatureMap[F, X, Y, M, Z, W, featureMap.type]
    ] =
      new LearnerReady(featureMap.mapOutput(f))

    def mapOutput[W](named: NamedMap[Z, W]): LearnerReady[
      F,
      X,
      Y,
      M,
      W,
      MappedOutputFeatureMap[F, X, Y, M, Z, W, featureMap.type]
    ] =
      new LearnerReady(featureMap.mapOutput(named))

    def learn[P, L <: Learner[F, Z, Y, M, P]](learner: L): Complete[
      F,
      X,
      Y,
      M,
      P,
      LearnedWith[F, X, Y, M, Z, P, featureMap.type, L]
    ] =
      new Complete(featureMap.learnWith(learner))

  /** Completed blueprint exposing the exact concrete learner. */
  final class Complete[
      F[_],
      X,
      Y,
      M,
      P,
      L <: Learner[F, X, Y, M, P]
  ] private[application] (
      val learner: L
  )

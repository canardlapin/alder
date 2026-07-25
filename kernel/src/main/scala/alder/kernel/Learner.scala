package alder.kernel

import cats.Monad

/** The terminal learning algorithm. It may depend on all training targets
  * because its in-sample predictions are not passed downstream; the moment
  * they are (calibration, stacking), the whole chain must be cross-fitted
  * through the explicit CrossFit protocol.
  */
trait Learner[F[_], X, Y, M, P]:
  type FitError
  type RunError
  type Model <: Pipe[X, RunError, P]

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]]
  )(using FitContext): FitResult[F, FitError, Trained[Model]]

extension [F[_], X, Y, M, Z, FM <: FeatureMap[F, X, Y, M, Z]](featureMap: FM)
  /** FeatureMap ∘ Learner = Learner (terminal). A workflow IS this value;
    * there is no Workflow abstraction (D3). Top-level so
    * `import alder.kernel.*` brings it into scope.
    */
  def learnWith[P, L <: Learner[F, Z, Y, M, P]](learner: L)(using
      Monad[F]
  ): LearnedWith[F, X, Y, M, Z, P, FM, L] =
    LearnedWith(featureMap, learner)

/** FeatureMap composed with a terminal learner: fits the feature map, fits the
  * learner on the LearnerReady rows, and serves through the chained pipe. The
  * resulting model accepts the original input X.
  */
final class LearnedWith[
    F[_],
    X,
    Y,
    M,
    Z,
    P,
    FM <: FeatureMap[F, X, Y, M, Z],
    L <: Learner[F, Z, Y, M, P]
](
    val featureMap: FM,
    val learner: L
)(using Monad[F])
    extends Learner[F, X, Y, M, P]:

  type FitError = featureMap.FitError | learner.FitError
  type RunError = featureMap.RunError | learner.RunError
  type Model = Pipe.Chain[X, featureMap.RunError, Z, learner.RunError, P]

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]]
  )(using context: FitContext): FitResult[F, FitError, Trained[Model]] =
    for
      prepared <- featureMap
        .fit(data)(using context.forChild(0))
        .widenFailure[FitError]
      model <- learner
        .fit(prepared.rows)(using context.forChild(1))
        .widenFailure[FitError]
    yield
      val chained: Model =
        Pipe.Chain(prepared.fitted.artifact, model.artifact)
      context.composite(
        artifact = chained,
        data = data.fingerprint,
        component = AlderComponents.learnedWith,
        preparation = prepared.lineage,
        children = Vector(prepared.fitted.audit, model.audit)
      )

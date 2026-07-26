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

  private[alder] def fitFrom[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]],
      startOrdinal: Int
  )(using context: FitContext): FitResult[F, FitError, Trained[Model]] =
    fit(data)(using context.forChild(startOrdinal))

  private[alder] def stageCount: Int = 1

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
  type Model = Pipe.Chain[
    X,
    featureMap.RunError,
    Z,
    learner.RunError,
    P,
    featureMap.Fitted,
    learner.Model
  ]

  override private[alder] def stageCount: Int =
    featureMap.stageCount + learner.stageCount

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]]
  )(using context: FitContext): FitResult[F, FitError, Trained[Model]] =
    fitFrom(data, 0)

  override private[alder] def fitFrom[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]],
      startOrdinal: Int
  )(using context: FitContext): FitResult[F, FitError, Trained[Model]] =
    for
      prepared <- featureMap
        .fitFrom(data, startOrdinal)
        .widenFailure[FitError]
      model <- learner
        .fitFrom(prepared.rows, startOrdinal + featureMap.stageCount)
        .widenFailure[FitError]
    yield
      val chained: Model =
        Pipe.Chain(prepared.fitted.artifact, model.artifact)
      context.composite(
        artifact = chained,
        trainedOn = data,
        component = AlderComponents.learnedWith,
        preparation = prepared.lineage,
        children =
          prepared.fitted.audit.flattenedPreparationSequence ++
            model.audit.flattenedPreparationSequence,
        shape = AuditShape.WorkflowSequence
      )

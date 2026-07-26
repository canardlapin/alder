package alder.kernel

import cats.Monad
import cats.data.EitherT

/** Leakage-aware supervised preprocessing. Its prepared rows are LearnerReady:
  * each row was prepared without its own target, so they may feed a terminal
  * learner but never another preprocessing stage through the safe API (D4).
  * The prepared rows are deliberately NOT required to equal replaying the
  * final fitted pipe.
  */
trait FeatureMap[F[_], X, Y, M, Z]:
  type Scope <: Preparation.LearnerReady
  type FitError
  type RunError
  type Fitted <: Pipe[X, RunError, Z]

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]]
  )(using FitContext): FitResult[
    F,
    FitError,
    Prepared[Scope, U, Fitted, Example[Z, Y, M]]
  ]

  /** Total rowwise postprocessing has no fitted state or training dependency,
    * so it preserves the preparation scope exactly (D20).
    */
  final def mapOutput[W](f: Z => W)(using
      Monad[F]
  ): MappedOutputFeatureMap[F, X, Y, M, Z, W, this.type] =
    MappedOutputFeatureMap(this, f)

  /** FeatureMap composed with a terminal learner is a terminal learner. */
  final def learnWith[P, L <: Learner[F, Z, Y, M, P]](learner: L)(using
      Monad[F]
  ): LearnedWith[F, X, Y, M, Z, P, this.type, L] =
    LearnedWith(this, learner)

  /** Internal normalized-plan interpreter hook. */
  private[alder] def fitFrom[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]],
      startOrdinal: Int
  )(using context: FitContext): FitResult[
    F,
    FitError,
    Prepared[Scope, U, Fitted, Example[Z, Y, M]]
  ] =
    fit(data)(using context.forChild(startOrdinal))

  private[alder] def stageCount: Int = 1

object FeatureMap:
  def inputOnly[
      F[_],
      X,
      Y,
      M,
      Z,
      T <: Transform[F, X, Z]
  ](transform: T)(using
      Monad[F]
  ): InputOnlyFeatureMap[F, X, Y, M, Z, T] =
    InputOnlyFeatureMap(transform)

// FeatureMap.crossFitted is an extension supplied by alder-data beside
// CompleteResampler, preserving the one-way data -> kernel dependency (D19).

final class InputOnlyFeatureMap[
    F[_],
    X,
    Y,
    M,
    Z,
    T <: Transform[F, X, Z]
](val transform: T)(using Monad[F])
    extends FeatureMap[F, X, Y, M, Z]:
  type Scope = Preparation.Reusable
  type FitError = transform.FitError | PreparationError
  type RunError = transform.RunError
  type Fitted = transform.Fitted

  override private[alder] def stageCount: Int = transform.stageCount

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]]
  )(using context: FitContext): FitResult[
    F,
    FitError,
    Prepared[Scope, U, Fitted, Example[Z, Y, M]]
  ] =
    val inputs = DataOperations.mapNonEmpty(data)(_.input)
    transform
      .fit(inputs)
      .widenFailure[FitError]
      .flatMap { prepared =>
        val restored = DataOperations
          .restoreExamples(data, prepared.rows, context.stagePath)
          .left
          .map(_.widen[FitError])
          .map(rows =>
            new Prepared(
              prepared.fitted,
              rows,
              prepared.lineage
            )
          )
        EitherT.fromEither[F](restored)
      }

  override private[alder] def fitFrom[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]],
      startOrdinal: Int
  )(using context: FitContext): FitResult[
    F,
    FitError,
    Prepared[Scope, U, Fitted, Example[Z, Y, M]]
  ] =
    val inputs = DataOperations.mapNonEmpty(data)(_.input)
    transform
      .fitFrom(inputs, startOrdinal)
      .widenFailure[FitError]
      .flatMap { prepared =>
        val restored = DataOperations
          .restoreExamples(
            data,
            prepared.rows,
            context.forChild(startOrdinal).stagePath
          )
          .left
          .map(_.widen[FitError])
          .map(rows =>
            new Prepared(
              prepared.fitted,
              rows,
              prepared.lineage
            )
          )
        EitherT.fromEither[F](restored)
      }

final class MappedOutputFeatureMap[
    F[_],
    X,
    Y,
    M,
    Z,
    W,
    FM <: FeatureMap[F, X, Y, M, Z]
](
    val featureMap: FM,
    val f: Z => W
)(using Monad[F])
    extends FeatureMap[F, X, Y, M, W]:
  type Scope = featureMap.Scope
  type FitError = featureMap.FitError
  type RunError = featureMap.RunError
  type Fitted = Pipe.Mapped[X, featureMap.RunError, Z, W]

  override private[alder] def stageCount: Int = featureMap.stageCount

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]]
  )(using context: FitContext): FitResult[
    F,
    FitError,
    Prepared[Scope, U, Fitted, Example[W, Y, M]]
  ] =
    featureMap.fit(data).map { prepared =>
      val fitted: Fitted = Pipe.Mapped(prepared.fitted.artifact, f)
      val rows = DataOperations.mapNonEmpty(prepared.rows)(example =>
        Example(f(example.input), example.target, example.meta)
      )
      val lineage = PreparationLineage.sequence(
        context.stagePath,
        prepared.lineage.scope,
        Vector(prepared.lineage)
      )
      val trained = context.composite(
        fitted,
        data,
        AlderComponents.mapFeatureOutput,
        lineage,
        Vector(prepared.fitted.audit)
      )
      new Prepared(trained, rows, lineage)
    }

  override private[alder] def fitFrom[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]],
      startOrdinal: Int
  )(using context: FitContext): FitResult[
    F,
    FitError,
    Prepared[Scope, U, Fitted, Example[W, Y, M]]
  ] =
    featureMap.fitFrom(data, startOrdinal).map { prepared =>
      val fitted: Fitted = Pipe.Mapped(prepared.fitted.artifact, f)
      val rows = DataOperations.mapNonEmpty(prepared.rows)(example =>
        Example(f(example.input), example.target, example.meta)
      )
      val lineage = PreparationLineage.sequence(
        context.stagePath,
        prepared.lineage.scope,
        Vector(prepared.lineage)
      )
      val trained = context.composite(
        fitted,
        data,
        AlderComponents.mapFeatureOutput,
        lineage,
        Vector(prepared.fitted.audit)
      )
      new Prepared(trained, rows, lineage)
    }

final class ThenFeatureMap[
    F[_],
    X,
    Y,
    M,
    Z,
    W,
    T <: Transform[F, X, Z],
    FM <: FeatureMap[F, Z, Y, M, W]
](
    val transform: T,
    val featureMap: FM
)(using Monad[F])
    extends FeatureMap[F, X, Y, M, W]:
  type Scope = featureMap.Scope
  type FitError =
    transform.FitError | PreparationError | featureMap.FitError
  type RunError = transform.RunError | featureMap.RunError
  type Fitted =
    Pipe.Chain[
      X,
      transform.RunError,
      Z,
      featureMap.RunError,
      W,
      transform.Fitted,
      featureMap.Fitted
    ]

  override private[alder] def stageCount: Int =
    transform.stageCount + featureMap.stageCount

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]]
  )(using context: FitContext): FitResult[
    F,
    FitError,
    Prepared[Scope, U, Fitted, Example[W, Y, M]]
  ] = fitFrom(data, 0)

  override private[alder] def fitFrom[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]],
      startOrdinal: Int
  )(using context: FitContext): FitResult[
    F,
    FitError,
    Prepared[Scope, U, Fitted, Example[W, Y, M]]
  ] =
    val inputs = DataOperations.mapNonEmpty(data)(_.input)
    for
      first <- transform
        .fitFrom(inputs, startOrdinal)
        .widenFailure[FitError]
      firstRows <- EitherT.fromEither[F](
        DataOperations
          .restoreExamples(
            data,
            first.rows,
            context.forChild(startOrdinal).stagePath
          )
          .left
          .map(_.widen[FitError])
      )
      second <- featureMap
        .fitFrom(firstRows, startOrdinal + transform.stageCount)
        .widenFailure[FitError]
    yield
      val fitted: Fitted =
        Pipe.Chain(first.fitted.artifact, second.fitted.artifact)
      val lineage = PreparationLineage.sequence(
        context.stagePath,
        second.lineage.scope,
        Vector(first.lineage, second.lineage)
      )
      val trained = context.composite(
        fitted,
        data,
        AlderComponents.composeFeatureMap,
        lineage,
        first.fitted.audit.flattenedPreparationSequence ++
          second.fitted.audit.flattenedPreparationSequence,
        shape = AuditShape.FeatureMapSequence
      )
      new Prepared(trained, second.rows, lineage)

/** What a target-aware encoder must implement: fit a state, apply the state.
  * FeatureMap.crossFitted owns the fold protocol; there is deliberately no
  * public fit-all/encode-all shortcut.
  */
trait FoldEncoder[F[_], X, Y, M, Z]:
  type State
  type FitError
  type RunError

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]]
  )(using FitContext): FitResult[F, FitError, Trained[State]]

  def encode(state: State, input: X): Either[Failure[RunError], Z]

  final def andThen[W, T <: Transform[F, Z, W]](transform: T)(using
      Monad[F]
  ): ThenFoldEncoder[F, X, Y, M, Z, W, this.type, T] =
    ThenFoldEncoder(this, transform)

  private[alder] def fitFrom[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]],
      startOrdinal: Int
  )(using context: FitContext): FitResult[F, FitError, Trained[State]] =
    fit(data)(using context.forChild(startOrdinal))

  private[alder] def stageCount: Int = 1

final class FoldEncoderState[S, P](val encoder: S, val transform: Trained[P])

final class ThenFoldEncoder[
    F[_],
    X,
    Y,
    M,
    Z,
    W,
    E <: FoldEncoder[F, X, Y, M, Z],
    T <: Transform[F, Z, W]
](
    val encoder: E,
    val transform: T
)(using Monad[F])
    extends FoldEncoder[F, X, Y, M, W]:
  type State = FoldEncoderState[encoder.State, transform.Fitted]
  type FitError =
    encoder.FitError | encoder.RunError | transform.FitError
  type RunError = encoder.RunError | transform.RunError

  override private[alder] def stageCount: Int =
    encoder.stageCount + transform.stageCount

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]]
  )(using context: FitContext): FitResult[F, FitError, Trained[State]] =
    fitFrom(data, 0)

  override private[alder] def fitFrom[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]],
      startOrdinal: Int
  )(using context: FitContext): FitResult[F, FitError, Trained[State]] =
    for
      first <- encoder
        .fitFrom(data, startOrdinal)
        .widenFailure[FitError]
      encoded <- EitherT.fromEither[F](
        DataOperations
          .traverseNonEmpty(data)((_, example) =>
            encoder
              .encode(first.artifact, example.input)
              .left
              .map(_.widen[FitError])
          )
      )
      second <- transform
        .fitFrom(encoded, startOrdinal + encoder.stageCount)
        .widenFailure[FitError]
    yield
      val state = new FoldEncoderState(
        first.artifact,
        second.fitted
      )
      val lineage = PreparationLineage.sequence(
        context.stagePath,
        PreparationScopeTag.Reusable,
        Vector(first.audit.preparation, second.lineage)
      )
      context.composite(
        state,
        data,
        AlderComponents.composeFoldEncoder,
        lineage,
        first.audit.flattenedPreparationSequence ++
          second.fitted.audit.flattenedPreparationSequence,
        shape = AuditShape.FoldEncoderSequence
      )

  def encode(
      state: State,
      input: X
  ): Either[Failure[RunError], W] =
    encoder.encode(state.encoder, input) match
      case Left(failure) => Left(failure.widen[RunError])
      case Right(value) =>
        state.transform.artifact
          .run(value)
          .left
          .map(_.widen[RunError])

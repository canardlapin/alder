package alder.kernel

import cats.Monad

/** Target-blind preprocessing: cannot inspect targets or metadata because they
  * do not occur in its input. Fitting owns its training-replay preparation
  * (D5): replay failure surfaces inside this transform's FitError.
  */
trait Transform[F[_], X, Z]:
  type FitError
  type RunError
  type Fitted <: Pipe[X, RunError, Z]

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, X]
  )(using FitContext): FitResult[
    F,
    FitError,
    Prepared[Preparation.Reusable, U, Fitted, Z]
  ]

  /** Transform composition is closed (composition algebra row 2). The
    * singleton receiver type retains this stage's precise error members.
    */
  final def andThen[W, R <: Transform[F, Z, W]](right: R)(using
      Monad[F]
  ): ThenTransform[F, X, Z, W, this.type, R] =
    ThenTransform(this, right)

  /** Target-blind preparation may lawfully precede target-aware preparation. */
  final def andThen[
      Y,
      M,
      W,
      FM <: FeatureMap[F, Z, Y, M, W]
  ](featureMap: FM)(using
      Monad[F]
  ): ThenFeatureMap[F, X, Y, M, Z, W, this.type, FM] =
    ThenFeatureMap(this, featureMap)

  /** Target-blind preparation may feed a terminal learner directly. */
  final def learnWith[
      Y,
      M,
      P,
      L <: Learner[F, Z, Y, M, P]
  ](learner: L)(using
      Monad[F]
  ): LearnedWith[
    F,
    X,
    Y,
    M,
    Z,
    P,
    InputOnlyFeatureMap[F, X, Y, M, Z, this.type],
    L
  ] =
    new LearnedWith[
      F,
      X,
      Y,
      M,
      Z,
      P,
      InputOnlyFeatureMap[F, X, Y, M, Z, this.type],
      L
    ](
      FeatureMap.inputOnly[F, X, Y, M, Z, this.type](this),
      learner
    )

  /** Internal normalized-plan interpreter hook. Third-party transforms are
    * leaves by default; Alder's composition values override it to distribute
    * absolute stable ordinals without exposing layout machinery publicly.
    */
  private[alder] def fitFrom[U <: Use.Fit](
      data: NonEmptyData[U, X],
      startOrdinal: Int
  )(using context: FitContext): FitResult[
    F,
    FitError,
    Prepared[Preparation.Reusable, U, Fitted, Z]
  ] =
    fit(data)(using context.forChild(startOrdinal))

  private[alder] def stageCount: Int = 1

/** Sequential composition of two target-blind transforms. Knows nothing
  * algorithm-specific: only preparation scope, row identity, audit
  * composition, child contexts, error widening, and lineage.
  */
final class ThenTransform[
    F[_],
    X,
    Z,
    W,
    L <: Transform[F, X, Z],
    R <: Transform[F, Z, W]
](
    val left: L,
    val right: R
)(using Monad[F])
    extends Transform[F, X, W]:

  type FitError = left.FitError | right.FitError
  type RunError = left.RunError | right.RunError
  type Fitted = Pipe.Chain[
    X,
    left.RunError,
    Z,
    right.RunError,
    W,
    left.Fitted,
    right.Fitted
  ]

  override private[alder] def stageCount: Int =
    left.stageCount + right.stageCount

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, X]
  )(using context: FitContext): FitResult[
    F,
    FitError,
    Prepared[Preparation.Reusable, U, Fitted, W]
  ] = fitFrom(data, 0)

  override private[alder] def fitFrom[U <: Use.Fit](
      data: NonEmptyData[U, X],
      startOrdinal: Int
  )(using context: FitContext): FitResult[
    F,
    FitError,
    Prepared[Preparation.Reusable, U, Fitted, W]
  ] =
    for
      first <- left
        .fitFrom(data, startOrdinal)
        .widenFailure[FitError]
      second <- right
        .fitFrom(first.rows, startOrdinal + left.stageCount)
        .widenFailure[FitError]
    yield
      val fitted: Fitted =
        Pipe.Chain(first.fitted.artifact, second.fitted.artifact)
      val lineage = PreparationLineage.sequence(
        context.stagePath,
        PreparationScopeTag.Reusable,
        Vector(first.lineage, second.lineage)
      )
      val trained = context.composite(
        artifact = fitted,
        trainedOn = data,
        component = AlderComponents.composeTransform,
        preparation = lineage,
        children =
          first.fitted.audit.flattenedTransformSequence ++
            second.fitted.audit.flattenedTransformSequence,
        shape = AuditShape.TransformSequence
      )
      new Prepared(trained, second.rows, lineage)

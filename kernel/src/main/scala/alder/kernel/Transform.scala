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

extension [F[_], X, Z, L <: Transform[F, X, Z]](left: L)
  /** Transform composition is closed (composition algebra row 2). Top-level so
    * `import alder.kernel.*` brings it into scope. The concrete stage types L
    * and R are retained so composed error members stay precise at call sites.
    */
  def andThen[W, R <: Transform[F, Z, W]](right: R)(using
      Monad[F]
  ): ThenTransform[F, X, Z, W, L, R] =
    ThenTransform(left, right)

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
  type Fitted = Pipe.Chain[X, left.RunError, Z, right.RunError, W]

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, X]
  )(using context: FitContext): FitResult[
    F,
    FitError,
    Prepared[Preparation.Reusable, U, Fitted, W]
  ] =
    for
      first <- left
        .fit(data)(using context.forChild(0))
        .widenFailure[FitError]
      second <- right
        .fit(first.rows)(using context.forChild(1))
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
        data = data.fingerprint,
        component = AlderComponents.composeTransform,
        preparation = lineage,
        children = Vector(first.fitted.audit, second.fitted.audit)
      )
      new Prepared(trained, second.rows, lineage)

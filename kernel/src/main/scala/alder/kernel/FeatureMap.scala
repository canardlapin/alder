package alder.kernel

/** Leakage-aware supervised preprocessing. Its prepared rows are LearnerReady:
  * each row was prepared without its own target, so they may feed a terminal
  * learner but never another preprocessing stage through the safe API (D4).
  * The prepared rows are deliberately NOT required to equal replaying the
  * final fitted pipe.
  */
trait FeatureMap[F[_], X, Y, M, Z]:
  type FitError
  type RunError
  type Fitted <: Pipe[X, RunError, Z]

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]]
  )(using FitContext): FitResult[
    F,
    FitError,
    Prepared[Preparation.LearnerReady, U, Fitted, Example[Z, Y, M]]
  ]

// TODO(0.1): FeatureMap.inputOnly(transform) — lifts a Transform, preserving
// targets, metadata, and row identities; result scope is Reusable. Requires
// the internal projected-data view.
// TODO(0.1): FeatureMap.crossFitted(encoder, resampler) — the out-of-fold
// protocol. Requires Resampler from alder-data.

/** What a target-aware encoder must implement: fit a state, apply the state.
  * FeatureMap.crossFitted owns the fold protocol; there is deliberately no
  * public fit-all/encode-all shortcut.
  */
trait FoldEncoder[F[_], X, Y, M, Z]:
  type State
  type FitError
  type RunError

  def fit(
      data: NonEmptyData[Use.Train, Example[X, Y, M]]
  )(using FitContext): FitResult[F, FitError, State]

  def encode(state: State, input: X): Either[RunError, Z]

// TODO(0.1): encoder.andThen(transform): FoldEncoder — per-fold inner fits
// (D6, D17); the lawful route to target-encode-then-standardize.

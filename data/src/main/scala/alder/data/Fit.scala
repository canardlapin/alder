package alder.data

import alder.kernel.*
import cats.Id

/** Application-facing construction of a root fitting context.
  *
  * Component authors still consume `FitContext` directly. Applications can
  * use this helper to derive the schema fingerprint and select Alder's
  * deterministic numerical mode by default.
  */
object Fit:
  /** Creates the context for one logical fitting plan.
    *
    * @param seed
    *   root seed from which composed stage seeds are derived
    * @param plan
    *   stable application identity for the logical workflow
    * @param numericMode
    *   numerical determinism policy recorded in the audit
    */
  def context[A](
      seed: Seed,
      plan: String,
      numericMode: NumericMode = NumericMode.Deterministic
  )(using schema: Schema[A]): FitContext =
    context(seed, PlanFingerprint(plan), numericMode)

  def context[A](
      seed: Seed,
      plan: PlanFingerprint,
      numericMode: NumericMode
  )(using schema: Schema[A]): FitContext =
    FitContext.root(
      seed,
      plan,
      schema.fingerprint,
      numericMode
    )

  /** Fits a synchronous learner without requiring application code to install
    * a contextual value or unwrap `EitherT`.
    *
    * The learner's exact model and error types are preserved.
    */
  def learner[
      U <: Use.Fit,
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P]
  ](
      learner: L,
      data: NonEmptyData[U, Example[X, Y, M]],
      seed: Seed,
      plan: String,
      numericMode: NumericMode = NumericMode.Deterministic
  )(using Schema[X]): Either[
    Failure[learner.FitError],
    Trained[learner.Model]
  ] =
    learner
      .fit(data)(
        using context[X](seed, plan, numericMode)
      )
      .toEither

  /** Fits with an already policy-tagged plan identity. */
  def learner[
      U <: Use.Fit,
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P]
  ](
      learner: L,
      data: NonEmptyData[U, Example[X, Y, M]],
      seed: Seed,
      plan: PlanFingerprint
  )(using Schema[X]): Either[
    Failure[learner.FitError],
    Trained[learner.Model]
  ] =
    learner
      .fit(data)(
        using context[X](
          seed,
          plan,
          NumericMode.Deterministic
        )
      )
      .toEither

  /** Fits a synchronous target-blind transform with the same application
    * defaults as `learner`.
    */
  def transform[
      U <: Use.Fit,
      X,
      Z,
      T <: Transform[Id, X, Z]
  ](
      transform: T,
      data: NonEmptyData[U, X],
      seed: Seed,
      plan: String,
      numericMode: NumericMode = NumericMode.Deterministic
  )(using Schema[X]): Either[
    Failure[transform.FitError],
    Prepared[
      Preparation.Reusable,
      U,
      transform.Fitted,
      Z
    ]
  ] =
    transform
      .fit(data)(
        using context[X](seed, plan, numericMode)
      )
      .toEither

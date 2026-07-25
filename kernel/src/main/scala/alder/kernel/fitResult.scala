package alder.kernel

import cats.Functor
import cats.data.EitherT

/** The result of fitting: an effect around either a stage-attributed failure
  * or a fitted value.
  */
type FitResult[F[_], E, A] = EitherT[F, Failure[E], A]

extension [F[_], E, A](result: FitResult[F, E, A])
  /** Widen the error channel toward a composition union. Explicit because
    * EitherT is invariant; information loss is impossible (E2 >: E).
    */
  def widenFailure[E2 >: E](using Functor[F]): FitResult[F, E2, A] =
    result.leftMap(failure => failure.widen[E2])

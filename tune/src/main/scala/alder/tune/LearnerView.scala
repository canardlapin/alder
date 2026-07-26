package alder.tune

import alder.kernel.*
import cats.Functor

/** Capability-erased learner view used only where heterogeneous model families
  * must be iterated uniformly. Concrete learners retain their path-dependent
  * Model type everywhere else.
  */
trait LearnerView[F[_], X, Y, M, FitE, RunE, P]:
  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]]
  )(using FitContext): FitResult[F, FitE, Trained[Pipe[X, RunE, P]]]

extension [F[_], X, Y, M, P](
    learner: Learner[F, X, Y, M, P]
)
  def eraseModel(using
      functor: Functor[F]
  ): LearnerView[
    F,
    X,
    Y,
    M,
    learner.FitError,
    learner.RunError,
    P
  ] =
    new LearnerView[
      F,
      X,
      Y,
      M,
      learner.FitError,
      learner.RunError,
      P
    ]:
      def fit[U <: Use.Fit](
          data: NonEmptyData[U, Example[X, Y, M]]
      )(using context: FitContext)
          : FitResult[
            F,
            learner.FitError,
            Trained[Pipe[X, learner.RunError, P]]
          ] =
        learner.fit(data).map { trained =>
          val erased: Pipe[X, learner.RunError, P] = trained.artifact
          new Trained(erased, trained.audit)
        }

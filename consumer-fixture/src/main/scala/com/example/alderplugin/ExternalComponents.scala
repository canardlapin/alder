package com.example.alderplugin

import alder.kernel.*
import cats.Applicative
import cats.data.EitherT

/** External transform implemented outside the `alder` package namespace. */
final class AddConstant[F[_]](val amount: Double)(using Applicative[F])
    extends Transform.Leaf[F, Double, Double]:
  type FitError = Nothing
  type RunError = Nothing
  type Fitted = Pipe[Double, Nothing, Double]

  protected def descriptor: ComponentDescriptor =
    ComponentDescriptor(
      ComponentId("com.example.add-constant"),
      ComponentVersion("1"),
      AuditValue.record("amount" -> AuditValue.decimal(amount)),
      BackendFingerprint("example", "1", AuditValue.record())
    )

  protected def replayFailure(
      failure: Failure[RunError]
  ): Failure[FitError] =
    failure.widen[FitError]

  protected def fitPipe[U <: Use.Fit](
      data: NonEmptyData[U, Double]
  )(using FitContext): Either[Failure[FitError], Fitted] =
    val _ = data
    Right(Pipe.total[Double, Double](value => value + amount))

/** External learner implemented outside the `alder` package namespace. */
final class MeanLearner[F[_]](using Applicative[F])
    extends Learner[F, Double, Double, Unit, Double]:
  type FitError = Nothing
  type RunError = Nothing
  type Model = Pipe[Double, Nothing, Double]

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[Double, Double, Unit]]
  )(using context: FitContext): FitResult[F, FitError, Trained[Model]] =
    val (sum, count) = data.data.foldRows((0.0, 0L)) {
      case ((total, n), _, example) => (total + example.target, n + 1L)
    }
    val mean = sum / count.toDouble
    EitherT.right(
      Applicative[F].pure(
        context.complete(
          Pipe.total[Double, Double](_ => mean),
          data,
          ComponentDescriptor(
            ComponentId("com.example.mean-learner"),
            ComponentVersion("1"),
            AuditValue.record(),
            BackendFingerprint("example", "1", AuditValue.record())
          )
        )
      )
    )

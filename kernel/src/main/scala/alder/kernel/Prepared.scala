package alder.kernel

/** Phantom scope governing who may consume prepared training rows (D4).
  *
  * `Reusable` (no target information used) may feed another fitted stage or a
  * learner. `LearnerReady` (each row prepared without its own target, e.g.
  * cross-fitted) may feed a terminal learner only: fitting further
  * preprocessing on such rows reintroduces own-target leakage through pooled
  * statistics. Reusable extends LearnerReady because target-independence is
  * strictly stronger.
  */
sealed trait Preparation

object Preparation:
  sealed trait LearnerReady extends Preparation
  sealed trait Reusable extends LearnerReady

/** The hinge of the design: a fitted artifact together with the training rows
  * that are safe to pass downstream — which are NOT necessarily the replay of
  * the fitted artifact. The rows are a protocol resource, not a collection:
  * only Alder's combinators may consume them.
  */
final class Prepared[+S <: Preparation, +U <: Use.Fit, +A, +B] private[alder] (
    val fitted: Trained[A],
    private[alder] val rows: NonEmptyData[U, B],
    val lineage: PreparationLineage
):
  /** The fitted artifact applications use for serving.
    *
    * The full `fitted` value remains available when its audit is also needed.
    */
  def artifact: A = fitted.artifact

object Prepared:

  /** Correct-by-construction Reusable factory (D5): takes the fitted pipe and
    * the fitting data and performs the replay itself, so the input-only replay
    * law cannot be violated. Replay failure surfaces so the caller can embed
    * it in its FitError. Score-reuse shortcuts belong to alder.unsafe.spi.
    */
  private[alder] def replayed[U <: Use.Fit, E, X, Z, P <: Pipe[X, E, Z]](
      fitted: Trained[P],
      data: NonEmptyData[U, X],
      lineage: PreparationLineage
  ): Either[Failure[E], Prepared[Preparation.Reusable, U, P, Z]] =
    val pipe = fitted.artifact
    val replay = data.data
      .foldRows[Either[Failure[E], Vector[(RowId, Z)]]](Right(Vector.empty)) {
        case (Left(failure), _, _) => Left(failure)
        case (Right(acc), id, x)   => pipe.run(x).map(z => acc :+ (id, z))
      }
    replay.map { rows =>
      new Prepared(
        fitted,
        new NonEmptyData(
          RowVectorData(rows, data.fingerprint),
          data.refit
        ),
        lineage
      )
    }

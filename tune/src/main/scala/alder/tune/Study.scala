package alder.tune

import alder.kernel.*
import cats.Monad
import cats.syntax.all.*

/** Search procedure recorded in a completed study audit. */
enum SearchStrategy derives CanEqual:
  case Grid(continuousPoints: PositiveInt)
  case Random(trials: PositiveInt)

/** Whether a smaller or larger finite objective is preferred. */
enum ObjectiveDirection derives CanEqual:
  case Minimize
  case Maximize

/** Failure of one candidate evaluation. */
enum TrialFailure derives CanEqual:
  case Evaluation(description: String)
  case NonFiniteObjective(value: Double)

/** One configuration and either its finite objective or evaluation failure. */
final case class Trial[C](
    config: C,
    objective: Either[TrialFailure, Double]
)

/** Reproducibility and completion summary for a study. */
final case class StudyAudit(
    strategy: SearchStrategy,
    objectiveDirection: ObjectiveDirection,
    seed: Option[Seed],
    candidateCount: Int,
    successfulTrials: Int
)

/** Successful study result, including every attempted trial.
  *
  * Ties preserve candidate order: the first candidate with the best objective
  * is selected.
  */
final class Selection[C] private[tune] (
    val best: C,
    val trials: Vector[Trial[C]],
    val audit: StudyAudit
)

/** Failure to select a configuration. */
enum StudyError derives CanEqual:
  case NoSuccessfulTrial(failures: Vector[TrialFailure])

/** A study evaluates only Train data and returns a selected configuration.
  * Fitted artifacts never cross this boundary; callers perform final refitting
  * through their concrete family(selection.best) on receipt-authorized Refit
  * data, preserving the path-dependent Model type.
  */
final class Study[F[_], C, A] private (
    candidates: Vector[C],
    strategy: SearchStrategy,
    objectiveDirection: ObjectiveDirection,
    seed: Option[Seed],
    evaluate: (C, NonEmptyData[Use.Train, A]) =>
      F[Either[TrialFailure, Double]]
)(using monad: Monad[F]):

  /** Evaluates every candidate on the supplied training data and selects the
    * best finite objective.
    */
  def run(
      data: NonEmptyData[Use.Train, A]
  ): F[Either[StudyError, Selection[C]]] =
    candidates
      .traverse(config =>
        evaluate(config, data).map { objective =>
          val validated = objective.flatMap { value =>
            if value.isFinite then Right(value)
            else Left(TrialFailure.NonFiniteObjective(value))
          }
          Trial(config, validated)
        }
      )
      .map(select)

  private def select(
      trials: Vector[Trial[C]]
  ): Either[StudyError, Selection[C]] =
    val successful = trials.collect {
      case trial @ Trial(_, Right(objective)) =>
        (trial, objective)
    }
    successful.reduceOption { (best, candidate) =>
      val candidateIsBetter = objectiveDirection match
        case ObjectiveDirection.Minimize =>
          candidate._2 < best._2
        case ObjectiveDirection.Maximize =>
          candidate._2 > best._2
      if candidateIsBetter then candidate
      else best
    } match
      case Some((best, _)) =>
        Right(
          new Selection(
            best.config,
            trials,
            StudyAudit(
              strategy,
              objectiveDirection,
              seed,
              trials.length,
              successful.length
            )
          )
        )
      case None =>
        Left(
          StudyError.NoSuccessfulTrial(
            trials.collect {
              case Trial(_, Left(failure)) => failure
            }
          )
        )

object Study:
  /** Builds a deterministic grid study.
    *
    * The evaluation callback can inspect only `Use.Train` data. Candidate
    * failures are retained and do not prevent later candidates from running.
    */
  def grid[F[_], C, A](
      space: Space[C],
      strategy: GridStrategy,
      objectiveDirection: ObjectiveDirection
  )(
      evaluate: (C, NonEmptyData[Use.Train, A]) =>
        F[Either[TrialFailure, Double]]
  )(using Monad[F]): Study[F, C, A] =
    new Study(
      Grid.candidates(space, strategy),
      SearchStrategy.Grid(strategy.continuousPoints),
      objectiveDirection,
      None,
      evaluate
    )

  /** Builds a reproducible random-search study.
    *
    * The explicit seed determines candidate generation and is recorded in the
    * resulting audit.
    */
  def random[F[_], C, A](
      space: Space[C],
      trials: PositiveInt,
      seed: Seed,
      objectiveDirection: ObjectiveDirection
  )(
      evaluate: (C, NonEmptyData[Use.Train, A]) =>
        F[Either[TrialFailure, Double]]
  )(using Monad[F]): Study[F, C, A] =
    new Study(
      RandomSearch.candidates(space, trials, seed),
      SearchStrategy.Random(trials),
      objectiveDirection,
      Some(seed),
      evaluate
    )

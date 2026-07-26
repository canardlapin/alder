package alder.tune

import alder.kernel.*
import cats.Monad
import cats.syntax.all.*

enum SearchStrategy derives CanEqual:
  case Grid(continuousPoints: PositiveInt)
  case Random(trials: PositiveInt)

enum ObjectiveDirection derives CanEqual:
  case Minimize
  case Maximize

enum TrialFailure derives CanEqual:
  case Evaluation(description: String)
  case NonFiniteObjective(value: Double)

final case class Trial[C](
    config: C,
    objective: Either[TrialFailure, Double]
)

final case class StudyAudit(
    strategy: SearchStrategy,
    objectiveDirection: ObjectiveDirection,
    seed: Option[Seed],
    candidateCount: Int,
    successfulTrials: Int
)

final class Selection[C] private[tune] (
    val best: C,
    val trials: Vector[Trial[C]],
    val audit: StudyAudit
)

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

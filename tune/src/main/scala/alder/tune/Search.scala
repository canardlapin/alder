package alder.tune

import alder.data.*
import alder.kernel.*
import alder.metrics.*
import cats.{Id, Monad}
import cats.syntax.all.*

/** One fold outcome inside a cross-validated candidate evaluation.
  *
  * Fold models are discarded after scoring; only the score or failure remains.
  */
enum FoldScore[+E, +S] derives CanEqual:
  case Scored(fold: Int, score: S)
  case Failed(fold: Int, failure: TrialFailure[E])

/** One configuration evaluated across every resampling fold. */
final case class CrossValidatedTrial[C, +E, S](
    config: C,
    folds: Vector[FoldScore[E, S]],
    objective: Either[TrialFailure[E], Double]
)

/** Successful cross-validated search over typed configurations.
  *
  * Reconstruct the concrete learner with the caller's `family(result.best)`.
  * Fitted fold models are never retained.
  */
final class CrossValidatedResult[C, +E, S] private[tune] (
    val best: C,
    val trials: Vector[CrossValidatedTrial[C, E, S]],
    val audit: StudyAudit,
    val assignment: DataFingerprint,
    val resampler: ResamplerFingerprint
)

/** Failures of cross-validated search before or during Study selection. */
enum SearchError[+E] derives CanEqual:
  case Resampling(error: DataError)
  case Study(error: StudyError[E])

/** Typed causes retained inside cross-validated fold evaluation. */
enum FoldEvaluationError[+FitE, +RunE] derives CanEqual:
  case EmptyFolds()
  case Fit(failure: Failure[FitE])
  case Predict(failure: Failure[RunE])
  case Metric(error: MetricError)

/** High-level interpreter that expands cross-validated search into Study. */
object Search:

  /** Grid search with complete-resampler cross-validation.
    *
    * For each candidate the family learner is fitted on every analysis fold,
    * scored on the complementary assessment fold, and discarded. The mean of
    * finite fold objectives is the Study objective. Selection uses the same
    * first-best-tie policy as [[Study]].
    */
  def crossValidatedGrid[
      F[_],
      C,
      X,
      Y,
      M,
      P,
      S,
      L <: Learner[F, X, Y, M, P]
  ](
      space: Space[C],
      strategy: GridStrategy,
      resampler: CompleteResampler[Example[X, Y, M]],
      family: C => L,
      metric: ObjectiveMetric[Scored[Y, P, M], S],
      objective: S => Double,
      seed: Seed,
      plan: PlanFingerprint
  )(using
      Monad[F],
      Schema[X]
  ): CrossValidatedSearch[F, C, X, Y, M, P, S, L] =
    new CrossValidatedSearch(
      SearchStrategy.Grid(strategy.continuousPoints),
      Grid.candidates(space, strategy),
      resampler,
      family,
      metric,
      objective,
      seed,
      plan,
      None
    )

  /** Random search with complete-resampler cross-validation. */
  def crossValidatedRandom[
      F[_],
      C,
      X,
      Y,
      M,
      P,
      S,
      L <: Learner[F, X, Y, M, P]
  ](
      space: Space[C],
      trials: PositiveInt,
      resampler: CompleteResampler[Example[X, Y, M]],
      family: C => L,
      metric: ObjectiveMetric[Scored[Y, P, M], S],
      objective: S => Double,
      seed: Seed,
      plan: PlanFingerprint
  )(using
      Monad[F],
      Schema[X]
  ): CrossValidatedSearch[F, C, X, Y, M, P, S, L] =
    new CrossValidatedSearch(
      SearchStrategy.Random(trials),
      RandomSearch.candidates(space, trials, seed),
      resampler,
      family,
      metric,
      objective,
      seed,
      plan,
      Some(seed)
    )

  /** Synchronous grid convenience over `cats.Id`. */
  def crossValidatedGridSync[
      C,
      X,
      Y,
      M,
      P,
      S,
      L <: Learner[Id, X, Y, M, P]
  ](
      space: Space[C],
      strategy: GridStrategy,
      resampler: CompleteResampler[Example[X, Y, M]],
      family: C => L,
      metric: ObjectiveMetric[Scored[Y, P, M], S],
      objective: S => Double,
      seed: Seed,
      plan: PlanFingerprint
  )(using Schema[X]): CrossValidatedSearch[Id, C, X, Y, M, P, S, L] =
    crossValidatedGrid(
      space,
      strategy,
      resampler,
      family,
      metric,
      objective,
      seed,
      plan
    )

final class CrossValidatedSearch[
    F[_],
    C,
    X,
    Y,
    M,
    P,
    S,
    L <: Learner[F, X, Y, M, P]
] private[tune] (
    strategy: SearchStrategy,
    candidates: Vector[C],
    resampler: CompleteResampler[Example[X, Y, M]],
    family: C => L,
    metric: ObjectiveMetric[Scored[Y, P, M], S],
    objective: S => Double,
    seed: Seed,
    plan: PlanFingerprint,
    studySeed: Option[Seed]
)(using monad: Monad[F], schema: Schema[X]):
  private type EvalE = FoldEvaluationError[Any, Any]

  /** Runs cross-validated search on Train data and returns the best config. */
  def run(
      data: NonEmptyData[Use.Train, Example[X, Y, M]]
  ): F[Either[SearchError[EvalE], CrossValidatedResult[C, EvalE, S]]] =
    resampler.split(data, seed) match
      case Left(error) =>
        monad.pure(Left(SearchError.Resampling(error)))
      case Right(planFolds) =>
        candidates
          .traverse(config => evaluateCandidate(config, planFolds))
          .map { trials =>
            val studyTrials =
              trials.map(trial => Trial(trial.config, trial.objective))
            select(studyTrials) match
              case Left(error) => Left(SearchError.Study(error))
              case Right(selection) =>
                Right(
                  new CrossValidatedResult(
                    selection.best,
                    trials,
                    selection.audit,
                    planFolds.assignment,
                    planFolds.resampler
                  )
                )
          }

  private def select(
      trials: Vector[Trial[C, EvalE]]
  ): Either[StudyError[EvalE], Selection[C, EvalE]] =
    val successful = trials.collect {
      case trial @ Trial(_, Right(value)) => (trial, value)
    }
    successful.reduceOption { (best, candidate) =>
      val candidateIsBetter = metric.direction match
        case ObjectiveDirection.Minimize =>
          candidate._2 < best._2
        case ObjectiveDirection.Maximize =>
          candidate._2 > best._2
      if candidateIsBetter then candidate else best
    } match
      case Some((best, _)) =>
        Right(
          new Selection(
            best.config,
            trials,
            StudyAudit(
              strategy,
              metric.direction,
              studySeed,
              trials.length,
              successful.length
            )
          )
        )
      case None =>
        Left(
          StudyError.NoSuccessfulTrial(
            trials.collect { case Trial(_, Left(failure)) => failure }
          )
        )

  private def evaluateCandidate(
      config: C,
      planFolds: ResamplingPlan[Use.Train, Example[X, Y, M]]
  ): F[CrossValidatedTrial[C, EvalE, S]] =
    val learner = family(config)
    planFolds.folds
      .traverse(fold => scoreFold(learner, fold))
      .map { foldScores =>
        val objectives = foldScores.collect {
          case FoldScore.Scored(_, score) => objective(score)
        }
        val foldFailure = foldScores.collectFirst {
          case FoldScore.Failed(_, failure) => failure
        }
        val aggregated: Either[TrialFailure[EvalE], Double] =
          foldFailure match
            case Some(failure) => Left(failure)
            case None =>
              if objectives.isEmpty then
                Left(
                  TrialFailure.Evaluation(FoldEvaluationError.EmptyFolds())
                )
              else
                val mean = objectives.sum / objectives.length.toDouble
                if mean.isFinite then Right(mean)
                else Left(TrialFailure.NonFiniteObjective(mean))
        CrossValidatedTrial(config, foldScores, aggregated)
      }

  private def scoreFold(
      learner: L,
      fold: ResamplingFold[Use.Train, Example[X, Y, M]]
  ): F[FoldScore[EvalE, S]] =
    learner
      .fit(fold.analysis)(
        using Fit.context[X](seed, plan, NumericMode.Deterministic)
      )
      .value
      .map {
        case Left(failure) =>
          FoldScore.Failed(
            fold.index,
            TrialFailure.Evaluation(
              FoldEvaluationError.Fit(failure.asInstanceOf[Failure[Any]])
            )
          )
        case Right(trained) =>
          scoreAssessment(learner, fold, trained) match
            case Left(failure) =>
              FoldScore.Failed(fold.index, failure)
            case Right(score) =>
              FoldScore.Scored(fold.index, score)
      }

  private def scoreAssessment(
      learner: L,
      fold: ResamplingFold[Use.Train, Example[X, Y, M]],
      trained: Trained[learner.Model]
  ): Either[TrialFailure[EvalE], S] =
    val pipe = trained.artifact
    val builder = Vector.newBuilder[Scored[Y, P, M]]
    val predictionFailed =
      fold.assessment.data.foldRows[Option[Failure[learner.RunError]]](None) {
        case (Some(failure), _, _) => Some(failure)
        case (None, _, example) =>
          pipe.run(example.input) match
            case Left(failure) => Some(failure)
            case Right(prediction) =>
              builder += Scored(example.target, prediction, example.meta)
              None
      }
    predictionFailed match
      case Some(failure) =>
        Left(
          TrialFailure.Evaluation(
            FoldEvaluationError.Predict(
              failure.asInstanceOf[Failure[Any]]
            )
          )
        )
      case None =>
        val scored = builder.result()
        val accumulated =
          scored.foldLeft(metric.accumulator.empty) { (current, value) =>
            metric.accumulator.combine(current, metric.observe(value))
          }
        metric
          .finish(accumulated)
          .left
          .map(error =>
            TrialFailure.Evaluation(FoldEvaluationError.Metric(error))
          )

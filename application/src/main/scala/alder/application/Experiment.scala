package alder.application

import alder.data.*
import alder.kernel.*
import alder.metrics.*
import cats.Id

/** Route evidence retained through every shared experiment state. */
sealed trait ExperimentRoute

sealed trait ValidationCapableRoute extends ExperimentRoute

sealed trait ValidationRoute extends ValidationCapableRoute
object ValidationRoute extends ValidationRoute

sealed trait TrainValidationTestRoute extends ValidationCapableRoute
object TrainValidationTestRoute extends TrainValidationTestRoute

sealed trait PrecommittedHoldoutRoute extends ExperimentRoute
object PrecommittedHoldoutRoute extends PrecommittedHoldoutRoute

/** Lifecycle-phase markers retained beside exact component failures. */
enum DataPhase derives CanEqual:
  case Source

enum SplitPhase derives CanEqual:
  case Partition

enum FitPhase derives CanEqual:
  case Candidate
  case SelectedRefit
  case DeploymentRefit

enum EvaluationPhase derives CanEqual:
  case Validation
  case Test
  case PrecommittedTest

enum SelectionPhase derives CanEqual:
  case Select

enum RefitPhase derives CanEqual:
  case SelectedPromotion
  case DeploymentPromotion

enum ExperimentDefinitionError derives CanEqual:
  case EmptySource

enum SelectionError derives CanEqual:
  case ReportingMetricCannotSelect

/** Typed experiment failure retaining phase plus exact underlying cause. */
enum ExperimentFailure[+FitE, +RunE] derives CanEqual:
  case Definition(error: ExperimentDefinitionError)
  case Data(phase: DataPhase, error: DataError | RefitError)
  case Split(phase: SplitPhase, error: DataError)
  case Fit(phase: FitPhase, failure: Failure[FitE])
  case Predict(phase: EvaluationPhase, error: EvaluationError[RunE])
  case Metric(phase: EvaluationPhase, error: MetricError)
  case Select(phase: SelectionPhase, error: SelectionError)
  case Refit(phase: RefitPhase, error: ApplicationRefitError | RefitError)

object Experiment:

  export ExperimentRoutes.{
    trainValidationTest,
    precommitted,
    TVTDefined,
    TVTPartitioned,
    TVTCandidateFitted,
    TVTValidated,
    TVTSelected,
    TVTRefitted,
    TVTTested,
    PrecommittedDefined,
    PrecommittedPartitioned,
    PrecommittedCandidateFitted,
    PrecommittedTested,
    DeploymentRefitted
  }

  /** Validation-route experiment over an exact learner and metric. */
  def validation[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      S,
      Mt <: Metric[Scored[Y, P, M], S]
  ](
      data: Data[Use.Unsplit, Example[X, Y, M]],
      specification: ValidationSpec,
      seed: Seed,
      plan: PlanFingerprint,
      learner: L,
      metric: Mt
  )(using Schema[X]): Defined[
    ValidationRoute.type,
    X,
    Y,
    M,
    P,
    L,
    Mt,
    S
  ] =
    new Defined(
      ValidationRoute,
      data,
      specification,
      seed,
      plan,
      learner,
      metric
    )

  def validation[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      S,
      Mt <: Metric[Scored[Y, P, M], S]
  ](
      data: Data[Use.Unsplit, Example[X, Y, M]],
      specification: ValidationSpec,
      seed: Seed,
      plan: String,
      learner: L,
      metric: Mt
  )(using Schema[X]): Defined[
    ValidationRoute.type,
    X,
    Y,
    M,
    P,
    L,
    Mt,
    S
  ] =
    validation(
      data,
      specification,
      seed,
      PlanFingerprint.external(plan),
      learner,
      metric
    )

  def validation[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      S,
      Mt <: Metric[Scored[Y, P, M], S]
  ](
      data: Data[Use.Unsplit, Example[X, Y, M]],
      specification: ValidationSpec,
      seed: Seed,
      plan: PlanFingerprint,
      blueprint: Blueprint.Complete[Id, X, Y, M, P, L],
      metric: Mt
  )(using Schema[X]): Defined[
    ValidationRoute.type,
    X,
    Y,
    M,
    P,
    L,
    Mt,
    S
  ] =
    validation(data, specification, seed, plan, blueprint.learner, metric)

  def validation[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      S,
      Mt <: Metric[Scored[Y, P, M], S]
  ](
      data: Data[Use.Unsplit, Example[X, Y, M]],
      specification: ValidationSpec,
      seed: Seed,
      plan: String,
      blueprint: Blueprint.Complete[Id, X, Y, M, P, L],
      metric: Mt
  )(using Schema[X]): Defined[
    ValidationRoute.type,
    X,
    Y,
    M,
    P,
    L,
    Mt,
    S
  ] =
    validation(
      data,
      specification,
      seed,
      PlanFingerprint.external(plan),
      blueprint,
      metric
    )

  /** Defined experiment before splitting. */
  final class Defined[
      R <: ExperimentRoute,
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      Mt <: Metric[Scored[Y, P, M], S],
      S
  ] private[application] (
      val route: R,
      val data: Data[Use.Unsplit, Example[X, Y, M]],
      private val specification: ValidationSpec,
      val seed: Seed,
      val plan: PlanFingerprint,
      val learner: L,
      val metric: Mt
  )(using schema: Schema[X]):
    private val phases = PhaseSeeds(seed, plan)

    def partition(using
        ev: R =:= ValidationRoute.type
    ): Either[
      ExperimentFailure[learner.FitError, learner.RunError],
      Partitioned[ValidationRoute.type, X, Y, M, P, L, Mt, S]
    ] =
      val _ = ev
      if data.size <= 0L then
        Left(ExperimentFailure.Definition(ExperimentDefinitionError.EmptySource))
      else
        Split
          .validation(data, specification, phases.split)
          .left
          .map(error =>
            ExperimentFailure.Split(SplitPhase.Partition, error)
          )
          .map(split =>
            new Partitioned(
              ValidationRoute,
              learner,
              metric,
              plan,
              phases,
              split
            )
          )

    /** Partition, fit the candidate, and score validation in one step. */
    def run(using
        ev: R =:= ValidationRoute.type
    ): Either[
      ExperimentFailure[learner.FitError, learner.RunError],
      Validated[ValidationRoute.type, X, Y, M, P, L, Mt, S]
    ] =
      val _ = ev
      partition match
        case Left(error) => Left(error)
        case Right(partitioned) =>
          partitioned.fitCandidate match
            case Left(error) =>
              Left(
                error.asInstanceOf[
                  ExperimentFailure[learner.FitError, learner.RunError]
                ]
              )
            case Right(fitted) =>
              fitted.validate match
                case Left(error) =>
                  Left(
                    error.asInstanceOf[
                      ExperimentFailure[learner.FitError, learner.RunError]
                    ]
                  )
                case Right(validated) => Right(validated)

  final class Partitioned[
      R <: ExperimentRoute,
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      Mt <: Metric[Scored[Y, P, M], S],
      S
  ] private[application] (
      val route: R,
      val learner: L,
      val metric: Mt,
      val plan: PlanFingerprint,
      private val phases: PhaseSeeds,
      val split: ValidationSplit[Example[X, Y, M]]
  )(using Schema[X]):
    def fitCandidate(using
        ev: R =:= ValidationRoute.type
    ): Either[
      ExperimentFailure[learner.FitError, learner.RunError],
      CandidateFitted[ValidationRoute.type, X, Y, M, P, L, Mt, S]
    ] =
      val _ = ev
      Fit
        .learner(
          learner,
          split.train,
          seed = phases.candidateFit,
          plan = plan
        )
        .left
        .map(failure =>
          ExperimentFailure.Fit(FitPhase.Candidate, failure)
        )
        .map(trained =>
          new CandidateFitted(
            ValidationRoute,
            learner,
            metric,
            plan,
            phases,
            split,
            trained
          )
        )

  final class CandidateFitted[
      R <: ValidationCapableRoute,
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      Mt <: Metric[Scored[Y, P, M], S],
      S
  ] private[application] (
      val route: R,
      val learner: L,
      val metric: Mt,
      val plan: PlanFingerprint,
      private val phases: PhaseSeeds,
      val split: ValidationSplit[Example[X, Y, M]],
      val trained: Trained[learner.Model]
  )(using Schema[X]):
    def validate: Either[
      ExperimentFailure[learner.FitError, learner.RunError],
      Validated[R, X, Y, M, P, L, Mt, S]
    ] =
      EvaluationSources
        .validation(split.train, split.validation.data)
        .left
        .map(error =>
          ExperimentFailure.Data(DataPhase.Source, error)
        )
        .flatMap { sources =>
          Evaluation.scored(trained, sources, metric) match
            case Left(ScoredEvaluationError.Prediction(error)) =>
              Left(
                ExperimentFailure.Predict(
                  EvaluationPhase.Validation,
                  error
                )
              )
            case Left(ScoredEvaluationError.Metric(error)) =>
              Left(
                ExperimentFailure.Metric(
                  EvaluationPhase.Validation,
                  error
                )
              )
            case Right(evaluation) =>
              Right(
                new Validated(
                  route,
                  learner,
                  metric,
                  plan,
                  phases,
                  split,
                  trained,
                  evaluation
                )
              )
        }

  final class Validated[
      R <: ValidationCapableRoute,
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      Mt <: Metric[Scored[Y, P, M], S],
      S
  ] private[application] (
      val route: R,
      val learner: L,
      val metric: Mt,
      val plan: PlanFingerprint,
      private val phases: PhaseSeeds,
      val split: ValidationSplit[Example[X, Y, M]],
      val trained: Trained[learner.Model],
      val evaluation: ScoredEvaluation[
        Use.Validation,
        X,
        Y,
        M,
        P,
        S,
        Mt
      ]
  )(using Schema[X]):
    def score: S = evaluation.score
    def predictions: NonEmptyData[Use.Validation, Scored[Y, P, M]] =
      evaluation.scored
    def model: Trained[learner.Model] = trained
    def audit: Audit = trained.audit

    def select(
        policy: SelectionPolicy.SingleCandidate.type
    )(using
        evidence: Mt <:< ObjectiveMetric[Scored[Y, P, M], S]
    ): Selected[R, X, Y, M, P, L, Mt, S] =
      val _ = evidence
      val objectiveEvaluation =
        evaluation.asInstanceOf[
          ScoredEvaluation[
            Use.Validation,
            X,
            Y,
            M,
            P,
            S,
            ObjectiveMetric[Scored[Y, P, M], S]
          ]
        ]
      val candidate =
        new ValidatedCandidate(
          learner,
          trained,
          objectiveEvaluation
        )
      val receipt = candidate.select(policy)
      new Selected(
        route,
        learner,
        metric,
        plan,
        phases,
        split,
        trained,
        evaluation,
        receipt.asInstanceOf[SelectionReceipt[L, Mt, S]]
      )

  final class Selected[
      R <: ValidationCapableRoute,
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      Mt <: Metric[Scored[Y, P, M], S],
      S
  ] private[application] (
      val route: R,
      val learner: L,
      val metric: Mt,
      val plan: PlanFingerprint,
      private val phases: PhaseSeeds,
      val split: ValidationSplit[Example[X, Y, M]],
      val trained: Trained[learner.Model],
      val evaluation: ScoredEvaluation[
        Use.Validation,
        X,
        Y,
        M,
        P,
        S,
        Mt
      ],
      val receipt: SelectionReceipt[L, Mt, S]
  )(using Schema[X]):
    def refit: Either[
      ExperimentFailure[learner.FitError, learner.RunError],
      Refitted[R, X, Y, M, P, L, Mt, S]
    ] =
      Refit
        .after(receipt)
        .from(evaluation.allObserved)
        .left
        .map(error =>
          ExperimentFailure.Refit(RefitPhase.SelectedPromotion, error)
        )
        .flatMap { refitData =>
          Fit
            .learner(
              learner,
              refitData,
              seed = phases.selectedRefit,
              plan = plan
            )
            .left
            .map(failure =>
              ExperimentFailure.Fit(FitPhase.SelectedRefit, failure)
            )
            .map(refitted =>
              new Refitted(
                route,
                learner,
                metric,
                plan,
                phases,
                split,
                evaluation,
                receipt,
                refitted
              )
            )
        }

  final class Refitted[
      R <: ValidationCapableRoute,
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      Mt <: Metric[Scored[Y, P, M], S],
      S
  ] private[application] (
      val route: R,
      val learner: L,
      val metric: Mt,
      val plan: PlanFingerprint,
      private val phases: PhaseSeeds,
      val split: ValidationSplit[Example[X, Y, M]],
      val evaluation: ScoredEvaluation[
        Use.Validation,
        X,
        Y,
        M,
        P,
        S,
        Mt
      ],
      val receipt: SelectionReceipt[L, Mt, S],
      val trained: Trained[learner.Model]
  ):
    def model: Trained[learner.Model] = trained
    def audit: Audit = trained.audit
    // Retained for TrainValidationTestRoute.test / deploymentRefit.
    private[application] def phaseSeeds: PhaseSeeds = phases

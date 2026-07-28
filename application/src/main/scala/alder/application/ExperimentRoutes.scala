package alder.application

import alder.data.*
import alder.kernel.*
import alder.metrics.*
import cats.Id

/** Train/validation/test and precommitted holdout Experiment routes. */
object ExperimentRoutes:

  def trainValidationTest[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      S,
      Mt <: Metric[Scored[Y, P, M], S]
  ](
      data: Data[Use.Unsplit, Example[X, Y, M]],
      specification: TrainValidationTestSpec,
      seed: Seed,
      plan: PlanFingerprint,
      learner: L,
      metric: Mt
  )(using Schema[X]): TVTDefined[X, Y, M, P, L, Mt, S] =
    new TVTDefined(data, specification, seed, plan, learner, metric)

  def trainValidationTest[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      S,
      Mt <: Metric[Scored[Y, P, M], S]
  ](
      data: Data[Use.Unsplit, Example[X, Y, M]],
      specification: TrainValidationTestSpec,
      seed: Seed,
      plan: String,
      learner: L,
      metric: Mt
  )(using Schema[X]): TVTDefined[X, Y, M, P, L, Mt, S] =
    trainValidationTest(
      data,
      specification,
      seed,
      PlanFingerprint.external(plan),
      learner,
      metric
    )

  def trainValidationTest[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      S,
      Mt <: Metric[Scored[Y, P, M], S]
  ](
      data: Data[Use.Unsplit, Example[X, Y, M]],
      specification: TrainValidationTestSpec,
      seed: Seed,
      plan: PlanFingerprint,
      blueprint: Blueprint.Complete[Id, X, Y, M, P, L],
      metric: Mt
  )(using Schema[X]): TVTDefined[X, Y, M, P, L, Mt, S] =
    trainValidationTest(
      data,
      specification,
      seed,
      plan,
      blueprint.learner,
      metric
    )

  def precommitted[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      S,
      Mt <: Metric[Scored[Y, P, M], S]
  ](
      data: Data[Use.Unsplit, Example[X, Y, M]],
      specification: HoldoutSpec,
      seed: Seed,
      plan: PlanFingerprint,
      learner: L,
      metric: Mt
  )(using Schema[X]): PrecommittedDefined[X, Y, M, P, L, Mt, S] =
    new PrecommittedDefined(
      data,
      specification,
      seed,
      plan,
      learner,
      metric
    )

  def precommitted[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      S,
      Mt <: Metric[Scored[Y, P, M], S]
  ](
      data: Data[Use.Unsplit, Example[X, Y, M]],
      specification: HoldoutSpec,
      seed: Seed,
      plan: String,
      learner: L,
      metric: Mt
  )(using Schema[X]): PrecommittedDefined[X, Y, M, P, L, Mt, S] =
    precommitted(
      data,
      specification,
      seed,
      PlanFingerprint.external(plan),
      learner,
      metric
    )

  def precommitted[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      S,
      Mt <: Metric[Scored[Y, P, M], S]
  ](
      data: Data[Use.Unsplit, Example[X, Y, M]],
      specification: HoldoutSpec,
      seed: Seed,
      plan: PlanFingerprint,
      blueprint: Blueprint.Complete[Id, X, Y, M, P, L],
      metric: Mt
  )(using Schema[X]): PrecommittedDefined[X, Y, M, P, L, Mt, S] =
    precommitted(
      data,
      specification,
      seed,
      plan,
      blueprint.learner,
      metric
    )

  final class TVTDefined[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      Mt <: Metric[Scored[Y, P, M], S],
      S
  ] private[application] (
      val data: Data[Use.Unsplit, Example[X, Y, M]],
      private val specification: TrainValidationTestSpec,
      val seed: Seed,
      val plan: PlanFingerprint,
      val learner: L,
      val metric: Mt
  )(using Schema[X]):
    val route: TrainValidationTestRoute.type = TrainValidationTestRoute
    private val phases = PhaseSeeds(seed, plan)

    def partition: Either[
      ExperimentFailure[learner.FitError, learner.RunError],
      TVTPartitioned[X, Y, M, P, L, Mt, S]
    ] =
      if data.size <= 0L then
        Left(ExperimentFailure.Definition(ExperimentDefinitionError.EmptySource))
      else
        Split
          .trainValidationTest(data, specification, phases.split)
          .left
          .map(error =>
            ExperimentFailure.Split(SplitPhase.Partition, error)
          )
          .map(split =>
            new TVTPartitioned(learner, metric, plan, phases, split)
          )

    def runToTested(using
        Mt <:< ObjectiveMetric[Scored[Y, P, M], S]
    ): Either[
      ExperimentFailure[learner.FitError, learner.RunError],
      TVTTested[X, Y, M, P, L, Mt, S]
    ] =
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
                case Right(validated) =>
                  validated
                    .select(SingleCandidate)
                    .refit
                    .flatMap(_.test) match
                    case Left(error) =>
                      Left(
                        error.asInstanceOf[
                          ExperimentFailure[
                            learner.FitError,
                            learner.RunError
                          ]
                        ]
                      )
                    case Right(tested) => Right(tested)

  final class TVTPartitioned[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      Mt <: Metric[Scored[Y, P, M], S],
      S
  ] private[application] (
      val learner: L,
      val metric: Mt,
      val plan: PlanFingerprint,
      private val phases: PhaseSeeds,
      val split: TrainValidationTestSplit[Example[X, Y, M]]
  )(using Schema[X]):
    val route: TrainValidationTestRoute.type = TrainValidationTestRoute

    def fitCandidate: Either[
      ExperimentFailure[learner.FitError, learner.RunError],
      TVTCandidateFitted[X, Y, M, P, L, Mt, S]
    ] =
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
          new TVTCandidateFitted(
            learner,
            metric,
            plan,
            phases,
            split,
            trained
          )
        )

  final class TVTCandidateFitted[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      Mt <: Metric[Scored[Y, P, M], S],
      S
  ] private[application] (
      val learner: L,
      val metric: Mt,
      val plan: PlanFingerprint,
      private val phases: PhaseSeeds,
      val split: TrainValidationTestSplit[Example[X, Y, M]],
      val trained: Trained[learner.Model]
  )(using Schema[X]):
    val route: TrainValidationTestRoute.type = TrainValidationTestRoute

    def validate: Either[
      ExperimentFailure[learner.FitError, learner.RunError],
      TVTValidated[X, Y, M, P, L, Mt, S]
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
                new TVTValidated(
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

  final class TVTValidated[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      Mt <: Metric[Scored[Y, P, M], S],
      S
  ] private[application] (
      val learner: L,
      val metric: Mt,
      val plan: PlanFingerprint,
      private val phases: PhaseSeeds,
      val split: TrainValidationTestSplit[Example[X, Y, M]],
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
    val route: TrainValidationTestRoute.type = TrainValidationTestRoute
    def score: S = evaluation.score

    def select(
        policy: SelectionPolicy.SingleCandidate.type
    )(using
        evidence: Mt <:< ObjectiveMetric[Scored[Y, P, M], S]
    ): TVTSelected[X, Y, M, P, L, Mt, S] =
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
      val receipt =
        new ValidatedCandidate(learner, trained, objectiveEvaluation)
          .select(policy)
      new TVTSelected(
        learner,
        metric,
        plan,
        phases,
        split,
        trained,
        evaluation,
        receipt.asInstanceOf[SelectionReceipt[L, Mt, S]]
      )

  final class TVTSelected[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      Mt <: Metric[Scored[Y, P, M], S],
      S
  ] private[application] (
      val learner: L,
      val metric: Mt,
      val plan: PlanFingerprint,
      private val phases: PhaseSeeds,
      val split: TrainValidationTestSplit[Example[X, Y, M]],
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
    val route: TrainValidationTestRoute.type = TrainValidationTestRoute

    def refit: Either[
      ExperimentFailure[learner.FitError, learner.RunError],
      TVTRefitted[X, Y, M, P, L, Mt, S]
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
              new TVTRefitted(
                learner,
                metric,
                plan,
                phases,
                split,
                evaluation,
                receipt,
                refitted,
                refitData
              )
            )
        }

  final class TVTRefitted[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      Mt <: Metric[Scored[Y, P, M], S],
      S
  ] private[application] (
      val learner: L,
      val metric: Mt,
      val plan: PlanFingerprint,
      private val phases: PhaseSeeds,
      val split: TrainValidationTestSplit[Example[X, Y, M]],
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
      val trained: Trained[learner.Model],
      private val refitData: NonEmptyData[Use.Refit, Example[X, Y, M]]
  )(using Schema[X]):
    val route: TrainValidationTestRoute.type = TrainValidationTestRoute
    def model: Trained[learner.Model] = trained

    def test: Either[
      ExperimentFailure[learner.FitError, learner.RunError],
      TVTTested[X, Y, M, P, L, Mt, S]
    ] =
      EvaluationSources
        .finalTest(refitData, split.test.data)
        .left
        .map(error =>
          ExperimentFailure.Data(DataPhase.Source, error)
        )
        .flatMap { sources =>
          Evaluation.scored(trained, sources, metric) match
            case Left(ScoredEvaluationError.Prediction(error)) =>
              Left(
                ExperimentFailure.Predict(EvaluationPhase.Test, error)
              )
            case Left(ScoredEvaluationError.Metric(error)) =>
              Left(
                ExperimentFailure.Metric(EvaluationPhase.Test, error)
              )
            case Right(tested) =>
              Right(
                new TVTTested(
                  learner,
                  metric,
                  plan,
                  phases,
                  receipt,
                  trained,
                  tested
                )
              )
        }

  final class TVTTested[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      Mt <: Metric[Scored[Y, P, M], S],
      S
  ] private[application] (
      val learner: L,
      val metric: Mt,
      val plan: PlanFingerprint,
      private val phases: PhaseSeeds,
      val selection: SelectionReceipt[L, Mt, S],
      val trained: Trained[learner.Model],
      val evaluation: ScoredEvaluation[
        Use.Test,
        X,
        Y,
        M,
        P,
        S,
        Mt
      ]
  )(using Schema[X]):
    val route: TrainValidationTestRoute.type = TrainValidationTestRoute
    def score: S = evaluation.score
    def model: Trained[learner.Model] = trained

    def deploymentRefit: Either[
      ExperimentFailure[learner.FitError, learner.RunError],
      DeploymentRefitted[X, Y, M, P, L, Mt, S]
    ] =
      Refit
        .after(evaluation.receipt)
        .from(evaluation.allObserved)
        .left
        .map(error =>
          ExperimentFailure.Refit(RefitPhase.DeploymentPromotion, error)
        )
        .flatMap { deploymentData =>
          Fit
            .learner(
              learner,
              deploymentData,
              seed = phases.deploymentRefit,
              plan = plan
            )
            .left
            .map(failure =>
              ExperimentFailure.Fit(FitPhase.DeploymentRefit, failure)
            )
            .map(refitted =>
              new DeploymentRefitted(
                learner,
                metric,
                plan,
                trained = refitted,
                prior = evaluation
              )
            )
        }

  final class PrecommittedDefined[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      Mt <: Metric[Scored[Y, P, M], S],
      S
  ] private[application] (
      val data: Data[Use.Unsplit, Example[X, Y, M]],
      private val specification: HoldoutSpec,
      val seed: Seed,
      val plan: PlanFingerprint,
      val learner: L,
      val metric: Mt
  )(using Schema[X]):
    val route: PrecommittedHoldoutRoute.type = PrecommittedHoldoutRoute
    private val phases = PhaseSeeds(seed, plan)

    def partition: Either[
      ExperimentFailure[learner.FitError, learner.RunError],
      PrecommittedPartitioned[X, Y, M, P, L, Mt, S]
    ] =
      if data.size <= 0L then
        Left(ExperimentFailure.Definition(ExperimentDefinitionError.EmptySource))
      else
        Split
          .holdout(data, specification, phases.split)
          .left
          .map(error =>
            ExperimentFailure.Split(SplitPhase.Partition, error)
          )
          .map(split =>
            new PrecommittedPartitioned(
              learner,
              metric,
              plan,
              phases,
              split
            )
          )

    def runToTested: Either[
      ExperimentFailure[learner.FitError, learner.RunError],
      PrecommittedTested[X, Y, M, P, L, Mt, S]
    ] =
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
              fitted.test match
                case Left(error) =>
                  Left(
                    error.asInstanceOf[
                      ExperimentFailure[learner.FitError, learner.RunError]
                    ]
                  )
                case Right(tested) => Right(tested)

  final class PrecommittedPartitioned[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      Mt <: Metric[Scored[Y, P, M], S],
      S
  ] private[application] (
      val learner: L,
      val metric: Mt,
      val plan: PlanFingerprint,
      private val phases: PhaseSeeds,
      val split: Holdout[Example[X, Y, M]]
  )(using Schema[X]):
    val route: PrecommittedHoldoutRoute.type = PrecommittedHoldoutRoute

    def fitCandidate: Either[
      ExperimentFailure[learner.FitError, learner.RunError],
      PrecommittedCandidateFitted[X, Y, M, P, L, Mt, S]
    ] =
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
          new PrecommittedCandidateFitted(
            learner,
            metric,
            plan,
            phases,
            split,
            trained
          )
        )

  final class PrecommittedCandidateFitted[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      Mt <: Metric[Scored[Y, P, M], S],
      S
  ] private[application] (
      val learner: L,
      val metric: Mt,
      val plan: PlanFingerprint,
      private val phases: PhaseSeeds,
      val split: Holdout[Example[X, Y, M]],
      val trained: Trained[learner.Model]
  )(using Schema[X]):
    val route: PrecommittedHoldoutRoute.type = PrecommittedHoldoutRoute

    def test: Either[
      ExperimentFailure[learner.FitError, learner.RunError],
      PrecommittedTested[X, Y, M, P, L, Mt, S]
    ] =
      EvaluationSources
        .precommittedTest(split.train, split.test.data)
        .left
        .map(error =>
          ExperimentFailure.Data(DataPhase.Source, error)
        )
        .flatMap { sources =>
          Evaluation.scored(trained, sources, metric) match
            case Left(ScoredEvaluationError.Prediction(error)) =>
              Left(
                ExperimentFailure.Predict(
                  EvaluationPhase.PrecommittedTest,
                  error
                )
              )
            case Left(ScoredEvaluationError.Metric(error)) =>
              Left(
                ExperimentFailure.Metric(
                  EvaluationPhase.PrecommittedTest,
                  error
                )
              )
            case Right(evaluation) =>
              Right(
                new PrecommittedTested(
                  learner,
                  metric,
                  plan,
                  phases,
                  trained,
                  evaluation
                )
              )
        }

  final class PrecommittedTested[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      Mt <: Metric[Scored[Y, P, M], S],
      S
  ] private[application] (
      val learner: L,
      val metric: Mt,
      val plan: PlanFingerprint,
      private val phases: PhaseSeeds,
      val trained: Trained[learner.Model],
      val evaluation: ScoredEvaluation[
        Use.Test,
        X,
        Y,
        M,
        P,
        S,
        Mt
      ]
  )(using Schema[X]):
    val route: PrecommittedHoldoutRoute.type = PrecommittedHoldoutRoute
    def score: S = evaluation.score
    def model: Trained[learner.Model] = trained

    def deploymentRefit: Either[
      ExperimentFailure[learner.FitError, learner.RunError],
      DeploymentRefitted[X, Y, M, P, L, Mt, S]
    ] =
      Refit
        .after(evaluation.receipt)
        .from(evaluation.allObserved)
        .left
        .map(error =>
          ExperimentFailure.Refit(RefitPhase.DeploymentPromotion, error)
        )
        .flatMap { deploymentData =>
          Fit
            .learner(
              learner,
              deploymentData,
              seed = phases.deploymentRefit,
              plan = plan
            )
            .left
            .map(failure =>
              ExperimentFailure.Fit(FitPhase.DeploymentRefit, failure)
            )
            .map(refitted =>
              new DeploymentRefitted(
                learner,
                metric,
                plan,
                trained = refitted,
                prior = evaluation
              )
            )
        }

  final class DeploymentRefitted[
      X,
      Y,
      M,
      P,
      L <: Learner[Id, X, Y, M, P],
      Mt <: Metric[Scored[Y, P, M], S],
      S
  ] private[application] (
      val learner: L,
      val metric: Mt,
      val plan: PlanFingerprint,
      val trained: Trained[learner.Model],
      val prior: ScoredEvaluation[Use.Test, X, Y, M, P, S, Mt]
  ):
    def model: Trained[learner.Model] = trained
    def audit: Audit = trained.audit

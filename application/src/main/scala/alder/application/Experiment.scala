package alder.application

import alder.data.*
import alder.kernel.*
import alder.metrics.*
import cats.Id

/** Route evidence retained through every shared experiment state. */
sealed trait ExperimentRoute

sealed trait ValidationCapableRoute extends ExperimentRoute

sealed trait ValidationRoute extends ValidationCapableRoute
object ValidationRoute       extends ValidationRoute

sealed trait TrainValidationTestRoute extends ValidationCapableRoute
object TrainValidationTestRoute       extends TrainValidationTestRoute

sealed trait PrecommittedHoldoutRoute extends ExperimentRoute
object PrecommittedHoldoutRoute       extends PrecommittedHoldoutRoute

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

object ExperimentFailure:
  /** Human rendering at an application edge. The typed failure remains
    * available for pattern matching; custom leaf errors can supply stable
    * renderers instead of relying on their `toString` implementations.
    */
  extension [FitE, RunE](failure: ExperimentFailure[FitE, RunE])
    def renderWith(
        renderFitError: FitE => String,
        renderRunError: RunE => String
    ): String =
      failure match
        case ExperimentFailure.Definition(error) =>
          s"experiment definition failed: ${renderDefinition(error)}"
        case ExperimentFailure.Data(phase, error) =>
          s"experiment data phase $phase failed: ${renderDataOrRefit(error)}"
        case ExperimentFailure.Split(phase, error) =>
          s"experiment split phase $phase failed: ${renderData(error)}"
        case ExperimentFailure.Fit(phase, fitted) =>
          s"experiment fit phase $phase failed at ${fitted.stage.render}: " +
            renderFitError(fitted.cause)
        case ExperimentFailure.Predict(phase, error) =>
          error match
            case EvaluationError.PredictionFailed(prediction) =>
              s"experiment prediction phase $phase failed at " +
                s"${prediction.stage.render}: " +
                renderRunError(prediction.cause)
            case EvaluationError.FitSourceMismatch(expected, actual) =>
              s"experiment prediction phase $phase failed: fitted source " +
                s"${renderFingerprint(actual)} did not match expected " +
                renderFingerprint(expected)
            case EvaluationError.FitAuditMismatch(expected, actual) =>
              s"experiment prediction phase $phase failed: fitted refit " +
                s"evidence ${renderRefitEvidence(actual)} did not match " +
                s"expected ${renderRefitEvidence(expected)}"
        case ExperimentFailure.Metric(phase, error) =>
          s"experiment metric phase $phase failed: ${renderMetric(error)}"
        case ExperimentFailure.Select(phase, error) =>
          s"experiment selection phase $phase failed: ${renderSelection(error)}"
        case ExperimentFailure.Refit(phase, error) =>
          s"experiment refit phase $phase failed: " +
            renderApplicationOrRefit(error)

    /** Convenient rendering for leaf error types with meaningful `toString`
      * implementations. Use [[renderWith]] when an application owns a more
      * stable presentation.
      */
    def render: String =
      renderWith(String.valueOf, String.valueOf)

  private def renderDefinition(error: ExperimentDefinitionError): String =
    error match
      case ExperimentDefinitionError.EmptySource => "source data is empty"

  private def renderDataOrRefit(error: DataError | RefitError): String =
    error match
      case data: DataError   => renderData(data)
      case refit: RefitError => renderRefit(refit)

  private def renderApplicationOrRefit(
      error: ApplicationRefitError | RefitError
  ): String =
    error match
      case application: ApplicationRefitError =>
        renderApplicationRefit(application)
      case refit: RefitError => renderRefit(refit)

  private def renderData(error: DataError): String =
    error match
      case DataError.EmptyData => "data is empty"
      case DataError.InvalidRows(value) =>
        s"row count must be positive, got $value"
      case DataError.InvalidFraction(numerator, denominator) =>
        s"fraction must satisfy 0 < numerator < denominator, got " +
          s"$numerator/$denominator"
      case DataError.InvalidThreeWayFractionSum(validation, test) =>
        s"validation fraction $validation plus test fraction $test must be " +
          "less than one"
      case DataError.InvalidRankText(field, reason) =>
        s"RankV1 field $field is invalid: ${renderRankText(reason)}"
      case DataError.DuplicateSourceRow(id) =>
        s"source row ${id.value} occurs more than once"
      case DataError.EmptySplitRole(role, availableRows, policy) =>
        s"split policy $policy assigned no rows to $role from " +
          s"$availableRows available rows"
      case DataError.ExhaustiveSplit(availableRows, policy) =>
        s"split policy $policy left no training rows among " +
          s"$availableRows available rows"
      case DataError.InvalidHoldoutSize(requested, available) =>
        s"holdout size $requested is invalid for $available available rows"
      case DataError.InvalidFoldCount(requested) =>
        s"fold count must be at least two, got $requested"
      case DataError.TooManyFolds(requested, availableRows) =>
        s"fold count $requested exceeds $availableRows available rows"
      case DataError.TooFewGroups(requestedFolds, availableGroups) =>
        s"fold count $requestedFolds exceeds $availableGroups available groups"
      case DataError.InvalidResamplingAssignment =>
        "resampling assignment is incomplete, duplicated, or out of range"
      case DataError.Resample4sPopulationTooLarge(availableRows) =>
        s"Resample4s cannot index $availableRows rows with Int ordinals"
      case DataError.Resample4sPopulationSizeMismatch(expected, actual) =>
        s"Resample4s population size $actual did not match expected $expected"
      case DataError.Resample4sSeedMismatch(expected, actual) =>
        s"Resample4s seed $actual did not match expected $expected"
      case DataError.Resample4sPopulationFingerprintMismatch =>
        "Resample4s population fingerprint did not match the Alder data"
      case DataError.InvalidResample4sPopulationFingerprint(policy, digest) =>
        s"Resample4s population fingerprint $policy:$digest is invalid"
      case DataError.InvalidRollingWindow(initial, assessment, step) =>
        s"rolling window requires positive initial, assessment, and step " +
          s"sizes, got $initial, $assessment, and $step"
      case DataError.NoRollingFolds(availableRows, initialSize) =>
        s"rolling window with initial size $initialSize produced no folds " +
          s"from $availableRows rows"

  private def renderRankText(error: RankTextError): String =
    error match
      case RankTextError.UnpairedSurrogate(index) =>
        s"unpaired surrogate at code-unit index $index"
      case RankTextError.Utf8LengthExceedsU32(length) =>
        s"UTF-8 length $length exceeds the unsigned 32-bit limit"

  private def renderRefit(error: RefitError): String =
    error match
      case RefitError.EmptyEvaluationSource(role) =>
        s"$role evaluation source is empty"
      case RefitError.DuplicateObservedRow(id) =>
        s"observed row ${id.value} occurs in more than one source"
      case RefitError.MissingPriorRefitAudit =>
        "the fitted data has no prior refit audit"
      case RefitError.PriorRefitWasNotSelected =>
        "the prior validation result was not selected"
      case RefitError.PriorSourcesMustEndInValidation(actual) =>
        s"prior sources must end in Validation, got ${actual.mkString(" -> ")}"
      case RefitError.PriorFingerprintMismatch(expected, actual) =>
        s"prior source fingerprint ${renderFingerprint(actual)} did not " +
          s"match expected ${renderFingerprint(expected)}"

  private def renderApplicationRefit(error: ApplicationRefitError): String =
    error match
      case ApplicationRefitError.SelectionReceiptMismatch(receipt) =>
        s"selection receipt ${receipt.render} does not authorize these rows"
      case ApplicationRefitError.EvaluationReceiptMismatch(receipt) =>
        s"evaluation receipt ${receipt.render} does not authorize these rows"
      case ApplicationRefitError.SelectionReceiptAlreadyUsed(receipt) =>
        s"selection receipt ${receipt.render} was already used"
      case ApplicationRefitError.EvaluationReceiptAlreadyUsed(receipt) =>
        s"evaluation receipt ${receipt.render} was already used"

  private def renderMetric(error: MetricError): String =
    error match
      case MetricError.Empty => "metric received no observations"
      case MetricError.NonFiniteTruth(value) =>
        s"truth is non-finite: $value"
      case MetricError.NonFinitePrediction(value) =>
        s"prediction is non-finite: $value"
      case MetricError.NonFiniteResidual(truth, prediction) =>
        s"residual is non-finite for truth $truth and prediction $prediction"
      case MetricError.NonFiniteSquaredError(value) =>
        s"squared error is non-finite: $value"
      case MetricError.NonFiniteWeight(value) =>
        s"weight is non-finite: $value"
      case MetricError.NegativeWeight(value) =>
        s"weight is negative: $value"
      case MetricError.NonFiniteWeightedValue(value, weight) =>
        s"weighted value is non-finite for value $value and weight $weight"
      case MetricError.ZeroTotalWeight => "total metric weight is zero"
      case MetricError.NonFiniteResult => "metric result is non-finite"

  private def renderSelection(error: SelectionError): String =
    error match
      case SelectionError.ReportingMetricCannotSelect =>
        "a reporting-only metric cannot authorize selection"

  private def renderRefitEvidence(value: Option[RefitEvidence]): String =
    value match
      case None => "none"
      case Some(evidence) =>
        val selection = evidence.selection match
          case None          => "none"
          case Some(receipt) => receipt.render
        s"evaluation=${evidence.evaluation.render}, selection=$selection"

  private def renderFingerprint(value: DataFingerprint): String =
    val policy = value.policy match
      case FingerprintPolicy.ContentDigest(algorithm) =>
        s"content-digest:$algorithm"
      case FingerprintPolicy.SourceIdentity(uri, version) =>
        s"source-identity:$uri@$version"
      case FingerprintPolicy.Summary(policyId) => s"summary:$policyId"
    s"$policy:${value.digest}"

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
        Left(
          ExperimentFailure.Definition(ExperimentDefinitionError.EmptySource)
        )
      else
        Split
          .validation(data, specification, phases.split)
          .left
          .map(error => ExperimentFailure.Split(SplitPhase.Partition, error))
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
        .map(failure => ExperimentFailure.Fit(FitPhase.Candidate, failure))
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
        .map(error => ExperimentFailure.Data(DataPhase.Source, error))
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
    def audit: Audit                  = trained.audit

    /** Structured projection of this validation result's retained evidence. */
    def report: ExperimentReport[S] =
      ExperimentReport.validation(
        split,
        metric.descriptor,
        score,
        audit,
        phases.root
      )

    /** Predicts with the candidate fitted on the training partition. */
    def predict(input: X): Either[Failure[learner.RunError], P] =
      trained.predict(input)

    /** Predicts every input row with the candidate fitted on the training
      * partition, preserving row IDs and traversal order.
      */
    def predictAll[U <: Use](
        data: Data[U, X]
    ): Either[Failure[learner.RunError], Vector[(RowId, P)]] =
      trained.predictAll(data)

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
    def audit: Audit                  = trained.audit

    /** Predicts with the candidate refitted on training plus validation. */
    def predict(input: X): Either[Failure[learner.RunError], P] =
      trained.predict(input)

    /** Predicts every input row with the candidate refitted on training plus
      * validation, preserving row IDs and traversal order.
      */
    def predictAll[U <: Use](
        data: Data[U, X]
    ): Either[Failure[learner.RunError], Vector[(RowId, P)]] =
      trained.predictAll(data)

    // Retained for TrainValidationTestRoute.test / deploymentRefit.
    private[application] def phaseSeeds: PhaseSeeds = phases

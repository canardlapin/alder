package alder.models.linear

import alder.data.CoordinateError
import alder.kernel.*

/** Invalid user-supplied ridge configuration. */
enum RidgeConfigError derives CanEqual:
  case InvalidPenalty(value: Double)
  case InvalidTolerance(value: Double)

/** Validated ridge hyperparameters.
  *
  * The penalty is finite and non-negative. The convergence tolerance is
  * finite and strictly positive.
  */
final class RidgeConfig private (
    val penalty: Double,
    val fitIntercept: Boolean,
    val tolerance: Double
)

object RidgeConfig:
  /** Validates ridge hyperparameters before a learner or backend can observe
    * them.
    */
  def create(
      penalty: Double,
      fitIntercept: Boolean = true,
      tolerance: Double = 1.0e-10
  ): Either[RidgeConfigError, RidgeConfig] =
    if !penalty.isFinite || penalty < 0.0 then
      Left(RidgeConfigError.InvalidPenalty(penalty))
    else if !tolerance.isFinite || tolerance <= 0.0 then
      Left(RidgeConfigError.InvalidTolerance(tolerance))
    else Right(new RidgeConfig(penalty, fitIntercept, tolerance))

/** Row-weight policy supplied to a ridge backend. */
sealed trait RowWeights

object RowWeights:
  /** Gives every row unit weight. */
  case object Uniform extends RowWeights

  /** Owned, immutable weights in dataset traversal order. */
  final class ByRow private[linear] (
      private val ownedValues: IArray[Double]
  ) extends RowWeights:
    private[alder] def valuesCopy: IArray[Double] =
      IArray.from(ownedValues)

  /** Rejection produced while validating explicit row weights. */
  enum Error derives CanEqual:
    case Empty
    case Invalid(index: Int, value: Double)

  /** Copies and validates a non-empty sequence of finite, non-negative
    * weights.
    */
  def byRow(values: IArray[Double]): Either[Error, ByRow] =
    val owned = IArray.from(values)
    if owned.isEmpty then Left(Error.Empty)
    else
      var index = 0
      var invalid: Option[Error] = None
      while index < owned.length && invalid.isEmpty do
        val value = owned(index)
        if !value.isFinite || value < 0.0 then
          invalid = Some(Error.Invalid(index, value))
        index += 1
      invalid.toLeft(new ByRow(owned))

  private[alder] def validatedByLearner(
      values: IArray[Double]
  ): ByRow =
    new ByRow(IArray.from(values))

/** Stable identity of a concrete ridge solver. */
enum SolverId derives CanEqual:
  case GaleAugmentedQR
  case GaleNormalCholesky
  case Linop4sLSQR
  case Linop4sCG

/** Quantitative evidence that an iterative method converged. */
enum ConvergenceEvidence derives CanEqual:
  case RelativeResidual(achieved: Double, target: Double)
  case AbsoluteResidual(achieved: Double, target: Double)
  case ExactSolution

/** Quantitative evidence explaining a solver breakdown. */
enum BreakdownEvidence derives CanEqual:
  case NotPositiveDefinite(curvature: Double)
  case ZeroDirection(quantity: String)

/** Why a solver stopped, whether successfully or unsuccessfully. */
enum TerminationReason derives CanEqual:
  case Converged(reason: ConvergenceEvidence)
  case Direct
  case MaximumIterations(limit: Int)
  case EvaluationBudgetExceeded(limit: Long)
  case Breakdown(reason: BreakdownEvidence)
  case Stagnated(window: Int)
  case Diverged
  case NonFinite(quantity: String)
  case Cancelled

/** Residual norms reported by a backend. */
final case class ResidualEvidence(
    norm: Double,
    relative: Option[Double],
    normalEquation: Option[Double]
) derives CanEqual

/** Evidence supporting a numerical assumption used by a backend. */
enum AssumptionEvidence derives CanEqual:
  case Constructed(description: String)
  case NumericallyChecked(
      property: String,
      samples: Int,
      worstDeviation: Double,
      passed: Boolean
  )
  case UserAsserted(justification: String)

/** Backend-specific structured audit fields. */
final case class AuditRecord(
    fields: Vector[(String, AuditValue)]
) derives CanEqual

object AuditRecord:
  val empty: AuditRecord = AuditRecord(Vector.empty)

/** Complete solver-level provenance attached to a ridge solution. */
final case class SolverReceipt(
    algorithm: SolverId,
    iterations: Option[Int],
    termination: TerminationReason,
    residual: Option[ResidualEvidence],
    conditionEstimate: Option[Double],
    assumptions: Vector[AssumptionEvidence],
    backend: BackendFingerprint,
    extensions: AuditRecord
)

/** Validated ridge coefficients and the evidence supporting them.
  *
  * Coefficients are owned internally. Use `coefficient` for indexed access or
  * `coefficientsCopy` when an independent array is required.
  */
final class RidgeSolution private[linear] (
    coefficients: IArray[Double],
    val intercept: Double,
    val receipt: SolverReceipt,
    val objective: Double,
    val kktResidual: Double
):
  private val ownedCoefficients = IArray.from(coefficients)

  /** Number of fitted coefficients. */
  def coefficientCount: Int = ownedCoefficients.length

  /** Coefficient at the zero-based coordinate index. */
  def coefficient(index: Int): Double = ownedCoefficients(index)

  /** Defensive copy of all coefficients in coordinate order. */
  def coefficientsCopy: IArray[Double] = IArray.from(ownedCoefficients)

object RidgeSolution:
  private[alder] def create(
      coefficients: IArray[Double],
      intercept: Double,
      receipt: SolverReceipt,
      objective: Double,
      kktResidual: Double
  ): RidgeSolution =
    new RidgeSolution(
      coefficients,
      intercept,
      receipt,
      objective,
      kktResidual
    )

/** Fitting context narrowed to the numerical backend boundary. */
final case class BackendContext(
    stage: StagePath,
    numericMode: NumericMode
)

object BackendContext:
  def from(context: FitContext): BackendContext =
    BackendContext(context.stagePath, context.numericMode)

/** A rejected ridge fit. */
enum RidgeBackendError derives CanEqual:
  case Coordinate(row: RowId, error: CoordinateError)
  case NonFiniteFeature(row: RowId, coordinate: String, value: Double)
  case NonFiniteTarget(row: RowId, value: Double)
  case InvalidWeightCount(expected: Int, actual: Int)
  case InvalidWeight(row: RowId, value: Double)
  case NonPositiveTotalWeight(value: Double)
  case TooManyRows(size: Long)
  case EmptyCoordinateSpace
  case RequiresPositivePenalty(algorithm: SolverId)
  case InvalidIterationLimit(value: Int)
  case NumericModeMismatch(
      captured: NumericMode,
      requested: NumericMode
  )
  case SolverFailure(
      algorithm: SolverId,
      kind: SolverFailureKind,
      detail: String
  )
  case UnusableSolution(
      algorithm: SolverId,
      quantity: UnusableSolutionQuantity
  )

/** A rejected application of a fitted ridge model. */
enum RidgePredictionError derives CanEqual:
  case Coordinate(error: CoordinateError)
  case NonFiniteFeature(coordinate: String, value: Double)
  case NonFinitePrediction(value: Double)

/** Coarse category for an exception or failure reported by a backend. */
enum SolverFailureKind derives CanEqual:
  case Factorization
  case BackendException
  case OperatorConstruction

/** Non-finite output that makes a nominal solver result unusable. */
enum UnusableSolutionQuantity derives CanEqual:
  case CoefficientsOrIntercept
  case IterateOrResidual

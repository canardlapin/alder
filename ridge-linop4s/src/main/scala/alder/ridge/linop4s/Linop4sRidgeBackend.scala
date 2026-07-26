package alder.ridge.linop4s

import alder.data.Coordinates
import alder.kernel.*
import alder.models.linear.*
import cats.Applicative
import cats.data.EitherT
import linop4s.*
import linop4s.array.Dense
import linop4s.array.given
import linop4s.krylov.*

enum Linop4sRidgeStrategy derives CanEqual:
  case LSQR
  case NormalCG

final class Linop4sRidgeBackend[F[_]](
    val strategy: Linop4sRidgeStrategy,
    val maxIterations: Int,
    val numericMode: NumericMode
)(using applicative: Applicative[F])
    extends RidgeBackend[F]:

  val fingerprint: BackendFingerprint =
    BackendFingerprint(
      "linop4s",
      "0.1.0-SNAPSHOT",
      AuditValue.record(
        "strategy" -> AuditValue.text(strategy.toString),
        "maxIterations" -> AuditValue.integer(maxIterations.toLong),
        "numericMode" -> AuditValue.text(numericMode.toString),
        "intercept" -> AuditValue.text("weighted-centering"),
        "weights" -> AuditValue.text("matrix-free-sqrt-diagonal"),
        "damping" -> AuditValue.text("sqrt(lambda)")
      )
    )

  def solve[X, M, U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Double, M]],
      coordinates: Coordinates[X],
      config: RidgeConfig,
      weights: RowWeights,
      context: BackendContext
  ): FitResult[F, RidgeBackendError, RidgeSolution] =
    val solved =
      if maxIterations <= 0 then
        Left(RidgeBackendError.InvalidIterationLimit(maxIterations))
      else if context.numericMode != numericMode then
        Left(RidgeBackendError.NumericModeMismatch(numericMode, context.numericMode))
      else if strategy == Linop4sRidgeStrategy.NormalCG &&
        config.penalty <= 0.0
      then
        Left(RidgeBackendError.RequiresPositivePenalty(solverId))
      else
        RidgeProblem
          .materialize(data, coordinates, weights)
          .flatMap(problem => solveProblem(problem, config))
    EitherT.fromEither[F](solved.left.map(context.stage.failure))

  private def solveProblem(
      problem: RidgeProblem,
      config: RidgeConfig
  ): Either[RidgeBackendError, RidgeSolution] =
    val domain = Dense.real("ridge-coefficients", problem.columns)
    val codomain = Dense.real("ridge-observations", problem.rows)
    val operator =
      AdjointOp.primitive[Double, Array[Double], Array[Double]](
        "alder-weighted-centered-design",
        domain,
        codomain
      )(
        coefficients =>
          val result = new Array[Double](problem.rows)
          var row = 0
          while row < problem.rows do
            var value = 0.0
            var column = 0
            while column < problem.columns do
              value +=
                problem.centeredFeature(
                  row,
                  column,
                  config.fitIntercept
                ) * coefficients(column)
              column += 1
            result(row) = math.sqrt(problem.weights(row)) * value
            row += 1
          result
      )(
        residuals =>
          val result = new Array[Double](problem.columns)
          var row = 0
          while row < problem.rows do
            val scaled =
              math.sqrt(problem.weights(row)) * residuals(row)
            var column = 0
            while column < problem.columns do
              result(column) +=
                problem.centeredFeature(
                  row,
                  column,
                  config.fitIntercept
                ) * scaled
              column += 1
            row += 1
          result
      )
    val response = Array.tabulate(problem.rows) { row =>
      math.sqrt(problem.weights(row)) *
        problem.centeredTarget(row, config.fitIntercept)
    }
    val result =
      strategy match
        case Linop4sRidgeStrategy.LSQR =>
          Right(
            LSQR.solve(
              operator,
              response,
              LSQRConfig(
                tolerance = config.tolerance,
                maxIterations = maxIterations,
                damping =
                  if config.penalty == 0.0 then None
                  else Some(math.sqrt(config.penalty))
              )
            )
          )
        case Linop4sRidgeStrategy.NormalCG =>
          PositiveDefinite
            .normalEquation(operator, config.penalty)
            .left
            .map(detail =>
              RidgeBackendError.SolverFailure(
                solverId,
                SolverFailureKind.OperatorConstruction,
                detail
              )
            )
            .map { normal =>
              CG.solve(
                normal,
                operator.applyAdjoint(response),
                CGConfig(config.tolerance, maxIterations)
              )
            }
    result.flatMap(finish(problem, config, _))

  private def finish(
      problem: RidgeProblem,
      config: RidgeConfig,
      result: SolveResult[Array[Double], Double]
  ): Either[RidgeBackendError, RidgeSolution] =
    val coefficients = IArray.from(result.value)
    val intercept =
      if config.fitIntercept then
        var value = problem.targetMean
        var column = 0
        while column < problem.columns do
          value -=
            problem.featureMeans(column) * coefficients(column)
          column += 1
        value
      else 0.0
    if !intercept.isFinite ||
      coefficients.exists(value => !value.isFinite) ||
      !result.finalResidual.isFinite
    then
      Left(
        RidgeBackendError.UnusableSolution(
          solverId,
          UnusableSolutionQuantity.IterateOrResidual
        )
      )
    else
      val (objective, kkt) =
        RidgeProblem.evidence(
          problem,
          coefficients,
          intercept,
          config.penalty
        )
      val assumptions =
        result.termination match
          case Termination.Breakdown(_, evidence) =>
            evidence.toVector.map(mapEvidence)
          case _ if strategy == Linop4sRidgeStrategy.NormalCG =>
            Vector(
              AssumptionEvidence.Constructed(
                "A* A + positive lambda I"
              )
            )
          case _ => Vector.empty
      Right(
        RidgeSolution.create(
          coefficients,
          intercept,
          SolverReceipt(
            solverId,
            Some(result.iterations),
            mapTermination(result.termination),
            Some(
              ResidualEvidence(
                result.finalResidual,
                None,
                Some(kkt)
              )
            ),
            None,
            assumptions,
            fingerprint,
            AuditRecord(
              Vector(
                "operatorApplications" ->
                  AuditValue.integer(result.operatorApplications),
                "adjointApplications" ->
                  AuditValue.integer(result.adjointApplications),
                "conditioning" -> AuditValue.text(
                  strategy match
                    case Linop4sRidgeStrategy.LSQR =>
                      "damped rectangular system"
                    case Linop4sRidgeStrategy.NormalCG =>
                      "normal equations square the condition number"
                ),
                "damping" ->
                  AuditValue.decimal(math.sqrt(config.penalty))
              )
            )
          ),
          objective,
          kkt
        )
      )

  private def mapEvidence(
      evidence: Evidence
  ): AssumptionEvidence =
    evidence match
      case Evidence.Constructed(description) =>
        AssumptionEvidence.Constructed(description)
      case Evidence.NumericallyChecked(report) =>
        AssumptionEvidence.NumericallyChecked(
          report.property,
          report.samples,
          report.worstDeviation,
          report.passed
        )
      case Evidence.UserAsserted(justification) =>
        AssumptionEvidence.UserAsserted(justification)

  private def mapTermination(
      termination: Termination[Double]
  ): TerminationReason =
    termination match
      case Termination.Converged(reason) =>
        TerminationReason.Converged(
          reason match
            case ConvergenceReason.RelativeResidual(achieved, target) =>
              ConvergenceEvidence.RelativeResidual(achieved, target)
            case ConvergenceReason.AbsoluteResidual(achieved, target) =>
              ConvergenceEvidence.AbsoluteResidual(achieved, target)
            case _: ConvergenceReason.ExactSolution.type =>
              ConvergenceEvidence.ExactSolution
        )
      case Termination.MaximumIterations(limit) =>
        TerminationReason.MaximumIterations(limit)
      case Termination.EvaluationBudgetExceeded(limit) =>
        TerminationReason.EvaluationBudgetExceeded(limit)
      case Termination.Breakdown(reason, _) =>
        TerminationReason.Breakdown(
          reason match
            case BreakdownReason.NotPositiveDefinite(curvature) =>
              BreakdownEvidence.NotPositiveDefinite(curvature)
            case BreakdownReason.ZeroDirection(quantity) =>
              BreakdownEvidence.ZeroDirection(quantity)
        )
      case Termination.Stagnated(window) =>
        TerminationReason.Stagnated(window)
      case Termination.Diverged(_) =>
        TerminationReason.Diverged
      case Termination.NonFinite(quantity) =>
        TerminationReason.NonFinite(quantity)
      case _: Termination.Cancelled.type =>
        TerminationReason.Cancelled

  private def solverId: SolverId =
    strategy match
      case Linop4sRidgeStrategy.LSQR =>
        SolverId.Linop4sLSQR
      case Linop4sRidgeStrategy.NormalCG =>
        SolverId.Linop4sCG

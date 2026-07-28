package alder.ridge.gale

import alder.data.{CoordinateWriter, FeatureView}
import alder.kernel.*
import alder.models.linear.*
import cats.Applicative
import cats.data.EitherT
import gale.backend.{Backend, BackendReport}
import gale.linalg.{DMat, DVec}

/** Dense direct-solver strategy used by the Gale backend. */
enum GaleRidgeStrategy derives CanEqual:
  case AugmentedQR
  case NormalCholesky

/** Gale-backed dense ridge solver.
  *
  * `AugmentedQR` solves the weighted, damped least-squares system directly.
  * `NormalCholesky` is faster for suitable problems but squares the condition
  * number. The selected Gale backend and its capabilities are captured in the
  * solver fingerprint.
  */
final class GaleRidgeBackend[F[_]](
    val galeBackend: Backend,
    val strategy: GaleRidgeStrategy,
    val numericMode: NumericMode
)(using applicative: Applicative[F])
    extends RidgeBackend[F]:

  private val report: BackendReport = Backend.current(using galeBackend)

  val fingerprint: BackendFingerprint =
    BackendFingerprint(
      "gale",
      "1.0.0-SNAPSHOT",
      AuditValue.record(
        "backend" -> AuditValue.text(report.name),
        "capabilities" -> AuditValue.sequence(
          report.capabilities.toVector
            .map(_.toString)
            .sorted
            .map(AuditValue.text)*
        ),
        "jvmThreads" -> AuditValue.integer(report.config.jvmThreads.toLong),
        "nativeThreads" ->
          AuditValue.integer(report.config.nativeThreads.toLong),
        "allowNestedParallelism" ->
          AuditValue.bool(report.config.allowNestedParallelism),
        "nativeGemmMinFlops" ->
          AuditValue.integer(report.thresholds.nativeGemmMinFlops),
        "nativeGemvMinWork" ->
          AuditValue.integer(report.thresholds.nativeGemvMinWork),
        "nativeLuMinSize" ->
          AuditValue.integer(report.thresholds.nativeLuMinSize.toLong),
        "nativeCholeskyMinSize" ->
          AuditValue.integer(
            report.thresholds.nativeCholeskyMinSize.toLong
          ),
        "nativeQrMinSize" ->
          AuditValue.integer(report.thresholds.nativeQrMinSize.toLong),
        "hasDenseFactorizations" ->
          AuditValue.bool(report.hasDenseFactorizations),
        "hasSpectral" -> AuditValue.bool(report.hasSpectral),
        "strategy" -> AuditValue.text(strategy.toString),
        "numericMode" -> AuditValue.text(numericMode.toString),
        "intercept" -> AuditValue.text("weighted-centering"),
        "weights" -> AuditValue.text("sqrt-row-materialization")
      )
    )

  def solve[X, M, U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Double, M]],
      features: FeatureView[X],
      config: RidgeConfig,
      weights: RowWeights,
      context: BackendContext
  ): FitResult[F, RidgeBackendError, RidgeSolution] =
    val solved =
      if context.numericMode != numericMode then
        Left(RidgeBackendError.NumericModeMismatch(numericMode, context.numericMode))
      else
        RidgeProblem
          .materialize(data, features, weights)
          .flatMap(problem => solveProblem(problem, features, config))
    EitherT.fromEither[F](
      solved.left.map(context.stage.failure)
    )

  private def solveProblem[X](
      problem: RidgeProblem,
      features: FeatureView[X],
      config: RidgeConfig
  ): Either[RidgeBackendError, RidgeSolution] =
    try
      val rows =
        if strategy == GaleRidgeStrategy.AugmentedQR &&
          config.penalty > 0.0
        then problem.rows + problem.columns
        else problem.rows
      val builder = DMat.newBuilder(rows, problem.columns)
      var row = 0
      while row < problem.rows do
        val scale = math.sqrt(problem.weights(row))
        val writer = new CoordinateWriter:
          val size: Int = problem.columns
          def write(
              column: Int,
              name: String,
              value: Double
          ): Either[alder.data.CoordinateError, Unit] =
            val centered =
              if config.fitIntercept then
                value - problem.featureMeans(column)
              else value
            builder(row, column) = scale * centered
            Right(())
        var column = 0
        while column < problem.columns do
          writer.write(
            column,
            features.names(column),
            problem.feature(row, column)
          ) match
            case Left(error) =>
              return Left(
                RidgeBackendError.Coordinate(problem.rowIds(row), error)
              )
            case Right(_) => ()
          column += 1
        row += 1
      if rows > problem.rows then
        val damping = math.sqrt(config.penalty)
        var column = 0
        while column < problem.columns do
          builder(problem.rows + column, column) = damping
          column += 1
      val matrix = builder.result()
      val response =
        DVec.tabulate(rows) { index =>
          if index < problem.rows then
            math.sqrt(problem.weights(index)) *
              problem.centeredTarget(index, config.fitIntercept)
          else 0.0
        }
      val coefficients =
        strategy match
          case GaleRidgeStrategy.AugmentedQR =>
            matrix.qr(using galeBackend).solveLeastSquares(response)
          case GaleRidgeStrategy.NormalCholesky =>
            val normal =
              (matrix.t * matrix)(using galeBackend)
                .addToDiagonal(config.penalty)
            val rhs = (matrix.t * response)(using galeBackend)
            normal
              .cholesky(using galeBackend)
              .flatMap(_.solve(rhs))
      coefficients
        .left
        .map(error =>
          RidgeBackendError.SolverFailure(
            solverId,
            SolverFailureKind.Factorization,
            error.toString
          )
        )
        .flatMap { vector =>
          val values = IArray.from(vector.toSeq)
          val intercept =
            if config.fitIntercept then
              var result = problem.targetMean
              var column = 0
              while column < problem.columns do
                result -= problem.featureMeans(column) * values(column)
                column += 1
              result
            else 0.0
          if !intercept.isFinite || values.exists(value => !value.isFinite)
          then
            Left(
              RidgeBackendError.UnusableSolution(
                solverId,
                UnusableSolutionQuantity.CoefficientsOrIntercept
              )
            )
          else
            val (objective, kkt) =
              RidgeProblem.evidence(
                problem,
                values,
                intercept,
                config.penalty
              )
            Right(
              RidgeSolution.create(
                values,
                intercept,
                SolverReceipt(
                  solverId,
                  None,
                  TerminationReason.Direct,
                  Some(ResidualEvidence(kkt, None, Some(kkt))),
                  None,
                  Vector.empty,
                  fingerprint,
                  AuditRecord(
                    Vector(
                      "centering" ->
                        AuditValue.text("weighted backend algebra"),
                      "conditioning" -> AuditValue.text(
                        strategy match
                          case GaleRidgeStrategy.AugmentedQR =>
                            "augmented system"
                          case GaleRidgeStrategy.NormalCholesky =>
                            "normal equations square the condition number"
                      )
                    )
                  )
                ),
                objective,
                kkt
              )
            )
        }
    catch
      case error: RuntimeException =>
        Left(
          RidgeBackendError.SolverFailure(
            solverId,
            SolverFailureKind.BackendException,
            error.toString
          )
        )

  private def solverId: SolverId =
    strategy match
      case GaleRidgeStrategy.AugmentedQR =>
        SolverId.GaleAugmentedQR
      case GaleRidgeStrategy.NormalCholesky =>
        SolverId.GaleNormalCholesky

package alder.models.linear

import alder.data.Coordinates
import alder.kernel.*
import cats.Monad
import cats.data.EitherT

final class RidgeModel[X] private[linear] (
    val solution: RidgeSolution,
    coordinates: Coordinates[X],
    stage: StagePath
) extends Pipe[X, RidgePredictionError, Double]:
  def run(value: X): Either[Failure[RidgePredictionError], Double] =
    coordinates
      .read(value)
      .left
      .map(error => stage.failure(RidgePredictionError.Coordinate(error)))
      .flatMap { values =>
        var prediction = solution.intercept
        var column = 0
        var failure: Option[Failure[RidgePredictionError]] = None
        while column < values.length && failure.isEmpty do
          val coordinate = values(column)
          if !coordinate.isFinite then
            failure = Some(
              stage.failure(
                RidgePredictionError.NonFiniteFeature(
                  coordinates.names(column),
                  coordinate
                )
              )
            )
          else
            prediction += coordinate * solution.coefficient(column)
          column += 1
        failure.toLeft(prediction).flatMap { result =>
          if result.isFinite then Right(result)
          else
            Left(
              stage.failure(
                RidgePredictionError.NonFinitePrediction(result)
              )
            )
        }
      }

final class RidgeRegression[F[_], X, M](
    val config: RidgeConfig,
    val backend: RidgeBackend[F]
)(using
    monad: Monad[F],
    coordinates: Coordinates[X]
) extends Learner[F, X, Double, M, Double]:
  type FitError = RidgeBackendError
  type RunError = RidgePredictionError
  type Model = RidgeModel[X]

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Double, M]]
  )(using context: FitContext): FitResult[F, FitError, Trained[Model]] =
    backend
      .solve(
        data,
        coordinates,
        config,
        RowWeights.Uniform,
        BackendContext.from(context)
      )
      .map { solution =>
        val model = new RidgeModel(solution, coordinates, context.stagePath)
        context.complete(model, data, descriptor("alder.ridge"))
      }

  private def descriptor(id: String): ComponentDescriptor =
    ComponentDescriptor(
      ComponentId(id),
      ComponentVersion("0.1.0-SNAPSHOT"),
      AuditValue.record(
        "penalty" -> AuditValue.decimal(config.penalty),
        "fitIntercept" -> AuditValue.bool(config.fitIntercept),
        "tolerance" -> AuditValue.decimal(config.tolerance)
      ),
      backend.fingerprint
    )

final class WeightedRidgeRegression[F[_], X, M](
    val config: RidgeConfig,
    val backend: RidgeBackend[F]
)(using
    monad: Monad[F],
    coordinates: Coordinates[X],
    weightOf: WeightOf[M]
) extends Learner[F, X, Double, M, Double]:
  type FitError = RidgeBackendError
  type RunError = RidgePredictionError
  type Model = RidgeModel[X]

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Double, M]]
  )(using context: FitContext): FitResult[F, FitError, Trained[Model]] =
    if data.size > Int.MaxValue.toLong then
      EitherT.leftT(
        context.stagePath.failure(
          RidgeBackendError.TooManyRows(data.size)
        )
      )
    else
      val values = new Array[Double](data.size.toInt)
      var index = 0
      data.data.foreachRow { (_, example) =>
        values(index) = weightOf(example.meta)
        index += 1
      }
      backend
        .solve(
          data,
          coordinates,
          config,
          RowWeights.validatedByLearner(
            IArray.unsafeFromArray(values)
          ),
          BackendContext.from(context)
        )
        .map { solution =>
          val model = new RidgeModel(solution, coordinates, context.stagePath)
          context.complete(
            model,
            data,
            ComponentDescriptor(
              ComponentId("alder.ridge.weighted"),
              ComponentVersion("0.1.0-SNAPSHOT"),
              AuditValue.record(
                "penalty" -> AuditValue.decimal(config.penalty),
                "fitIntercept" -> AuditValue.bool(config.fitIntercept),
                "tolerance" -> AuditValue.decimal(config.tolerance),
                "weights" -> AuditValue.text("ByRow")
              ),
              backend.fingerprint
            )
          )
        }

package alder.preprocess

import alder.data.{CoordinateError, Coordinates}
import alder.kernel.*
import cats.Applicative
import cats.data.EitherT

/** Centered-and-scaled representation of `A`. */
opaque type Standardized[A] = A

object Standardized:
  private[alder] def wrap[A](value: A): Standardized[A] = value
  private[alder] def unwrap[A](value: Standardized[A]): A = value

  given [A](using coordinates: Coordinates[A]): Coordinates[Standardized[A]] =
    coordinates.imap(wrap)(unwrap)

/** Scale-only representation of `A`.
  *
  * This distinct brand is the 0.1 sparse-policy boundary: scale-only
  * preprocessing preserves structural zeros and exposes no centering switch.
  * A public sparse container remains deliberately deferred.
  */
opaque type Scaled[A] = A

object Scaled:
  private[alder] def wrap[A](value: A): Scaled[A] = value
  private[alder] def unwrap[A](value: Scaled[A]): A = value

  given [A](using coordinates: Coordinates[A]): Coordinates[Scaled[A]] =
    coordinates.imap(wrap)(unwrap)

enum ZeroVariance derives CanEqual:
  case Reject
  case EmitZero

enum ScaleFitError derives CanEqual:
  case CoordinateFailure(row: RowId, cause: CoordinateError)
  case NonFinite(row: RowId, coordinate: String, value: Double)
  case NonFiniteMoment(coordinate: String)
  case ConstantCoordinate(coordinate: String)

enum ScaleRunError derives CanEqual:
  case CoordinateFailure(cause: CoordinateError)
  case NonFiniteInput(coordinate: String, value: Double)
  case NonFiniteOutput(coordinate: String)

/** Stable population-moment standardization with explicit constant-coordinate
  * policy. The fitted artifact centers and scales each coordinate.
  */
final class StandardScaler[F[_], A](
    val zeroVariance: ZeroVariance
)(using Applicative[F], Coordinates[A])
    extends Transform[F, A, Standardized[A]]:

  type FitError = ScaleFitError | ScaleRunError
  type RunError = ScaleRunError
  type Fitted = Standardizer[A]

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, A]
  )(using context: FitContext): FitResult[
    F,
    FitError,
    Prepared[Preparation.Reusable, U, Standardizer[A], Standardized[A]]
  ] =
    val result = for
      moments <- Moments
        .compute(data)
        .left
        .map(error => context.stagePath.failure[FitError](error))
      inverse <- moments
        .inverseStandardDeviation(zeroVariance)
        .left
        .map(error => context.stagePath.failure[FitError](error))
      pipe = new Standardizer[A](
        moments.mean,
        inverse,
        context.stagePath
      )
      trained = context.complete(pipe, data, ScalerComponents.centered(zeroVariance))
      prepared <- Prepared
        .replayed(
          trained,
          data,
          PreparationLineage.leaf(
            context.stagePath,
            PreparationScopeTag.Reusable
          )
        )
        .left
        .map(_.widen[FitError])
    yield prepared
    EitherT.fromEither(result)

/** Stable population-moment scaling without centering.
  *
  * There is intentionally no centering parameter: `0 * invStd` remains zero,
  * which makes this the lawful preprocessing shape for a future `Sparse[S]`.
  */
final class ScaleOnlyScaler[F[_], A](
    val zeroVariance: ZeroVariance
)(using Applicative[F], Coordinates[A])
    extends Transform[F, A, Scaled[A]]:

  type FitError = ScaleFitError | ScaleRunError
  type RunError = ScaleRunError
  type Fitted = ScaleOnlyStandardizer[A]

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, A]
  )(using context: FitContext): FitResult[
    F,
    FitError,
    Prepared[
      Preparation.Reusable,
      U,
      ScaleOnlyStandardizer[A],
      Scaled[A]
    ]
  ] =
    val result = for
      moments <- Moments
        .compute(data)
        .left
        .map(error => context.stagePath.failure[FitError](error))
      inverse <- moments
        .inverseStandardDeviation(zeroVariance)
        .left
        .map(error => context.stagePath.failure[FitError](error))
      pipe = new ScaleOnlyStandardizer[A](
        inverse,
        context.stagePath
      )
      trained =
        context.complete(pipe, data, ScalerComponents.scaleOnly(zeroVariance))
      prepared <- Prepared
        .replayed(
          trained,
          data,
          PreparationLineage.leaf(
            context.stagePath,
            PreparationScopeTag.Reusable
          )
        )
        .left
        .map(_.widen[FitError])
    yield prepared
    EitherT.fromEither(result)

/** Immutable fitted centered standardizer. */
final class Standardizer[A] private[alder] (
    mean: IArray[Double],
    inverseStandardDeviation: IArray[Double],
    stage: StagePath
)(using coordinates: Coordinates[A])
    extends Pipe[A, ScaleRunError, Standardized[A]]:

  def run(value: A): Either[Failure[ScaleRunError], Standardized[A]] =
    ScalerRun
      .transform(
        value,
        coordinates,
        (raw, index) =>
          (raw(index) - mean(index)) *
            inverseStandardDeviation(index),
        Standardized.wrap
      )
      .left
      .map(stage.failure)

/** Immutable fitted scale-only standardizer. */
final class ScaleOnlyStandardizer[A] private[alder] (
    inverseStandardDeviation: IArray[Double],
    stage: StagePath
)(using coordinates: Coordinates[A])
    extends Pipe[A, ScaleRunError, Scaled[A]]:

  def run(value: A): Either[Failure[ScaleRunError], Scaled[A]] =
    ScalerRun
      .transform(
        value,
        coordinates,
        (raw, index) =>
          raw(index) * inverseStandardDeviation(index),
        Scaled.wrap
      )
      .left
      .map(stage.failure)

private object ScalerRun:
  def transform[A, B](
      value: A,
      coordinates: Coordinates[A],
      scaledAt: (IArray[Double], Int) => Double,
      brand: A => B
  ): Either[ScaleRunError, B] =
    coordinates
      .read(value)
      .left
      .map(ScaleRunError.CoordinateFailure.apply)
      .flatMap { raw =>
        firstNonFinite(raw, coordinates.names) match
          case Some((name, invalid)) =>
            Left(ScaleRunError.NonFiniteInput(name, invalid))
          case None =>
            val scaled = IArray.tabulate(raw.length)(index =>
              scaledAt(raw, index)
            )
            firstNonFinite(scaled, coordinates.names) match
              case Some((name, _)) =>
                Left(ScaleRunError.NonFiniteOutput(name))
              case None =>
                coordinates
                  .build(scaled)
                  .left
                  .map(ScaleRunError.CoordinateFailure.apply)
                  .map(brand)
      }

  private def firstNonFinite(
      values: IArray[Double],
      names: IArray[String]
  ): Option[(String, Double)] =
    var index = 0
    var result: Option[(String, Double)] = None
    while index < values.length && result.isEmpty do
      val value = values(index)
      if !value.isFinite then result = Some((names(index), value))
      index += 1
    result

private final class Moments(
    val mean: IArray[Double],
    private val m2: IArray[Double],
    private val count: Long,
    private val names: IArray[String]
):
  def inverseStandardDeviation(
      policy: ZeroVariance
  ): Either[ScaleFitError, IArray[Double]] =
    val inverse = new Array[Double](mean.length)
    var index = 0
    var error: Option[ScaleFitError] = None
    while index < mean.length && error.isEmpty do
      val variance = m2(index) / count.toDouble
      if !variance.isFinite || variance < 0.0 then
        error = Some(ScaleFitError.NonFiniteMoment(names(index)))
      else if variance == 0.0 then
        policy match
          case ZeroVariance.Reject =>
            error = Some(ScaleFitError.ConstantCoordinate(names(index)))
          case ZeroVariance.EmitZero =>
            inverse(index) = 0.0
      else
        val value = 1.0 / math.sqrt(variance)
        if value.isFinite then inverse(index) = value
        else
          error = Some(ScaleFitError.NonFiniteMoment(names(index)))
      index += 1
    error match
      case Some(value) => Left(value)
      case None        => Right(IArray.unsafeFromArray(inverse))

private object Moments:
  def compute[U <: Use.Fit, A](
      data: NonEmptyData[U, A]
  )(using coordinates: Coordinates[A]): Either[ScaleFitError, Moments] =
    val means = new Array[Double](coordinates.size)
    val m2 = new Array[Double](coordinates.size)
    val result = data.data.foldRows[
      Either[ScaleFitError, Long]
    ](Right(0L)) {
      case (left @ Left(_), _, _) => left
      case (Right(count), row, value) =>
        coordinates
          .read(value)
          .left
          .map(ScaleFitError.CoordinateFailure(row, _))
          .flatMap { values =>
            update(values, coordinates.names, row, count, means, m2)
          }
    }
    result.map(count =>
      new Moments(
        IArray.unsafeFromArray(means),
        IArray.unsafeFromArray(m2),
        count,
        coordinates.names
      )
    )

  private def update(
      values: IArray[Double],
      names: IArray[String],
      row: RowId,
      priorCount: Long,
      means: Array[Double],
      m2: Array[Double]
  ): Either[ScaleFitError, Long] =
    val count = priorCount + 1L
    var index = 0
    var error: Option[ScaleFitError] = None
    while index < values.length && error.isEmpty do
      val value = values(index)
      if !value.isFinite then
        error = Some(ScaleFitError.NonFinite(row, names(index), value))
      else
        val delta = value - means(index)
        val nextMean = means(index) + delta / count.toDouble
        val nextM2 = m2(index) + delta * (value - nextMean)
        if !nextMean.isFinite || !nextM2.isFinite then
          error = Some(ScaleFitError.NonFiniteMoment(names(index)))
        else
          means(index) = nextMean
          m2(index) = nextM2
      index += 1
    error match
      case Some(value) => Left(value)
      case None        => Right(count)

private object ScalerComponents:
  private val backend =
    BackendFingerprint(
      "alder.stable-moments",
      "welford-population-v1",
      AuditValue.record()
    )

  def centered(policy: ZeroVariance): ComponentDescriptor =
    descriptor("alder.preprocess.standard-scaler", policy, centered = true)

  def scaleOnly(policy: ZeroVariance): ComponentDescriptor =
    descriptor("alder.preprocess.scale-only-scaler", policy, centered = false)

  private def descriptor(
      id: String,
      policy: ZeroVariance,
      centered: Boolean
  ): ComponentDescriptor =
    ComponentDescriptor(
      ComponentId(id),
      ComponentVersion("0.1.0-SNAPSHOT"),
      AuditValue.record(
        "zeroVariance" -> AuditValue.text(policyName(policy)),
        "centered" -> AuditValue.bool(centered),
        "variance" -> AuditValue.text("population")
      ),
      backend
    )

  private def policyName(policy: ZeroVariance): String =
    policy match
      case ZeroVariance.Reject   => "reject"
      case ZeroVariance.EmitZero => "emit-zero"

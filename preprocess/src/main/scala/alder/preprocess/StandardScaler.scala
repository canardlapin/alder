package alder.preprocess

import alder.data.{
  CoordinateError,
  Coordinates,
  Dense,
  FeatureSchema,
  FeatureView,
  SchemaError
}
import alder.kernel.*
import cats.{Applicative, Id}

/** Phantom brand for dense coordinates produced by centered standardization of
  * `A`. The brand retains the source type without claiming the original field
  * representations remain valid.
  */
sealed trait Standardized[A]

object Standardized:
  /** Feature schema for standardized dense coordinates of `A`. */
  def schema[A](using
      view: FeatureView[A]
  ): Either[SchemaError, FeatureSchema[Standardized[A]]] =
    FeatureSchema.named[Standardized[A]](view.names)

  /** Coordinates for `Dense[Standardized[A]]` derived from `FeatureView[A]`. */
  def coordinates[A](using
      view: FeatureView[A]
  ): Either[SchemaError, Coordinates[Dense[Standardized[A]]]] =
    schema[A].map(Dense.coordinates)

/** Phantom brand for dense coordinates produced by scale-only preprocessing. */
sealed trait Scaled[A]

object Scaled:
  def schema[A](using
      view: FeatureView[A]
  ): Either[SchemaError, FeatureSchema[Scaled[A]]] =
    FeatureSchema.named[Scaled[A]](view.names)

  def coordinates[A](using
      view: FeatureView[A]
  ): Either[SchemaError, Coordinates[Dense[Scaled[A]]]] =
    schema[A].map(Dense.coordinates)

enum ZeroVariance derives CanEqual:
  case Reject
  case EmitZero

enum ScaleFitError derives CanEqual:
  case CoordinateFailure(row: RowId, cause: CoordinateError)
  case NonFinite(row: RowId, coordinate: String, value: Double)
  case NonFiniteMoment(coordinate: String)
  case ConstantCoordinate(coordinate: String)
  case Schema(cause: SchemaError)

enum ScaleRunError derives CanEqual:
  case CoordinateFailure(cause: CoordinateError)
  case Dense(cause: alder.data.DenseError)
  case NonFiniteInput(coordinate: String, value: Double)
  case NonFiniteOutput(coordinate: String)

/** Stable population-moment standardization with explicit constant-coordinate
  * policy. Produces dense numerical coordinates rather than rebuilding `A`.
  */
final class StandardScaler[F[_], A](
    val zeroVariance: ZeroVariance,
    private val outputSchema: FeatureSchema[Standardized[A]]
)(using Applicative[F], FeatureView[A])
    extends Transform.Leaf[F, A, Dense[Standardized[A]]]:

  type FitError = ScaleFitError | ScaleRunError
  type RunError = ScaleRunError
  type Fitted = Standardizer[A]

  protected def descriptor: ComponentDescriptor =
    ScalerComponents.centered(zeroVariance, outputSchema)

  protected def replayFailure(
      failure: Failure[RunError]
  ): Failure[FitError] =
    failure.widen[FitError]

  protected def fitPipe[U <: Use.Fit](
      data: NonEmptyData[U, A]
  )(using context: FitContext): Either[Failure[FitError], Fitted] =
    for
      moments <- Moments
        .compute(data)
        .left
        .map(error => context.stagePath.failure[FitError](error))
      inverse <- moments
        .inverseStandardDeviation(zeroVariance)
        .left
        .map(error => context.stagePath.failure[FitError](error))
    yield new Standardizer[A](
      moments.mean,
      inverse,
      outputSchema,
      context.stagePath
    )

object StandardScaler:
  /** Creates a synchronous scaler for applications that do not need an
    * effectful fitting implementation.
    */
  def sync[A](
      zeroVariance: ZeroVariance
  )(using view: FeatureView[A]): Either[ScaleFitError, StandardScaler[Id, A]] =
    Standardized
      .schema[A]
      .left
      .map(ScaleFitError.Schema.apply)
      .map(schema => new StandardScaler[Id, A](zeroVariance, schema))

/** Stable population-moment scaling without centering.
  *
  * There is intentionally no centering parameter: `0 * invStd` remains zero,
  * which makes this the lawful preprocessing shape for a future `Sparse[S]`.
  */
final class ScaleOnlyScaler[F[_], A](
    val zeroVariance: ZeroVariance,
    private val outputSchema: FeatureSchema[Scaled[A]]
)(using Applicative[F], FeatureView[A])
    extends Transform.Leaf[F, A, Dense[Scaled[A]]]:

  type FitError = ScaleFitError | ScaleRunError
  type RunError = ScaleRunError
  type Fitted = ScaleOnlyStandardizer[A]

  protected def descriptor: ComponentDescriptor =
    ScalerComponents.scaleOnly(zeroVariance, outputSchema)

  protected def replayFailure(
      failure: Failure[RunError]
  ): Failure[FitError] =
    failure.widen[FitError]

  protected def fitPipe[U <: Use.Fit](
      data: NonEmptyData[U, A]
  )(using context: FitContext): Either[Failure[FitError], Fitted] =
    for
      moments <- Moments
        .compute(data)
        .left
        .map(error => context.stagePath.failure[FitError](error))
      inverse <- moments
        .inverseStandardDeviation(zeroVariance)
        .left
        .map(error => context.stagePath.failure[FitError](error))
    yield new ScaleOnlyStandardizer[A](
      inverse,
      outputSchema,
      context.stagePath
    )

object ScaleOnlyScaler:
  /** Creates a synchronous scale-only scaler. */
  def sync[A](
      zeroVariance: ZeroVariance
  )(using view: FeatureView[A]): Either[ScaleFitError, ScaleOnlyScaler[Id, A]] =
    Scaled
      .schema[A]
      .left
      .map(ScaleFitError.Schema.apply)
      .map(schema => new ScaleOnlyScaler[Id, A](zeroVariance, schema))

/** Immutable fitted centered standardizer. */
final class Standardizer[A] private[alder] (
    mean: IArray[Double],
    inverseStandardDeviation: IArray[Double],
    schema: FeatureSchema[Standardized[A]],
    stage: StagePath
)(using view: FeatureView[A])
    extends Pipe[A, ScaleRunError, Dense[Standardized[A]]]:

  def run(
      value: A
  ): Either[Failure[ScaleRunError], Dense[Standardized[A]]] =
    ScalerRun
      .transform(
        value,
        view,
        schema,
        (raw, index) =>
          (raw(index) - mean(index)) *
            inverseStandardDeviation(index)
      )
      .left
      .map(stage.failure)

/** Immutable fitted scale-only standardizer. */
final class ScaleOnlyStandardizer[A] private[alder] (
    inverseStandardDeviation: IArray[Double],
    schema: FeatureSchema[Scaled[A]],
    stage: StagePath
)(using view: FeatureView[A])
    extends Pipe[A, ScaleRunError, Dense[Scaled[A]]]:

  def run(value: A): Either[Failure[ScaleRunError], Dense[Scaled[A]]] =
    ScalerRun
      .transform(
        value,
        view,
        schema,
        (raw, index) =>
          raw(index) * inverseStandardDeviation(index)
      )
      .left
      .map(stage.failure)

private object ScalerRun:
  def transform[A, S](
      value: A,
      view: FeatureView[A],
      schema: FeatureSchema[S],
      scaledAt: (IArray[Double], Int) => Double
  ): Either[ScaleRunError, Dense[S]] =
    view
      .read(value)
      .left
      .map(ScaleRunError.CoordinateFailure.apply)
      .flatMap { raw =>
        firstNonFinite(raw, view.names) match
          case Some((name, invalid)) =>
            Left(ScaleRunError.NonFiniteInput(name, invalid))
          case None =>
            val scaled = IArray.tabulate(raw.length)(index =>
              scaledAt(raw, index)
            )
            firstNonFinite(scaled, view.names) match
              case Some((name, _)) =>
                Left(ScaleRunError.NonFiniteOutput(name))
              case None =>
                Dense
                  .from(scaled, schema)
                  .left
                  .map(ScaleRunError.Dense.apply)
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
  )(using view: FeatureView[A]): Either[ScaleFitError, Moments] =
    val means = new Array[Double](view.size)
    val m2 = new Array[Double](view.size)
    val result = data.data.foldRows[
      Either[ScaleFitError, Long]
    ](Right(0L)) {
      case (left @ Left(_), _, _) => left
      case (Right(count), row, value) =>
        view
          .read(value)
          .left
          .map(ScaleFitError.CoordinateFailure(row, _))
          .flatMap { values =>
            update(values, view.names, row, count, means, m2)
          }
    }
    result.map(count =>
      new Moments(
        IArray.unsafeFromArray(means),
        IArray.unsafeFromArray(m2),
        count,
        view.names
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

  def centered[A](
      policy: ZeroVariance,
      schema: FeatureSchema[Standardized[A]]
  ): ComponentDescriptor =
    descriptor(
      "alder.preprocess.standard-scaler",
      policy,
      centered = true,
      schema.fingerprint
    )

  def scaleOnly[A](
      policy: ZeroVariance,
      schema: FeatureSchema[Scaled[A]]
  ): ComponentDescriptor =
    descriptor(
      "alder.preprocess.scale-only-scaler",
      policy,
      centered = false,
      schema.fingerprint
    )

  private def descriptor(
      id: String,
      policy: ZeroVariance,
      centered: Boolean,
      featureFingerprint: SchemaFingerprint
  ): ComponentDescriptor =
    ComponentDescriptor(
      ComponentId(id),
      ComponentVersion("0.1.0-SNAPSHOT"),
      AuditValue.record(
        "zeroVariance" -> AuditValue.text(policyName(policy)),
        "centered" -> AuditValue.bool(centered),
        "variance" -> AuditValue.text("population"),
        "featureFingerprintPolicy" -> AuditValue.text(
          featureFingerprint.policy match
            case FingerprintPolicy.ContentDigest(algorithm) =>
              s"content-digest:$algorithm"
            case FingerprintPolicy.SourceIdentity(uri, version) =>
              s"source-identity:$uri:$version"
            case FingerprintPolicy.Summary(policyId) =>
              s"summary:$policyId"
        ),
        "featureFingerprint" -> AuditValue.text(featureFingerprint.digest)
      ),
      backend
    )

  private def policyName(policy: ZeroVariance): String =
    policy match
      case ZeroVariance.Reject   => "reject"
      case ZeroVariance.EmitZero => "emit-zero"

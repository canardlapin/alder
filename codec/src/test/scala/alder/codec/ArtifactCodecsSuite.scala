package alder.codec

import alder.kernel.*
import alder.laws.ArtifactCodecTests
import alder.testkit.TestData
import cats.Id
import cats.data.EitherT
import cats.kernel.Eq
import munit.DisciplineSuite

enum ShiftError derives CanEqual:
  case NonFinite

final class ShiftPipe(
    val shift: Double,
    stage: StagePath
) extends Pipe[Double, ShiftError, Double]:
  def run(value: Double): Either[Failure[ShiftError], Double] =
    val shifted = value - shift
    if shifted.isFinite then Right(shifted)
    else Left(stage.failure(ShiftError.NonFinite))

final class MeanShift(using cats.Applicative[Id])
    extends Transform[Id, Double, Double]:
  type FitError = ShiftError
  type RunError = ShiftError
  type Fitted = ShiftPipe

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Double]
  )(using context: FitContext): FitResult[
    Id,
    ShiftError,
    Prepared[Preparation.Reusable, U, ShiftPipe, Double]
  ] =
    val (sum, count) =
      data.data.foldRows((0.0, 0L)) {
        case ((total, size), _, value) =>
          (total + value, size + 1L)
      }
    val pipe = new ShiftPipe(
      sum / count.toDouble,
      context.stagePath
    )
    val trained = context.complete(pipe, data, MeanShift.descriptor)
    EitherT.fromEither(
      Prepared.replayed[U, ShiftError, Double, Double, ShiftPipe](
        trained,
        data,
        PreparationLineage.leaf(
          context.stagePath,
          PreparationScopeTag.Reusable
        )
      )
    )

object MeanShift:
  val descriptor: ComponentDescriptor =
    ComponentDescriptor(
      ComponentId("alder.codec.mean-shift"),
      ComponentVersion("1"),
      AuditValue.record(),
      BackendFingerprint("pure", "1", AuditValue.record())
    )

class ArtifactCodecsSuite extends DisciplineSuite:
  private def format(id: String): ArtifactFormat =
    ArtifactFormat.create(id, 1) match
      case Left(error)  => fail(s"invalid codec format: $error")
      case Right(value) => value

  private def encodeDouble(value: Double): IArray[Byte] =
    val bits = java.lang.Double.doubleToRawLongBits(value)
    IArray.tabulate(8)(index =>
      (bits >>> (56 - index * 8)).toByte
    )

  private def decodeDouble(
      bytes: IArray[Byte]
  ): Either[CodecError, Double] =
    if bytes.length != 8 then
      Left(CodecError.Malformed("unexpected double payload"))
    else
      var bits = 0L
      var index = 0
      while index < bytes.length do
        bits = (bits << 8) | (bytes(index).toLong & 0xffL)
        index += 1
      Right(java.lang.Double.longBitsToDouble(bits))

  private given Eq[Either[Failure[ShiftError], Double]] =
    Eq.fromUniversalEquals

  private given shiftCodec: ArtifactCodec[ShiftPipe] =
    ArtifactCodecs.versioned(format("alder.codec.shift"))(
      pipe => Right(encodeDouble(pipe.shift)),
      bytes =>
        decodeDouble(bytes).map(value =>
          new ShiftPipe(value, StagePath.root)
        )
    )

  private val context =
    FitContext.root(
      Seed(41L),
      PlanFingerprint("codec-chain-law"),
      SchemaFingerprint("double"),
      NumericMode.Deterministic
    )

  private val data = TestData.nonEmpty[Use.Train, Double](
    Vector(
      RowId(0L) -> 1.0,
      RowId(1L) -> 2.0,
      RowId(2L) -> 6.0
    ),
    new DataFingerprint(
      FingerprintPolicy.Summary("codec-law"),
      "training"
    )
  ) match
    case Some(value) => value
    case None        => fail("codec fixture must be nonempty")

  private type ShiftChain = Pipe.Chain[
    Double,
    ShiftError,
    Double,
    ShiftError,
    Double,
    ShiftPipe,
    ShiftPipe
  ]

  private val firstTransform = new MeanShift()
  private val secondTransform = new MeanShift()

  private val trained: Trained[ShiftChain] =
    firstTransform
      .andThen[Double, MeanShift](secondTransform)
      .fit(data)(using context)
      .value match
      case Left(failure) =>
        fail(s"unexpected codec fixture failure: $failure")
      case Right(prepared) => prepared.fitted

  private val chainCodec: ArtifactCodec[ShiftChain] =
    import ArtifactCodecs.given
    summon

  checkAll(
    "derived Pipe.Chain ArtifactCodec",
    new ArtifactCodecTests[
      Double,
      ShiftError,
      Double,
      ShiftChain
    ](
      chainCodec,
      trained,
      Vector(-1.0, 0.0, 4.0)
    ).all
  )

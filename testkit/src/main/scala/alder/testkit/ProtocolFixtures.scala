package alder.testkit

import alder.kernel.*
import cats.Id
import cats.data.EitherT
import org.scalacheck.Gen

enum VisibilityRunError derives CanEqual:
  case Rejected(input: Double)

final class VisibilityState(
    val fittedOn: Set[RowId],
    val stage: StagePath
)

final case class VisibilityValue(input: Double, fittedOn: Set[RowId])

/** Instrumented encoder whose output exposes the analysis RowIds visible to
  * its fitted state. This intentionally row-level evidence belongs in testkit,
  * never in production PreparationLineage.
  */
final class VisibilityEncoder(reject: Option[Double] = None)
    extends FoldEncoder[Id, Double, Double, String, VisibilityValue]:
  type State = VisibilityState
  type FitError = Nothing
  type RunError = VisibilityRunError

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[Double, Double, String]]
  )(using context: FitContext): FitResult[
    Id,
    Nothing,
    Trained[VisibilityState]
  ] =
    val ids =
      data.data.foldRows(Set.empty[RowId])((seen, id, _) => seen + id)
    val state = new VisibilityState(ids, context.stagePath)
    EitherT.rightT(context.complete(state, data, VisibilityEncoder.descriptor))

  def encode(
      state: VisibilityState,
      input: Double
  ): Either[Failure[VisibilityRunError], VisibilityValue] =
    reject match
      case Some(value) if value == input =>
        Left(state.stage.failure(VisibilityRunError.Rejected(input)))
      case _ => Right(VisibilityValue(input, state.fittedOn))

object VisibilityEncoder:
  val descriptor: ComponentDescriptor =
    ComponentDescriptor(
      id = ComponentId("alder.testkit.visibility-encoder"),
      version = ComponentVersion("0"),
      parameters = AuditValue.record(),
      backend = BackendFingerprint("pure", "0", AuditValue.record())
    )

object TestData:
  def nonEmpty[U <: Use, A](
      rows: Vector[(RowId, A)],
      fingerprint: DataFingerprint
  ): Option[NonEmptyData[U, A]] =
    if rows.isEmpty then None
    else Some(new NonEmptyData(RowVectorData(rows, fingerprint)))

  /** Builds nonempty data with contiguous private RowIds for plugin tests. */
  def indexed[U <: Use, A](
      values: Vector[A],
      fingerprint: DataFingerprint
  ): Option[NonEmptyData[U, A]] =
    nonEmpty(
      values.zipWithIndex.map { (value, index) =>
        RowId(index.toLong) -> value
      },
      fingerprint
    )

  def rowsOf[U <: Use, A](
      data: NonEmptyData[U, A]
  ): Vector[(RowId, A)] =
    data.data.foldRows(Vector.empty[(RowId, A)])((rows, id, value) =>
      rows :+ (id, value)
    )

object AlderGenerators:
  val finiteDouble: Gen[Double] =
    Gen.chooseNum(-1.0e6, 1.0e6)

  val nonEmptyDoubles
      : Gen[Vector[(RowId, Double)]] =
    Gen
      .nonEmptyListOf(finiteDouble)
      .map(_.toVector.zipWithIndex.map { (value, index) =>
        (RowId(index.toLong), value)
      })

enum ToleranceError derives CanEqual:
  case NonFiniteAbsolute(value: Double)
  case NonFiniteRelative(value: Double)
  case NegativeAbsolute(value: Double)
  case NegativeRelative(value: Double)

/** Explicit absolute/relative tolerance for numerical law suites. */
final class NumericTolerance private (
    val absolute: Double,
    val relative: Double
):
  def equivalent(left: Double, right: Double): Boolean =
    if left == right then true
    else if !java.lang.Double.isFinite(left) ||
        !java.lang.Double.isFinite(right)
    then false
    else
      val difference = math.abs(left - right)
      val scale = math.max(math.abs(left), math.abs(right))
      difference <= math.max(absolute, relative * scale)

object NumericTolerance:
  def apply(
      absolute: Double,
      relative: Double
  ): Either[ToleranceError, NumericTolerance] =
    if !java.lang.Double.isFinite(absolute) then
      Left(ToleranceError.NonFiniteAbsolute(absolute))
    else if !java.lang.Double.isFinite(relative) then
      Left(ToleranceError.NonFiniteRelative(relative))
    else if absolute < 0.0 then
      Left(ToleranceError.NegativeAbsolute(absolute))
    else if relative < 0.0 then
      Left(ToleranceError.NegativeRelative(relative))
    else Right(new NumericTolerance(absolute, relative))

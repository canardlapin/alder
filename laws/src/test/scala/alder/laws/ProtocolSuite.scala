package alder.laws

import alder.kernel.*
import cats.Id
import cats.data.EitherT

enum ToyRunError derives CanEqual:
  case NonFinite

enum ToyFitError derives CanEqual:
  case Replay(cause: ToyRunError)

/** A fitted mean-shift pipe holding the stage identity it received at fit
  * time.
  */
final class ShiftPipe(val shift: Double, stage: StagePath)
    extends Pipe[Double, ToyRunError, Double]:
  def run(value: Double): Either[Failure[ToyRunError], Double] =
    val shifted = value - shift
    if java.lang.Double.isFinite(shifted) then Right(shifted)
    else Left(stage.failure(ToyRunError.NonFinite))

/** Toy target-blind transform: shifts by the training mean. Exercises the full
  * leaf protocol: FitContext.complete, then the correct-by-construction replay
  * factory, with replay failure embedded in FitError.
  */
final class MeanShift[F[_]](using cats.Applicative[F])
    extends Transform[F, Double, Double]:

  type FitError = ToyFitError
  type RunError = ToyRunError
  type Fitted = ShiftPipe

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Double]
  )(using context: FitContext): FitResult[
    F,
    ToyFitError,
    Prepared[Preparation.Reusable, U, ShiftPipe, Double]
  ] =
    val (sum, count) = data.data.foldRows((0.0, 0L)) {
      case ((s, n), _, value) => (s + value, n + 1L)
    }
    val pipe = ShiftPipe(sum / count.toDouble, context.stagePath)
    val trained = context.complete(pipe, data, MeanShift.descriptor)
    val prepared =
      Prepared
        .replayed[U, ToyRunError, Double, Double, ShiftPipe](
          trained,
          data,
          PreparationLineage.leaf(
            context.stagePath,
            PreparationScopeTag.Reusable
          )
        )
        .left
        .map(failure => failure.map(ToyFitError.Replay.apply))
    EitherT.fromEither[F](prepared)

object MeanShift:
  val descriptor: ComponentDescriptor =
    ComponentDescriptor(
      id = ComponentId("alder.test.mean-shift"),
      version = ComponentVersion("0"),
      parameters = AuditValue.record(),
      backend = BackendFingerprint("pure", "0", AuditValue.record())
    )

class ProtocolSuite extends munit.FunSuite:

  private def rootContext: FitContext =
    FitContext.root(
      seed = Seed(7L),
      plan = PlanFingerprint("test-plan"),
      schema = SchemaFingerprint("double"),
      numericMode = NumericMode.Deterministic
    )

  test("leaf fit: prepared rows are the replay of the fitted pipe") {
    val data = TestData.train(1.0, 2.0, 3.0, 6.0)
    val result = MeanShift[Id]().fit(data)(using rootContext).value
    result match
      case Left(failure) => fail(s"unexpected failure: $failure")
      case Right(prepared) =>
        val rows = TestData.rowsOf(prepared.rows)
        assertEquals(rows.map(_._1), Vector(0L, 1L, 2L, 3L))
        // mean of (1,2,3,6) is 3; replayed rows are shifted by it
        assertEquals(rows.map(_._2), Vector(-2.0, -1.0, 0.0, 3.0))
        assertEquals(prepared.fitted.artifact.shift, 3.0)
  }

  test("composed transform: chained pipe, chained audit, distinct stages") {
    val data = TestData.train(1.0, 2.0, 3.0, 6.0)
    val composed = MeanShift[Id]().andThen(MeanShift[Id]())
    val result = composed.fit(data)(using rootContext).value
    result match
      case Left(failure) => fail(s"unexpected failure: $failure")
      case Right(prepared) =>
        // second stage sees zero-mean rows, so its shift is zero
        prepared.fitted.artifact.second match
          case shift: ShiftPipe => assertEquals(shift.shift, 0.0)
          case other            => fail(s"expected ShiftPipe, got $other")
        // serving accepts the original input and applies both stages
        prepared.fitted.artifact.run(4.0) match
          case Right(value)  => assertEquals(value, 1.0)
          case Left(failure) => fail(s"unexpected run failure: $failure")
        // audit composes: two children with distinct derived seeds
        val audit = prepared.fitted.audit
        assertEquals(audit.children.length, 2)
        val childSeeds = audit.children.map(_.seed)
        assert(childSeeds(0) != childSeeds(1))
        // prepared rows equal replay of the composed pipe (input-only law)
        val rows = TestData.rowsOf(prepared.rows)
        assertEquals(rows.map(_._2), Vector(-2.0, -1.0, 0.0, 3.0))
  }

  test("run failure carries the failing stage's path") {
    val data = TestData.train(1.0, 2.0, 3.0, 6.0)
    val composed = MeanShift[Id]().andThen(MeanShift[Id]())
    val result = composed.fit(data)(using rootContext).value
    result match
      case Left(failure) => fail(s"unexpected failure: $failure")
      case Right(prepared) =>
        prepared.fitted.artifact.run(Double.NaN) match
          case Right(value) => fail(s"expected failure, got $value")
          case Left(failure) =>
            // NaN fails in the first stage, at child path /0
            assertEquals(failure.stage, StagePath.root.child(0))
            assertEquals(failure.cause, ToyRunError.NonFinite)
  }

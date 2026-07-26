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

  private def rootContext(
      plan: PlanFingerprint = PlanFingerprint("test-plan")
  ): FitContext =
    FitContext.root(
      seed = Seed(7L),
      plan = plan,
      schema = SchemaFingerprint("double"),
      numericMode = NumericMode.Deterministic
    )

  test("leaf fit: prepared rows are the replay of the fitted pipe") {
    val data = TestData.train(1.0, 2.0, 3.0, 6.0)
    val result = MeanShift[Id]().fit(data)(using rootContext()).value
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
    val result = composed.fit(data)(using rootContext()).value
    result match
      case Left(failure) => fail(s"unexpected failure: $failure")
      case Right(prepared) =>
        // second stage sees zero-mean rows, so its shift is zero
        assertEquals(
          prepared.fitted.artifact.second.shift,
          0.0
        )
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
    val result = composed.fit(data)(using rootContext()).value
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

  test("transform association preserves flat audit, lineage, seeds, and output") {
    val data = TestData.train(1.0, 2.0, 3.0, 6.0)
    val a = MeanShift[Id]()
    val b = MeanShift[Id]()
    val c = MeanShift[Id]()
    val leftAssociated = a.andThen(b).andThen(c)
    val rightAssociated = a.andThen(b.andThen(c))

    val left = leftAssociated.fit(data)(using rootContext()).value
    val right = rightAssociated.fit(data)(using rootContext()).value

    (left, right) match
      case (Right(leftPrepared), Right(rightPrepared)) =>
        val leftAudit = leftPrepared.fitted.audit
        val rightAudit = rightPrepared.fitted.audit
        assertEquals(leftAudit.children.length, 3)
        assertEquals(rightAudit.children.length, 3)
        assertEquals(
          leftAudit.children.map(_.preparation.stage),
          rightAudit.children.map(_.preparation.stage)
        )
        assertEquals(
          leftAudit.children.map(_.seed),
          rightAudit.children.map(_.seed)
        )
        assertEquals(
          leftPrepared.lineage.children.map(_.stage),
          rightPrepared.lineage.children.map(_.stage)
        )
        assertEquals(
          TestData.rowsOf(leftPrepared.rows),
          TestData.rowsOf(rightPrepared.rows)
        )
        assertEquals(
          leftPrepared.fitted.artifact.run(4.0),
          rightPrepared.fitted.artifact.run(4.0)
        )
      case (Left(failure), _) => fail(s"unexpected left failure: $failure")
      case (_, Left(failure)) => fail(s"unexpected right failure: $failure")
  }

  test("derived stage seeds include the normalized plan fingerprint") {
    val data = TestData.train(1.0, 2.0, 3.0, 6.0)
    val transform = MeanShift[Id]().andThen(MeanShift[Id]())
    val first =
      transform
        .fit(data)(using rootContext(PlanFingerprint("first-plan")))
        .value
    val second =
      transform
        .fit(data)(using rootContext(PlanFingerprint("second-plan")))
        .value

    (first, second) match
      case (Right(firstPrepared), Right(secondPrepared)) =>
        assertNotEquals(
          firstPrepared.fitted.audit.children.map(_.seed),
          secondPrepared.fitted.audit.children.map(_.seed)
        )
      case (Left(failure), _) => fail(s"unexpected first failure: $failure")
      case (_, Left(failure)) => fail(s"unexpected second failure: $failure")
  }

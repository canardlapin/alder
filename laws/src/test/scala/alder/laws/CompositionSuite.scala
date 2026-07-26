package alder.laws

import alder.kernel.*
import cats.Id
import cats.data.EitherT

enum EncoderRunError derives CanEqual:
  case NonFinite

final class EncoderState(val targetMean: Double, val stage: StagePath)

/** Test encoder whose state and runtime failures retain the fit stage. */
final class TargetMeanEncoder extends FoldEncoder[
      Id,
      Double,
      Double,
      String,
      Double
    ]:
  type State = EncoderState
  type FitError = Nothing
  type RunError = EncoderRunError

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[Double, Double, String]]
  )(using context: FitContext): FitResult[Id, Nothing, Trained[EncoderState]] =
    val (sum, count) = data.data.foldRows((0.0, 0L)) {
      case ((total, n), _, example) =>
        (total + example.target, n + 1L)
    }
    val state = new EncoderState(sum / count.toDouble, context.stagePath)
    EitherT.rightT(context.complete(state, data, TargetMeanEncoder.descriptor))

  def encode(
      state: EncoderState,
      input: Double
  ): Either[Failure[EncoderRunError], Double] =
    val encoded = input + state.targetMean
    if java.lang.Double.isFinite(encoded) then Right(encoded)
    else Left(state.stage.failure(EncoderRunError.NonFinite))

object TargetMeanEncoder:
  val descriptor: ComponentDescriptor =
    ComponentDescriptor(
      id = ComponentId("alder.test.target-mean-encoder"),
      version = ComponentVersion("0"),
      parameters = AuditValue.record(),
      backend = BackendFingerprint("pure", "0", AuditValue.record())
    )

final class SummaryPipe(
    val inputSum: Double,
    val targetSum: Double,
    val metadata: Vector[String]
) extends Pipe[Double, Nothing, Double]:
  def run(value: Double): Either[Failure[Nothing], Double] =
    Right(value + targetSum)

final class SummaryLearner
    extends Learner[Id, Double, Double, String, Double]:
  type FitError = Nothing
  type RunError = Nothing
  type Model = SummaryPipe

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[Double, Double, String]]
  )(using context: FitContext): FitResult[Id, Nothing, Trained[SummaryPipe]] =
    val summary = data.data.foldRows((0.0, 0.0, Vector.empty[String])) {
      case ((inputs, targets, metadata), _, example) =>
        (
          inputs + example.input,
          targets + example.target,
          metadata :+ example.meta
        )
    }
    val pipe = new SummaryPipe(summary._1, summary._2, summary._3)
    EitherT.rightT(context.complete(pipe, data, SummaryLearner.descriptor))

object SummaryLearner:
  val descriptor: ComponentDescriptor =
    ComponentDescriptor(
      id = ComponentId("alder.test.summary-learner"),
      version = ComponentVersion("0"),
      parameters = AuditValue.record(),
      backend = BackendFingerprint("pure", "0", AuditValue.record())
    )

class CompositionSuite extends munit.FunSuite:

  private def rootContext: FitContext =
    FitContext.root(
      seed = Seed(19L),
      plan = PlanFingerprint("composition-suite"),
      schema = SchemaFingerprint("example-double"),
      numericMode = NumericMode.Deterministic
    )

  private def examples
      : NonEmptyData[Use.Train, Example[Double, Double, String]] =
    TestData.examples(
      (1.0, 10.0, "a"),
      (2.0, 20.0, "b"),
      (6.0, 30.0, "c")
    )

  test("inputOnly preserves Reusable scope, RowIds, targets, and metadata") {
    val lifted = FeatureMap.inputOnly[
      Id,
      Double,
      Double,
      String,
      Double,
      MeanShift[Id]
    ](MeanShift[Id]())
    val result = lifted.fit(examples)(using rootContext).value

    result match
      case Left(failure) => fail(s"unexpected failure: $failure")
      case Right(prepared) =>
        val reusable
            : Prepared[
              Preparation.Reusable,
              Use.Train,
              lifted.Fitted,
              Example[Double, Double, String]
            ] = prepared
        val rows = TestData.rowsOf(reusable.rows)
        assertEquals(rows.map(_._1), Vector(0L, 1L, 2L))
        assertEquals(rows.map(_._2.target), Vector(10.0, 20.0, 30.0))
        assertEquals(rows.map(_._2.meta), Vector("a", "b", "c"))
        assertEquals(rows.map(_._2.input), Vector(-2.0, -1.0, 3.0))
  }

  test("total FeatureMap output mapping preserves Reusable scope") {
    val mapped = FeatureMap
      .inputOnly[
        Id,
        Double,
        Double,
        String,
        Double,
        MeanShift[Id]
      ](MeanShift[Id]())
      .mapOutput(value => value * 2.0)
    val result = mapped.fit(examples)(using rootContext).value

    result match
      case Left(failure) => fail(s"unexpected failure: $failure")
      case Right(prepared) =>
        val reusable
            : Prepared[
              Preparation.Reusable,
              Use.Train,
              mapped.Fitted,
              Example[Double, Double, String]
            ] = prepared
        assertEquals(
          TestData.rowsOf(reusable.rows).map(_._2.input),
          Vector(-4.0, -2.0, 6.0)
        )
        assertEquals(reusable.fitted.audit.children.length, 1)
  }

  test("Transform then FeatureMap composes fitted pipe and preparation") {
    val second = FeatureMap.inputOnly[
      Id,
      Double,
      Double,
      String,
      Double,
      MeanShift[Id]
    ](MeanShift[Id]())
    val composed = MeanShift[Id]().andThen(second)
    val result = composed.fit(examples)(using rootContext).value

    result match
      case Left(failure) => fail(s"unexpected failure: $failure")
      case Right(prepared) =>
        assertEquals(
          TestData.rowsOf(prepared.rows).map(_._2.input),
          Vector(-2.0, -1.0, 3.0)
        )
        assertEquals(prepared.fitted.artifact.run(4.0), Right(1.0))
        assertEquals(prepared.fitted.audit.children.length, 2)
        assertEquals(
          prepared.fitted.audit.children.map(_.preparation.stage),
          Vector(StagePath.root.child(0), StagePath.root.child(1))
        )
  }

  test("Transform and FeatureMap association preserves normalized stages") {
    val first = MeanShift[Id]()
    val second = MeanShift[Id]()
    val feature = FeatureMap.inputOnly[
      Id,
      Double,
      Double,
      String,
      Double,
      MeanShift[Id]
    ](MeanShift[Id]())
    val left = first.andThen(second).andThen(feature)
    val right = first.andThen(second.andThen(feature))
    val leftResult = left.fit(examples)(using rootContext).value
    val rightResult = right.fit(examples)(using rootContext).value

    (leftResult, rightResult) match
      case (Right(leftPrepared), Right(rightPrepared)) =>
        assertEquals(leftPrepared.fitted.audit.children.length, 3)
        assertEquals(rightPrepared.fitted.audit.children.length, 3)
        assertEquals(
          leftPrepared.fitted.audit.children.map(_.preparation.stage),
          rightPrepared.fitted.audit.children.map(_.preparation.stage)
        )
        assertEquals(
          leftPrepared.fitted.audit.children.map(_.seed),
          rightPrepared.fitted.audit.children.map(_.seed)
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

  test("Transform then Learner preserves supervised fields and is terminal") {
    val workflow = MeanShift[Id]().learnWith(new SummaryLearner)
    val result = workflow.fit(examples)(using rootContext).value

    result match
      case Left(failure) => fail(s"unexpected failure: $failure")
      case Right(trained) =>
        val summary = trained.artifact.second
        assertEquals(summary.inputSum, 0.0)
        assertEquals(summary.targetSum, 60.0)
        assertEquals(summary.metadata, Vector("a", "b", "c"))
        assertEquals(trained.artifact.run(4.0), Right(61.0))
        assertEquals(trained.audit.children.length, 2)
  }

  test("Transform workflow association preserves normalized stages") {
    val first = MeanShift[Id]()
    val second = MeanShift[Id]()
    val left = first.andThen(second).learnWith(new SummaryLearner)
    val right = first
      .andThen(
        FeatureMap.inputOnly[
          Id,
          Double,
          Double,
          String,
          Double,
          MeanShift[Id]
        ](second)
      )
      .learnWith(new SummaryLearner)
    val leftResult = left.fit(examples)(using rootContext).value
    val rightResult = right.fit(examples)(using rootContext).value

    (leftResult, rightResult) match
      case (Right(leftTrained), Right(rightTrained)) =>
        assertEquals(leftTrained.audit.children.length, 3)
        assertEquals(rightTrained.audit.children.length, 3)
        assertEquals(
          leftTrained.audit.children.map(_.preparation.stage),
          rightTrained.audit.children.map(_.preparation.stage)
        )
        assertEquals(
          leftTrained.audit.children.map(_.seed),
          rightTrained.audit.children.map(_.seed)
        )
        assertEquals(
          leftTrained.artifact.run(4.0),
          rightTrained.artifact.run(4.0)
        )
      case (Left(failure), _) => fail(s"unexpected left failure: $failure")
      case (_, Left(failure)) => fail(s"unexpected right failure: $failure")
  }

  test("FoldEncoder.andThen retains state audit and runtime provenance") {
    val encoder = new TargetMeanEncoder
    val composed = encoder.andThen(MeanShift[Id]())
    val result = composed.fit(examples)(using rootContext).value

    result match
      case Left(failure) => fail(s"unexpected failure: $failure")
      case Right(trained) =>
        assertEquals(trained.audit.children.length, 2)
        assertEquals(
          trained.audit.children.map(_.preparation.stage),
          Vector(StagePath.root.child(0), StagePath.root.child(1))
        )
        assertEquals(composed.encode(trained.artifact, 4.0), Right(1.0))
        composed.encode(trained.artifact, Double.NaN) match
          case Right(value) => fail(s"expected failure, got $value")
          case Left(failure) =>
            assertEquals(failure.stage, StagePath.root.child(0))
            assertEquals(failure.cause, EncoderRunError.NonFinite)
  }

  test("FoldEncoder analysis-row failure remains a typed fit failure") {
    val invalid = TestData.examples(
      (Double.NaN, 10.0, "a"),
      (2.0, 20.0, "b")
    )
    val result =
      new TargetMeanEncoder()
        .andThen(MeanShift[Id]())
        .fit(invalid)(using rootContext)
        .value

    result match
      case Right(_) => fail("expected analysis-row encode failure")
      case Left(failure) =>
        assertEquals(failure.stage, StagePath.root.child(0))
        assertEquals(failure.cause, EncoderRunError.NonFinite)
  }

  test("FoldEncoder association preserves paths, seeds, audit, and output") {
    val encoder = new TargetMeanEncoder
    val first = MeanShift[Id]()
    val second = MeanShift[Id]()
    val left = encoder.andThen(first).andThen(second)
    val right = encoder.andThen(first.andThen(second))
    val leftResult = left.fit(examples)(using rootContext).value
    val rightResult = right.fit(examples)(using rootContext).value

    (leftResult, rightResult) match
      case (Right(leftTrained), Right(rightTrained)) =>
        assertEquals(leftTrained.audit.children.length, 3)
        assertEquals(rightTrained.audit.children.length, 3)
        assertEquals(
          leftTrained.audit.children.map(_.preparation.stage),
          rightTrained.audit.children.map(_.preparation.stage)
        )
        assertEquals(
          leftTrained.audit.children.map(_.seed),
          rightTrained.audit.children.map(_.seed)
        )
        assertEquals(
          leftTrained.audit.preparation.children.map(_.stage),
          rightTrained.audit.preparation.children.map(_.stage)
        )
        assertEquals(
          left.encode(leftTrained.artifact, 4.0),
          right.encode(rightTrained.artifact, 4.0)
        )
      case (Left(failure), _) => fail(s"unexpected left failure: $failure")
      case (_, Left(failure)) => fail(s"unexpected right failure: $failure")
  }

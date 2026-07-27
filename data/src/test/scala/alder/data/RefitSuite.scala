package alder.data

import alder.kernel.*
import scala.compiletime.testing.typeCheckErrors

class RefitSuite extends munit.FunSuite:
  private val component =
    ComponentDescriptor(
      ComponentId("alder.test.identity"),
      ComponentVersion("1"),
      AuditValue.record(),
      BackendFingerprint("test", "1", AuditValue.record())
    )

  private val context =
    FitContext.root(
      Seed(77L),
      PlanFingerprint("prediction-suite"),
      SchemaFingerprint("double"),
      NumericMode.Deterministic
    )

  private def fingerprint(label: String): DataFingerprint =
    new DataFingerprint(FingerprintPolicy.Summary("refit-test"), label)

  private def data[U <: Use](
      startId: Long,
      label: String,
      values: Double*
  ): NonEmptyData[U, Double] =
    val rows = values.toVector.zipWithIndex.map { (value, index) =>
      (RowId(startId + index.toLong), value)
    }
    new NonEmptyData(
      new InMemoryData[U, Double](rows, fingerprint(label))
    )

  private def identity(
      fittedOn: NonEmptyData[Use.Fit, Double]
  ): Trained[Pipe[Double, Nothing, Double]] =
    context.complete(Pipe.identity[Double], fittedOn, component)

  private def validationResult(
      train: NonEmptyData[Use.Train, Double],
      validation: NonEmptyData[Use.Validation, Double]
  ): PredictionResult[Use.Validation, Double, Double] =
    val sources =
      EvaluationSources.validation(train, validation.data) match
        case Left(error)  => fail(s"unexpected source error: $error")
        case Right(value) => value
    Prediction.run(identity(train), sources) match
      case Left(error)  => fail(s"unexpected prediction error: $error")
      case Right(value) => value

  test("prediction covers every held-out RowId but grants no refit authority") {
    val train = data[Use.Train](0L, "train", 1.0, 2.0)
    val validation =
      data[Use.Validation](2L, "validation", 3.0, 4.0)
    val first = validationResult(train, validation)
    val replay = validationResult(train, validation)

    assertEquals(first.receipt.id, replay.receipt.id)
    assertEquals(
      first.predictions.data.foldRows(Vector.empty[(Long, Double)]) {
        (rows, id, value) => rows :+ (id.value, value)
      },
      Vector(2L -> 3.0, 3L -> 4.0)
    )
    assertEquals(first.allObserved.size, 4L)

    val refitErrors = typeCheckErrors(
      """package consumer
import alder.data.*
def illegal(result: PredictionResult[?, ?, ?]) =
  Refit.after(result.receipt)
"""
    )
    assert(refitErrors.nonEmpty)
  }

  test("prediction identity commits to the complete fitted audit") {
    val train = data[Use.Train](0L, "train", 1.0, 2.0)
    val validation =
      data[Use.Validation](2L, "validation", 3.0, 4.0)
    val sources =
      EvaluationSources.validation(train, validation.data) match
        case Left(error)  => fail(s"unexpected source error: $error")
        case Right(value) => value
    val first = Prediction.run(identity(train), sources) match
      case Left(error)  => fail(s"unexpected prediction error: $error")
      case Right(value) => value
    val otherContext =
      FitContext.root(
        Seed(78L),
        PlanFingerprint("prediction-suite"),
        SchemaFingerprint("double"),
        NumericMode.Deterministic
      )
    val second =
      Prediction.run(
        otherContext.complete(
          Pipe.identity[Double],
          train,
          component
        ),
        sources
      ) match
        case Left(error)  => fail(s"unexpected prediction error: $error")
        case Right(value) => value

    assertNotEquals(first.receipt.id, second.receipt.id)
    assertEquals(
      first.predictions.fingerprint.policy,
      FingerprintPolicy.Summary(
        "alder.evaluation-derivation-fnv1a64-v1"
      )
    )
  }

  test("precommitted Test sources retain an honest Train+Test manifest") {
    val train = data[Use.Train](0L, "train", 1.0, 2.0)
    val test = data[Use.Test](2L, "test", 3.0)
    val sources =
      EvaluationSources.precommittedTest(train, test.data) match
        case Left(error)  => fail(s"unexpected source error: $error")
        case Right(value) => value
    assertEquals(
      sources.sources.map(_.role),
      Vector(ObservedSourceRole.Train, ObservedSourceRole.Test)
    )
    Prediction.run(identity(train), sources) match
      case Left(error) => fail(s"unexpected prediction error: $error")
      case Right(result) =>
        assertEquals(result.receipt.role, EvaluationRole.Test)
        assertEquals(result.allObserved.size, 3L)
  }

  test("prediction rejects a model fitted on another source") {
    val expectedTrain = data[Use.Train](0L, "expected-train", 1.0)
    val otherTrain = data[Use.Train](0L, "other-train", 1.0)
    val validation = data[Use.Validation](1L, "validation", 2.0)
    val sources =
      EvaluationSources.validation(expectedTrain, validation.data) match
        case Left(error)  => fail(s"unexpected source error: $error")
        case Right(value) => value

    Prediction.run(identity(otherTrain), sources) match
      case Left(EvaluationError.FitSourceMismatch(expected, actual)) =>
        assertEquals(expected.digest, "expected-train")
        assertEquals(actual.digest, "other-train")
      case other => fail(s"expected fit source mismatch, got $other")
  }

  test("failed prediction emits no PredictionResult or receipt") {
    val train = data[Use.Train](0L, "train", 1.0)
    val validation = data[Use.Validation](1L, "validation", 2.0)
    val sources =
      EvaluationSources.validation(train, validation.data) match
        case Left(error)  => fail(s"unexpected source error: $error")
        case Right(value) => value
    val failing = new Pipe[Double, String, Double]:
      def run(value: Double): Either[Failure[String], Double] =
        val cause = if value == 2.0 then "rejected" else "unexpected"
        Left(StagePath.root.failure(cause))
    val trained = context.complete(failing, train, component)

    Prediction.run(trained, sources) match
      case Left(EvaluationError.PredictionFailed(failure)) =>
        assertEquals(failure.stage, StagePath.root)
        assertEquals(failure.cause, "rejected")
      case other => fail(s"expected prediction failure, got $other")
  }

  test("final-test sources require selected receipt-backed Refit data") {
    val unaudited = data[Use.Refit](0L, "unaudited-refit", 1.0)
    val test = data[Use.Test](1L, "test", 2.0)
    assertEquals(
      EvaluationSources.finalTest(unaudited, test.data),
      Left(RefitError.MissingPriorRefitAudit)
    )
  }

  test("evaluation sources reject duplicate logical rows") {
    val duplicateValidation =
      new InMemoryData[Use.Validation, Double](
        Vector(RowId(2L) -> 2.0, RowId(2L) -> 3.0),
        fingerprint("duplicate-validation")
      )
    val train = data[Use.Train](0L, "train", 1.0)
    assertEquals(
      EvaluationSources.validation(train, duplicateValidation),
      Left(RefitError.DuplicateObservedRow(RowId(2L)))
    )
  }

  test("prediction receipts, observed bundles, and authority are unforgeable") {
    val receiptErrors = typeCheckErrors(
      """package consumer
import alder.data.*
import alder.kernel.*
val receipt = new PredictionReceipt[Use.Validation](
  PredictionReceiptId("forged"),
  Vector.empty,
  EvaluationRole.Validation,
  None
)
"""
    )
    val observedErrors = typeCheckErrors(
      """package consumer
import alder.data.*
import alder.kernel.*
val authority = new PromotionAuthority[Use.Validation]
"""
    )
    val roleErrors = typeCheckErrors(
      """package consumer
import alder.data.*
import alder.kernel.*
def illegal(
  train: NonEmptyData[Use.Train, Double],
  test: Data[Use.Test, Double]
) =
  EvaluationSources.finalTest(train, test)
"""
    )
    assert(receiptErrors.nonEmpty)
    assert(observedErrors.nonEmpty)
    assert(roleErrors.nonEmpty)
  }

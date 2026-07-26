package alder.data

import alder.kernel.*
import scala.compiletime.testing.typeCheckErrors

class RefitSuite extends munit.FunSuite:
  private val component =
    ComponentDescriptor(
      ComponentId("alder.test.identity"),
      ComponentVersion("1"),
      AuditValue.record(),
      BackendFingerprint(
        "test",
        "1",
        AuditValue.record()
      )
    )

  private val context =
    FitContext.root(
      Seed(77L),
      PlanFingerprint("refit-suite"),
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
  ): EvaluationResult[Use.Validation, Double, Double] =
    val sources = EvaluationSources.validation(train, validation.data) match
      case Left(error) => fail(s"unexpected source error: $error")
      case Right(value) => value
    Evaluation.run(identity(train), sources) match
      case Left(error) => fail(s"unexpected evaluation error: $error")
      case Right(value) => value

  test("successful evaluation authorizes the exact observed sources once") {
    val train = data[Use.Train](0L, "train", 1.0, 2.0)
    val validation = data[Use.Validation](2L, "validation", 3.0)
    val first = validationResult(train, validation)
    val second = validationResult(train, validation)
    assertEquals(first.allObserved.fingerprint.digest, "de01c48817fdd895")
    assertEquals(first.receipt.id.render, "271b7232a196cf68")
    assertEquals(second.receipt.id, first.receipt.id)

    Refit.after(first.receipt).from(second.allObserved) match
      case Left(RefitError.ReceiptMismatch(id)) =>
        assertEquals(id, first.receipt.id)
      case other => fail(s"expected receipt mismatch, got $other")

    val promoted =
      Refit.after(first.receipt).from(first.allObserved) match
        case Left(error) => fail(s"unexpected promotion error: $error")
        case Right(value) => value
    assertEquals(promoted.size, 3L)

    Refit.after(first.receipt).from(first.allObserved) match
      case Left(RefitError.ReceiptAlreadyUsed(id)) =>
        assertEquals(id, first.receipt.id)
      case other => fail(s"expected receipt replay error, got $other")
  }

  test("validation then final-test refits retain exact source audit") {
    val train = data[Use.Train](0L, "train", 1.0, 2.0)
    val validation = data[Use.Validation](2L, "validation", 3.0)
    val test = data[Use.Test](3L, "test", 4.0, 5.0)

    val validationEvaluation = validationResult(train, validation)
    val trainAndValidation =
      Refit
        .after(validationEvaluation.receipt)
        .from(validationEvaluation.allObserved) match
        case Left(error) => fail(s"unexpected validation promotion: $error")
        case Right(value) => value
    val validationRefitModel = identity(trainAndValidation)
    validationRefitModel.audit.refit match
      case None => fail("expected validation refit audit")
      case Some(audit) =>
        assertEquals(
          audit.sources.map(_.role),
          Vector(
            ObservedSourceRole.Train,
            ObservedSourceRole.Validation
          )
        )
        assertEquals(
          audit.claim,
          RefitEvaluationClaim
            .ArtifactNotEvaluatedOnAuthorizingValidation
        )

    val testSources =
      EvaluationSources.finalTest(trainAndValidation, test.data) match
        case Left(error) => fail(s"unexpected test sources: $error")
        case Right(value) => value
    val testEvaluation =
      Evaluation.run(validationRefitModel, testSources) match
        case Left(error) => fail(s"unexpected test evaluation: $error")
        case Right(value) => value
    assertEquals(
      testEvaluation.predictions.data
        .foldRows(Vector.empty[Double])((values, _, value) =>
          values :+ value
        ),
      Vector(4.0, 5.0)
    )

    val allObserved =
      Refit.after(testEvaluation.receipt).from(testEvaluation.allObserved) match
        case Left(error) => fail(s"unexpected final promotion: $error")
        case Right(value) => value
    val finalModel = identity(allObserved)
    finalModel.audit.refit match
      case None => fail("expected final refit audit")
      case Some(audit) =>
        assertEquals(
          audit.sources.map(_.role),
          Vector(
            ObservedSourceRole.Train,
            ObservedSourceRole.Validation,
            ObservedSourceRole.Test
          )
        )
        assertEquals(audit.receipt, testEvaluation.receipt.id)
        assertEquals(
          audit.claim,
          RefitEvaluationClaim.ArtifactNotEvaluatedOnAuthorizingTest
        )
        assertEquals(finalModel.audit.data.digest, allObserved.fingerprint.digest)
  }

  test("evaluation rejects a model fitted on another source") {
    val expectedTrain = data[Use.Train](0L, "expected-train", 1.0)
    val otherTrain = data[Use.Train](0L, "other-train", 1.0)
    val validation = data[Use.Validation](1L, "validation", 2.0)
    val sources =
      EvaluationSources.validation(expectedTrain, validation.data) match
        case Left(error) => fail(s"unexpected source error: $error")
        case Right(value) => value

    Evaluation.run(identity(otherTrain), sources) match
      case Left(EvaluationError.FitSourceMismatch(expected, actual)) =>
        assertEquals(expected.digest, "expected-train")
        assertEquals(actual.digest, "other-train")
      case other => fail(s"expected fit source mismatch, got $other")
  }

  test("failed prediction emits no evaluation result or receipt") {
    val train = data[Use.Train](0L, "train", 1.0)
    val validation = data[Use.Validation](1L, "validation", 2.0)
    val sources =
      EvaluationSources.validation(train, validation.data) match
        case Left(error) => fail(s"unexpected source error: $error")
        case Right(value) => value
    val failing = new Pipe[Double, String, Double]:
      def run(value: Double): Either[Failure[String], Double] =
        val cause = if value == 2.0 then "rejected" else "unexpected"
        Left(StagePath.root.failure(cause))
    val trained = context.complete(failing, train, component)

    Evaluation.run(trained, sources) match
      case Left(EvaluationError.PredictionFailed(failure)) =>
        assertEquals(failure.stage, StagePath.root)
        assertEquals(failure.cause, "rejected")
      case other => fail(s"expected prediction failure, got $other")
  }

  test("final-test sources require receipt-backed prior refit data") {
    val unaudited = data[Use.Refit](0L, "unaudited-refit", 1.0)
    val test = data[Use.Test](1L, "test", 2.0)
    assertEquals(
      EvaluationSources.finalTest(unaudited, test.data),
      Left(RefitError.MissingPriorRefitAudit)
    )
  }

  test("evaluation sources reject duplicate logical rows within any source") {
    val duplicateValidation = new InMemoryData[Use.Validation, Double](
      Vector(
        RowId(2L) -> 2.0,
        RowId(2L) -> 3.0
      ),
      fingerprint("duplicate-validation")
    )
    val train = data[Use.Train](0L, "train", 1.0)
    assertEquals(
      EvaluationSources.validation(train, duplicateValidation),
      Left(RefitError.DuplicateObservedRow(RowId(2L)))
    )
  }

  test("receipt, observed bundle, and promotion constructors are unforgeable") {
    val receiptErrors = typeCheckErrors(
      """package consumer
import alder.data.*
import alder.kernel.*
val authority = new ReceiptAuthority
val receipt = new EvaluationReceipt(
  EvaluationReceiptId("forged"),
  Vector.empty,
  EvaluationRole.Validation,
  authority
)
"""
    )
    val observedErrors = typeCheckErrors(
      """package consumer
import alder.data.*
import alder.kernel.*
def illegal(data: Data[Use.Unsplit, Double], receipt: EvaluationReceipt) =
  Refit.after(receipt).from(data)
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

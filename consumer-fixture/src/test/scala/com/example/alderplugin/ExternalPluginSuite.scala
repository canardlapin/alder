package com.example.alderplugin

import alder.kernel.*
import alder.testkit.TestData
import cats.Id
import scala.compiletime.testing.typeCheckErrors

class ExternalPluginSuite extends munit.FunSuite:
  private val context =
    FitContext.root(
      Seed(3L),
      PlanFingerprint("consumer-fixture"),
      SchemaFingerprint("double"),
      NumericMode.Deterministic
    )

  private def trainDoubles(
      values: Double*
  ): NonEmptyData[Use.Train, Double] =
    TestData
      .indexed(
        values.toVector,
        new DataFingerprint(
          FingerprintPolicy.Summary("consumer-fixture"),
          "doubles"
        )
      )
      .get

  private def trainExamples(
      values: Vector[Example[Double, Double, Unit]]
  ): NonEmptyData[Use.Train, Example[Double, Double, Unit]] =
    TestData
      .indexed(
        values,
        new DataFingerprint(
          FingerprintPolicy.Summary("consumer-fixture"),
          "examples"
        )
      )
      .get

  test("external Transform.Leaf fits through completeTransform") {
    val data = trainDoubles(1.0, 2.0, 3.0)
    val prepared =
      new AddConstant[Id](1.5).fit(data)(using context).value match
        case Left(error)  => fail(s"unexpected fit error: $error")
        case Right(value) => value
    assertEquals(prepared.artifact.run(10.0), Right(11.5))
    assertEquals(
      prepared.fitted.audit.component.id.render,
      "com.example.add-constant"
    )
  }

  test("external Learner fits through FitContext.complete") {
    val data =
      trainExamples(
        Vector(
          Example(1.0, 2.0, ()),
          Example(2.0, 4.0, ()),
          Example(3.0, 6.0, ())
        )
      )
    val trained =
      new MeanLearner[Id]().fit(data)(using context).value match
        case Left(error)  => fail(s"unexpected fit error: $error")
        case Right(value) => value
    assertEquals(trained.artifact.run(0.0), Right(4.0))
    assertEquals(
      trained.audit.component.id.render,
      "com.example.mean-learner"
    )
  }

  test("external Transform.learnWith(Learner) fits and retains example component identity") {
    val data =
      trainExamples(
        Vector(
          Example(1.0, 2.0, ()),
          Example(2.0, 4.0, ()),
          Example(3.0, 6.0, ())
        )
      )
    val composed =
      new AddConstant[Id](1.0).learnWith(new MeanLearner[Id]())
    val trained =
      composed.fit(data)(using context).value match
        case Left(error)  => fail(s"unexpected composition fit error: $error")
        case Right(value) => value
    assertEquals(trained.artifact.run(10.0), Right(4.0))
    assertEquals(trained.audit.children.length, 2)
    assertEquals(
      trained.audit.children.map(_.component.id.render),
      Vector("com.example.add-constant", "com.example.mean-learner")
    )
  }

  test("external packages cannot construct Prepared or read protocol rows") {
    val replayErrors = typeCheckErrors(
      """import alder.kernel.*
def illegal[U <: Use.Fit, E, X, Z, P <: Pipe[X, E, Z]](
  fitted: Trained[P],
  data: NonEmptyData[U, X],
  lineage: PreparationLineage
) =
  Prepared.replayed(fitted, data, lineage)
"""
    )
    val rowsErrors = typeCheckErrors(
      """import alder.kernel.*
def illegal[S <: Preparation, U <: Use.Fit, A, B](
  prepared: Prepared[S, U, A, B]
) = prepared.rows
"""
    )
    assert(replayErrors.nonEmpty)
    assert(rowsErrors.nonEmpty)
  }

  test("external packages cannot forge Alder-owned lifecycle evidence") {
    val nonEmptyErrors = typeCheckErrors(
      """import alder.kernel.*
def illegal(data: Data[Use.Train, Double]): NonEmptyData[Use.Train, Double] =
  new NonEmptyData(data)
"""
    )
    val splitErrors = typeCheckErrors(
      """import alder.data.*
import alder.kernel.*
def illegal[A](
  train: NonEmptyData[Use.Train, A],
  validation: NonEmptyData[Use.Validation, A],
  receipt: SplitReceipt
) = new ValidationSplit(train, validation, receipt)
"""
    )
    val predictionReceiptErrors = typeCheckErrors(
      """import alder.data.*
import alder.kernel.*
val receipt = new PredictionReceipt[Use.Validation](
  PredictionReceiptId("forged"),
  Vector.empty,
  EvaluationRole.Validation,
  None
)
"""
    )
    val evaluationReceiptErrors = typeCheckErrors(
      """import alder.application.*
import alder.kernel.*
import alder.metrics.*
def forged(existing: EvaluationReceipt[Use.Test]) =
  new EvaluationReceipt[Use.Test](
    EvaluationReceiptId("forged"),
    PredictionReceiptId("forged"),
    Vector.empty,
    EvaluationRole.Test,
    RegressionMetrics.rmse[Unit].descriptor,
    DataFingerprint.external("forged"),
    existing.priorSelection,
    existing.authority
  )
"""
    )

    val fixtures = Vector(
      "NonEmptyData" -> nonEmptyErrors,
      "ValidationSplit" -> splitErrors,
      "PredictionReceipt" -> predictionReceiptErrors,
      "EvaluationReceipt" -> evaluationReceiptErrors
    )
    fixtures.foreach { case (concept, errors) =>
      assert(errors.nonEmpty, s"$concept was forgeable")
      assert(
        errors.exists(error =>
          error.message.contains(concept) ||
            error.message.toLowerCase.contains("access")
        ),
        clues(concept, errors.map(_.message))
      )
    }
  }

  test("external application package uses only the documented quickstart surface") {
    import alder.quickstart.*

    final case class House(area: Double, bedrooms: Int, age: Double)
        derives Coordinates,
          Schema

    val data = Supervised.fromPairs(
      Vector.tabulate(12) { index =>
        val area = 50.0 + index.toDouble * 8.0
        House(area, 1 + index % 4, 45.0 - index.toDouble * 3.0) ->
          (2.8 * area + 35.0)
      },
      "external-quickstart-v1"
    )
    val candidate =
      Standardize[House](zeroVariance = ZeroVariance.AsZero).learnWith(
        Ridge.lsqr[House](penalty = RidgePenalty.const(0.1))
      )
    val result =
      for
        split <- Validation.fraction(numerator = 1L, denominator = 4L)
        validated <- Experiment
          .validation(
            data = data,
            specification = split,
            seed = Seed(42L),
            plan = "external-quickstart-ridge-v1",
            learner = candidate,
            metric = Metrics.rmse
          )
          .run
      yield validated

    result match
      case Left(error) => fail(s"unexpected external workflow failure: $error")
      case Right(validated) =>
        assertEquals(validated.report.partitions, ExperimentPartitions.Validation(9L, 3L))
        assert(validated.predict(House(100.0, 3, 12.0)).isRight)
  }

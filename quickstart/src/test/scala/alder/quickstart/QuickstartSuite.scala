package alder.quickstart

import alder.application.FitPhase
import alder.data.{DataError, HoldoutSpec, InMemoryData}
import alder.kernel.{
  AuditValue,
  DataFingerprint,
  NonEmptyData,
  RowId,
  Scored,
  Seed,
  StagePath,
  Use
}
import alder.laws.AuditSnapshot
import alder.models.linear.RidgeConfigError
import alder.preprocess.{ScaleFitError, ScaleRunError}
import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

class QuickstartSuite extends FunSuite:
  final case class House(
      area: Double,
      bedrooms: Int,
      age: Double
  ) derives Coordinates,
        Schema

  private val houseRows =
    Vector(
      House(52.0, 1, 55.0)  -> 185.0,
      House(60.0, 1, 40.0)  -> 210.0,
      House(68.0, 2, 35.0)  -> 238.0,
      House(75.0, 2, 25.0)  -> 265.0,
      House(82.0, 2, 22.0)  -> 288.0,
      House(90.0, 2, 15.0)  -> 315.0,
      House(98.0, 3, 18.0)  -> 342.0,
      House(105.0, 3, 12.0) -> 370.0,
      House(110.0, 3, 10.0) -> 390.0,
      House(120.0, 4, 8.0)  -> 430.0,
      House(130.0, 4, 5.0)  -> 470.0,
      House(145.0, 4, 3.0)  -> 525.0
    )

  private def houses(identity: String) =
    Supervised.fromPairs(houseRows, identity)

  private def validation =
    Validation.fraction(numerator = 1L, denominator = 4L) match
      case Left(error)  => fail(s"unexpected validation specification: $error")
      case Right(value) => value

  private def scoredRows(
      values: NonEmptyData[Use.Validation, Scored[Double, Double, Unit]]
  ): Vector[(Long, Double, Double)] =
    values.data.foldRows(Vector.empty) { (rows, id, scored) =>
      rows :+ ((id.value, scored.truth, scored.prediction))
    }

  private def parameter(value: AuditValue, name: String): Option[AuditValue] =
    value match
      case AuditValue.Record(fields) =>
        fields.collectFirst { case (`name`, field) => field }
      case _ => None

  test("standardize plus LSQR ridge validates through Experiment") {
    val data = houses("quickstart-house-prices")

    val scaler    = Standardize[House](zeroVariance = ZeroVariance.AsZero)
    val ridge     = Ridge.lsqr[House](penalty = RidgePenalty.const(0.1))
    val candidate = scaler.learnWith(ridge)
    val result =
      for
        specification <- Validation.fraction(
          numerator = 1L,
          denominator = 4L
        )
        validated <- Experiment
          .validation(
            data = data,
            specification = specification,
            seed = Seed(42L),
            plan = "quickstart-house-ridge-v1",
            learner = candidate,
            metric = Metrics.rmse
          )
          .run
      yield validated

    result match
      case Left(error) =>
        fail(s"unexpected experiment failure: $error")
      case Right(validated) =>
        assertEquals(validated.predictions.size, 3L)
        assertEquals(
          validated.predictions.data.foldRows(0)((count, _, _) => count + 1),
          3
        )
        assert(validated.score.value.isFinite)
        assertEquals(
          validated.report.components.map(_.descriptor.id.render),
          Vector("alder.preprocess.standard-scaler", "alder.ridge")
        )
        assertEquals(
          validated.report.components.map(_.backend.id),
          Vector("alder.stable-moments", "linop4s")
        )
        assertEquals(
          validated.report.components.flatMap(component =>
            parameter(component.descriptor.parameters, "zeroVariance")
          ),
          Vector(AuditValue.text("as-zero"))
        )
        assertEquals(
          validated.report.components.flatMap(component =>
            parameter(component.descriptor.parameters, "penalty")
          ),
          Vector(AuditValue.decimal(0.1))
        )
        validated.select(SingleCandidate).refit match
          case Left(error) => fail(s"unexpected refit failure: $error")
          case Right(refitted) =>
            assert(refitted.predict(House(100.0, 3, 12.0)).isRight)
            assert(refitted.model.audit.children.nonEmpty)
  }

  test("Blueprint and direct composition are observationally equivalent") {
    val data   = houses("quickstart-equivalence")
    val scaler = Standardize[House](zeroVariance = ZeroVariance.AsZero)
    val ridge  = Ridge.lsqr[House](penalty = RidgePenalty.const(0.1))
    val facade =
      Blueprint.supervised[House, Double].via(scaler).learn(ridge).learner
    val direct = scaler.learnWith(ridge)
    val directValidated =
      Experiment
        .validation(
          data = data,
          specification = validation,
          seed = Seed(42L),
          plan = "quickstart-equivalence-v1",
          learner = direct,
          metric = Metrics.rmse
        )
        .run match
        case Left(error)  => fail(s"unexpected direct failure: $error")
        case Right(value) => value
    val facadeValidated =
      Experiment
        .validation(
          data = data,
          specification = validation,
          seed = Seed(42L),
          plan = "quickstart-equivalence-v1",
          learner = facade,
          metric = Metrics.rmse
        )
        .run match
        case Left(error)  => fail(s"unexpected façade failure: $error")
        case Right(value) => value

    assertEquals(
      scoredRows(facadeValidated.predictions),
      scoredRows(directValidated.predictions)
    )
    assertEquals(facadeValidated.score, directValidated.score)
    assert(
      AuditSnapshot.equivalent(
        AuditSnapshot(facadeValidated.audit),
        AuditSnapshot(directValidated.audit)
      )
    )
    assertEquals(
      facadeValidated.evaluation.receipt.id,
      directValidated.evaluation.receipt.id
    )
    assertEquals(facadeValidated.report.seed, directValidated.report.seed)
    assertEquals(
      facadeValidated.report.components.map(_.descriptor.id.render),
      directValidated.report.components.map(_.descriptor.id.render)
    )

    val input   = House(100.0, 3, 12.0)
    val invalid = House(Double.NaN, 3, 12.0)
    assertEquals(facadeValidated.predict(input), directValidated.predict(input))
    (facadeValidated.predict(invalid), directValidated.predict(invalid)) match
      case (Left(facadeFailure), Left(directFailure)) =>
        assertEquals(facadeFailure.stage, directFailure.stage)
        (facadeFailure.cause, directFailure.cause) match
          case (
                ScaleRunError.NonFiniteInput(facadeName, facadeValue),
                ScaleRunError.NonFiniteInput(directName, directValue)
              ) =>
            assertEquals(facadeName, directName)
            assert(facadeValue.isNaN)
            assert(directValue.isNaN)
          case other => fail(s"unexpected prediction failures: $other")
      case other => fail(s"expected matching prediction failures, got $other")
    val predictionData =
      InMemoryData.unsplit(
        Vector(House(70.0, 2, 30.0), input, House(135.0, 4, 4.0)),
        DataFingerprint.external("quickstart-equivalence-predictions")
      )
    assertEquals(
      facadeValidated.predictAll(predictionData),
      directValidated.predictAll(predictionData)
    )

    val facadeSelected = facadeValidated.select(SingleCandidate)
    val directSelected = directValidated.select(SingleCandidate)
    assertEquals(facadeSelected.receipt.id, directSelected.receipt.id)
    val facadeRefitted = facadeSelected.refit match
      case Left(error)  => fail(s"unexpected façade refit failure: $error")
      case Right(value) => value
    val directRefitted = directSelected.refit match
      case Left(error)  => fail(s"unexpected direct refit failure: $error")
      case Right(value) => value
    assert(
      AuditSnapshot.equivalent(
        AuditSnapshot(facadeRefitted.audit),
        AuditSnapshot(directRefitted.audit)
      )
    )
    assertEquals(facadeRefitted.predict(input), directRefitted.predict(input))
  }

  test("Blueprint and direct composition retain the same exact fit failure") {
    val data = Supervised.fromPairs(
      Vector.tabulate(8) { index =>
        House(60.0 + index.toDouble, 1 + index % 3, 10.0) ->
          (200.0 + index.toDouble)
      },
      "quickstart-failure-equivalence"
    )
    val scaler = Standardize[House](zeroVariance = ZeroVariance.Reject)
    val ridge  = Ridge.lsqr[House](penalty = RidgePenalty.const(0.1))
    val direct = scaler.learnWith(ridge)
    val facade =
      Blueprint.supervised[House, Double].via(scaler).learn(ridge).learner
    val directResult =
      Experiment
        .validation(
          data = data,
          specification = validation,
          seed = Seed(11L),
          plan = "quickstart-failure-equivalence-v1",
          learner = direct,
          metric = Metrics.rmse
        )
        .run
    val facadeResult =
      Experiment
        .validation(
          data = data,
          specification = validation,
          seed = Seed(11L),
          plan = "quickstart-failure-equivalence-v1",
          learner = facade,
          metric = Metrics.rmse
        )
        .run

    (facadeResult, directResult) match
      case (Left(facadeError), Left(directError)) =>
        assertEquals(facadeError, directError)
        facadeError match
          case ExperimentFailure.Fit(FitPhase.Candidate, failure) =>
            assertEquals(failure.stage, StagePath(Vector(0)))
            assertEquals(
              failure.cause,
              ScaleFitError.ConstantCoordinate("age")
            )
          case other => fail(s"unexpected staged failure: $other")
      case other => fail(s"expected matching fit failures, got $other")
  }

  test("dynamic ridge configuration retains its typed error") {
    assertEquals(
      Ridge.lsqrChecked[House](penalty = -0.1),
      Left(RidgeConfigError.InvalidPenalty(-0.1))
    )
  }

  test("precommitted holdout scores test without selection") {
    val data = Supervised.fromPairs(
      Vector(
        House(60.0, 1, 40.0)  -> 210.0,
        House(75.0, 2, 25.0)  -> 265.0,
        House(90.0, 2, 15.0)  -> 315.0,
        House(110.0, 3, 10.0) -> 390.0,
        House(130.0, 4, 5.0)  -> 470.0,
        House(150.0, 4, 2.0)  -> 520.0
      ),
      "quickstart-precommitted"
    )

    val scaler    = Standardize[House](zeroVariance = ZeroVariance.AsZero)
    val ridge     = Ridge.lsqr[House](penalty = RidgePenalty.const(0.1))
    val candidate = scaler.learnWith(ridge)
    val result =
      for
        specification <- HoldoutSpec.rows(2L)
        tested <- Experiment
          .precommitted(
            data,
            specification,
            Seed(21L),
            "quickstart-precommitted-v1",
            candidate,
            Metrics.rmse
          )
          .run
      yield tested

    result match
      case Left(error) => fail(s"unexpected precommitted failure: $error")
      case Right(tested) =>
        assertEquals(tested.evaluation.scored.size, 2L)
        assert(tested.score.value.isFinite)
  }

  test("learner-ready Blueprint diagnostics name the unavailable operation") {
    val errors = typeCheckErrors(
      """import alder.application.*
import alder.kernel.*
import cats.Id
def invalid[
  FM <: FeatureMap[Id, Double, Double, Unit, Double],
  T <: Transform[Id, Double, Double]
](ready: Blueprint.LearnerReady[Id, Double, Double, Unit, Double, FM], t: T) =
  ready.via(t)
"""
    )
    assert(errors.nonEmpty)
    assert(
      errors.exists(error =>
        error.message.contains("via") &&
          error.message.contains("LearnerReady")
      ),
      clues(errors.map(_.message))
    )
  }

  test("ordinary configuration and derivation diagnostics name user concepts") {
    val penaltyErrors = typeCheckErrors(
      """import alder.quickstart.*
val penalty = RidgePenalty.const(-0.1)
"""
    )
    assert(
      penaltyErrors.exists(error =>
        error.message.contains("RidgePenalty") &&
          error.message.contains("non-negative")
      ),
      clues(penaltyErrors.map(_.message))
    )

    val featureErrors = typeCheckErrors(
      """import alder.quickstart.*
final case class Labelled(value: String) derives Schema
val learner = Ridge.lsqr[Labelled](penalty = RidgePenalty.const(0.1))
"""
    )
    assert(
      featureErrors.exists(error =>
        error.message.contains("FeatureView") &&
          error.message.contains("Labelled")
      ),
      clues(featureErrors.map(_.message))
    )

    assertEquals(
      Validation.fraction(numerator = 0L, denominator = 4L),
      Left(DataError.InvalidFraction(0L, 4L))
    )
  }

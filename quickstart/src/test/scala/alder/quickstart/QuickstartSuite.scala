package alder.quickstart

import alder.data.HoldoutSpec
import alder.kernel.Seed
import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

class QuickstartSuite extends FunSuite:
  final case class House(
      area: Double,
      bedrooms: Int,
      age: Double
  ) derives Coordinates, Schema

  test("standardize plus LSQR ridge validates through Experiment") {
    val data = Supervised.fromPairs(
      Vector(
        House(60.0, 1, 40.0) -> 210.0,
        House(75.0, 2, 25.0) -> 265.0,
        House(90.0, 2, 15.0) -> 315.0,
        House(110.0, 3, 10.0) -> 390.0,
        House(130.0, 4, 5.0) -> 470.0
      ),
      "quickstart-house-prices"
    )

    val result =
      for
        scaler <- Standardize.emitZero[House]
        ridge <- Ridge.lsqr[House](0.1)
        blueprint =
          Blueprint.supervised[House, Double].via(scaler).learn(ridge)
        specification <- Validation.rows(1L)
        validated <- Experiment
          .validation(
            data,
            specification,
            Seed(42L),
            "quickstart-house-ridge-v1",
            blueprint,
            Metrics.rmse
          )
          .run
      yield validated

    result match
      case Left(error) =>
        fail(s"unexpected experiment failure: $error")
      case Right(validated) =>
        assertEquals(validated.predictions.size, 1L)
        assert(validated.score.value.isFinite)
        validated.select(SingleCandidate).refit match
          case Left(error) => fail(s"unexpected refit failure: $error")
          case Right(refitted) =>
            assert(
              refitted.model.artifact.run(House(100.0, 3, 12.0)).isRight
            )
            assert(refitted.model.audit.children.nonEmpty)
  }

  test("Blueprint.via.learn expands to transform.learnWith") {
    val constructed =
      for
        scaler <- Standardize.emitZero[House]
        ridge <- Ridge.lsqr[House](0.1)
      yield (
        Blueprint.supervised[House, Double].via(scaler).learn(ridge).learner,
        scaler.learnWith(ridge)
      )

    constructed match
      case Left(error) => fail(s"unexpected preset error: $error")
      case Right((facade, direct)) =>
        assertEquals(facade.getClass, direct.getClass)
  }

  test("precommitted holdout scores test without selection") {
    val data = Supervised.fromPairs(
      Vector(
        House(60.0, 1, 40.0) -> 210.0,
        House(75.0, 2, 25.0) -> 265.0,
        House(90.0, 2, 15.0) -> 315.0,
        House(110.0, 3, 10.0) -> 390.0,
        House(130.0, 4, 5.0) -> 470.0,
        House(150.0, 4, 2.0) -> 520.0
      ),
      "quickstart-precommitted"
    )

    val result =
      for
        scaler <- Standardize.emitZero[House]
        ridge <- Ridge.lsqr[House](0.1)
        blueprint =
          Blueprint.supervised[House, Double].via(scaler).learn(ridge)
        specification <- HoldoutSpec.rows(2L)
        tested <- Experiment
          .precommitted(
            data,
            specification,
            Seed(21L),
            "quickstart-precommitted-v1",
            blueprint.learner,
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
    assert(errors.exists(_.message.contains("via")), clues(errors.map(_.message)))
  }

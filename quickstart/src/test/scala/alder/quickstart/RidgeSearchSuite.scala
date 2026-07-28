package alder.quickstart

import alder.data.KFold
import alder.kernel.*
import alder.metrics.RootMeanSquaredError
import alder.models.linear.{RidgeConfig, RidgeRegression}
import alder.ridge.linop4s.Linop4sRidgeBackend
import alder.testkit.TestData
import alder.tune.*
import cats.Id
import munit.FunSuite

class RidgeSearchSuite extends FunSuite:
  final case class Point(x: Double) derives Coordinates, Schema

  test("Search.crossValidated tunes ridge penalty without retaining fold models") {
    val rows =
      Vector.tabulate(30) { index =>
        val x = index.toDouble
        Example(Point(x), 2.0 * x + 1.0, ())
      }
    val data =
      TestData.indexed[Use.Train, Example[Point, Double, Unit]](
        rows,
        DataFingerprint.external("ridge-search")
      ) match
        case Some(value) => value
        case None        => fail("expected data")
    val resampler = KFold[Example[Point, Double, Unit]](3) match
      case Left(error)  => fail(s"kfold: $error")
      case Right(value) => value
    val backend = Linop4sRidgeBackend.lsqr[Id]()
    val penalties = Vector(0.01, 0.1, 1.0)
    val family: Double => RidgeRegression[Id, Point, Unit] =
      penalty =>
        val config = RidgeConfig.create(penalty) match
          case Left(error)  => fail(s"config: $error")
          case Right(value) => value
        RidgeRegression.sync[Point, Unit](config, backend)
    val space = Space.choice(penalties.head, penalties.tail*)
    val strategy = GridStrategy(
      PositiveInt.create(1) match
        case Right(value) => value
        case Left(error)  => fail(s"points: $error")
    )
    Search
      .crossValidatedGridSync(
        space,
        strategy,
        resampler,
        family,
        Metrics.rmse,
        (score: RootMeanSquaredError) => score.value,
        Seed(5L),
        PlanFingerprint.external("ridge-search-v1")
      )
      .run(data) match
      case Left(error) => fail(s"search failed: $error")
      case Right(selected) =>
        assertEquals(selected.trials.length, 3)
        assert(selected.trials.forall(_.folds.length == 3))
        val rebuilt = family(selected.best)
        assertEquals(rebuilt.config.penalty, selected.best)
  }

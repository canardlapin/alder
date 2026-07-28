package alder.tune

import alder.data.*
import alder.kernel.*
import alder.metrics.*
import alder.testkit.TestData
import cats.Id
import cats.data.EitherT

class SearchSuite extends munit.FunSuite:
  private final class BiasLearner(val bias: Double)
      extends Learner[Id, Double, Double, Unit, Double]:
    type FitError = Nothing
    type RunError = Nothing
    type Model = Pipe[Double, Nothing, Double]

    def fit[U <: Use.Fit](
        data: NonEmptyData[U, Example[Double, Double, Unit]]
    )(using context: FitContext): FitResult[Id, FitError, Trained[Model]] =
      val _ = data
      EitherT.right(
        context.complete(
          Pipe.total[Double, Double](value => value + bias),
          data,
          ComponentDescriptor(
            ComponentId("alder.test.bias"),
            ComponentVersion("1"),
            AuditValue.record("bias" -> AuditValue.decimal(bias)),
            BackendFingerprint("test", "1", AuditValue.record())
          )
        )
      )

  test("cross-validated grid selects the lowest-RMSE bias") {
    val rows =
      Vector.tabulate(12) { index =>
        val x = index.toDouble
        Example(x, x, ())
      }
    val data =
      TestData.indexed[Use.Train, Example[Double, Double, Unit]](
        rows,
        DataFingerprint.external("search-bias")
      ) match
        case Some(value) => value
        case None        => fail("expected nonempty search data")
    val resampler = KFold[Example[Double, Double, Unit]](3) match
      case Left(error)  => fail(s"unexpected kfold error: $error")
      case Right(value) => value
    val space = Space.choice(0.0, 2.0, -0.5)
    val strategy = GridStrategy(
      PositiveInt.create(1) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected points: $error")
    )
    val result =
      Search
        .crossValidatedGridSync(
          space,
          strategy,
          resampler,
          bias => new BiasLearner(bias),
          RegressionMetrics.rmse[Unit],
          (score: RootMeanSquaredError) => score.value,
          Seed(7L),
          PlanFingerprint.external("search-bias-v1")
        )
        .run(data)

    result match
      case Left(error) => fail(s"unexpected search failure: $error")
      case Right(selected) =>
        assertEquals(selected.best, 0.0)
        assertEquals(selected.trials.length, 3)
        assert(selected.trials.forall(_.folds.length == 3))
        assert(
          selected.trials.forall(_.folds.forall {
            case FoldScore.Scored(_, _) => true
            case FoldScore.Failed(_, _) => false
          })
        )
  }

  private final class FailingLearner
      extends Learner[Id, Double, Double, Unit, Double]:
    type FitError = String
    type RunError = Nothing
    type Model = Pipe[Double, Nothing, Double]

    def fit[U <: Use.Fit](
        data: NonEmptyData[U, Example[Double, Double, Unit]]
    )(using context: FitContext): FitResult[Id, FitError, Trained[Model]] =
      val _ = data
      EitherT.leftT(context.stagePath.failure("forced-fit-failure"))

  test("all failing candidates surface SearchError.Study") {
    val rows =
      Vector.tabulate(9) { index =>
        Example(index.toDouble, index.toDouble, ())
      }
    val data =
      TestData.indexed[Use.Train, Example[Double, Double, Unit]](
        rows,
        DataFingerprint.external("search-fail")
      ) match
        case Some(value) => value
        case None        => fail("expected nonempty search data")
    val resampler = KFold[Example[Double, Double, Unit]](3) match
      case Left(error)  => fail(s"unexpected kfold error: $error")
      case Right(value) => value
    val strategy = GridStrategy(
      PositiveInt.create(1) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected points: $error")
    )
    Search
      .crossValidatedGridSync(
        Space.choice(1.0, 2.0),
        strategy,
        resampler,
        (_: Double) => new FailingLearner,
        RegressionMetrics.rmse[Unit],
        (score: RootMeanSquaredError) => score.value,
        Seed(3L),
        PlanFingerprint.external("search-fail-v1")
      )
      .run(data) match
      case Left(SearchError.Study(StudyError.NoSuccessfulTrial(failures))) =>
        assertEquals(failures.length, 2)
      case other =>
        fail(s"expected Study NoSuccessfulTrial, got $other")
  }

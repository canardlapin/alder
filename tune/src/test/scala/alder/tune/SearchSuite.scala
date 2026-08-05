package alder.tune

import alder.data.*
import alder.kernel.*
import alder.metrics.*
import alder.testkit.TestData
import cats.Id
import cats.data.EitherT
import cats.kernel.Eq

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

  private final class LabelLearner(val label: String)
      extends Learner[Id, Double, String, Unit, String]:
    type FitError = Nothing
    type RunError = Nothing
    type Model = Pipe[Double, Nothing, String]

    def fit[U <: Use.Fit](
        data: NonEmptyData[U, Example[Double, String, Unit]]
    )(using context: FitContext): FitResult[Id, FitError, Trained[Model]] =
      val _ = data
      EitherT.right(
        context.complete(
          Pipe.total[Double, String](_ => label),
          data,
          ComponentDescriptor(
            ComponentId("alder.test.label"),
            ComponentVersion("1"),
            AuditValue.record("label" -> AuditValue.text(label)),
            BackendFingerprint("test", "1", AuditValue.record())
          )
        )
      )

  private final class FailPredictLearner
      extends Learner[Id, Double, Double, Unit, Double]:
    type FitError = Nothing
    type RunError = String
    type Model = Pipe[Double, String, Double]

    def fit[U <: Use.Fit](
        data: NonEmptyData[U, Example[Double, Double, Unit]]
    )(using context: FitContext): FitResult[Id, FitError, Trained[Model]] =
      val _ = data
      val pipe =
        new Pipe[Double, String, Double]:
          def run(input: Double): Either[Failure[String], Double] =
            val _ = input
            Left(context.stagePath.failure("predict-boom"))
      EitherT.right(
        context.complete(
          pipe,
          data,
          ComponentDescriptor(
            ComponentId("alder.test.fail-predict"),
            ComponentVersion("1"),
            AuditValue.record(),
            BackendFingerprint("test", "1", AuditValue.record())
          )
        )
      )

  private def trainDoubles(
      count: Int,
      identity: String
  ): NonEmptyData[Use.Train, Example[Double, Double, Unit]] =
    val rows =
      Vector.tabulate(count) { index =>
        Example(index.toDouble, index.toDouble, ())
      }
    TestData.indexed[Use.Train, Example[Double, Double, Unit]](
      rows,
      DataFingerprint.external(identity)
    ) match
      case Some(value) => value
      case None        => fail("expected nonempty search data")

  test("crossValidatedRandom is seed-stable and retains fold scores") {
    val data = trainDoubles(12, "search-random")
    val resampler = KFold[Example[Double, Double, Unit]](3) match
      case Left(error)  => fail(s"unexpected kfold error: $error")
      case Right(value) => value
    val trials = PositiveInt.create(4) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected trials: $error")
    val space = Space.choice(0.0, 1.0, -1.0)
    def runOnce(seed: Seed) =
      Search
        .crossValidatedRandom(
          space,
          trials,
          resampler,
          bias => new BiasLearner(bias),
          RegressionMetrics.rmse[Unit],
          (score: RootMeanSquaredError) => score.value,
          seed,
          PlanFingerprint.external("search-random-v1")
        )
        .run(data)
    (runOnce(Seed(11L)), runOnce(Seed(11L))) match
      case (Right(first), Right(second)) =>
        assertEquals(first.best, second.best)
        assertEquals(first.trials.map(_.config), second.trials.map(_.config))
        assertEquals(first.audit.seed, Some(Seed(11L)))
      case other =>
        fail(s"unexpected random search result: $other")
  }

  test("maximize direction selects the highest-accuracy label") {
    given Eq[String] = Eq.fromUniversalEquals
    val rows =
      Vector.tabulate(12) { index =>
        Example(index.toDouble, "keep", ())
      }
    val data =
      TestData.indexed[Use.Train, Example[Double, String, Unit]](
        rows,
        DataFingerprint.external("search-maximize")
      ) match
        case Some(value) => value
        case None        => fail("expected nonempty search data")
    val resampler = KFold[Example[Double, String, Unit]](3) match
      case Left(error)  => fail(s"unexpected kfold error: $error")
      case Right(value) => value
    val strategy = GridStrategy(
      PositiveInt.create(1) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected points: $error")
    )
    Search
      .crossValidatedGridSync(
        Space.choice("keep", "drop"),
        strategy,
        resampler,
        label => new LabelLearner(label),
        ClassificationMetrics.accuracy[String, Unit](
          EqualityPolicyId("cats.eq.string")
        ),
        (score: Accuracy) => score.value,
        Seed(13L),
        PlanFingerprint.external("search-maximize-v1")
      )
      .run(data) match
      case Left(error) => fail(s"unexpected maximize search failure: $error")
      case Right(selected) =>
        assertEquals(selected.best, "keep")
        assertEquals(
          selected.audit.objectiveDirection,
          ObjectiveDirection.Maximize
        )
  }

  test("resampler split failure surfaces SearchError.Resampling") {
    val data = trainDoubles(2, "search-resample")
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
        Space.choice(0.0),
        strategy,
        resampler,
        bias => new BiasLearner(bias),
        RegressionMetrics.rmse[Unit],
        (score: RootMeanSquaredError) => score.value,
        Seed(1L),
        PlanFingerprint.external("search-resample-v1")
      )
      .run(data) match
      case Left(SearchError.Resampling(DataError.TooManyFolds(3, 2))) => ()
      case other =>
        fail(s"expected Resampling TooManyFolds, got $other")
  }

  test("prediction failures become fold evaluation failures") {
    val data = trainDoubles(9, "search-predict-fail")
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
        Space.choice(1.0),
        strategy,
        resampler,
        (_: Double) => new FailPredictLearner,
        RegressionMetrics.rmse[Unit],
        (score: RootMeanSquaredError) => score.value,
        Seed(4L),
        PlanFingerprint.external("search-predict-fail-v1")
      )
      .run(data) match
      case Left(SearchError.Study(StudyError.NoSuccessfulTrial(failures))) =>
        assertEquals(failures.length, 1)
        failures.head match
          case TrialFailure.Evaluation(
                FoldEvaluationError.Predict(_)
              ) =>
            ()
          case other =>
            fail(s"expected Predict evaluation failure, got $other")
      case other =>
        fail(s"expected Study NoSuccessfulTrial, got $other")
  }

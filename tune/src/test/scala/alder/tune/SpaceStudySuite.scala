package alder.tune

import alder.kernel.*
import alder.testkit.TestData
import cats.Id
import cats.syntax.all.*
import scala.compiletime.testing.typeCheckErrors

final case class ValidConfig(
    depth: PositiveInt,
    rate: PositiveDouble,
    enabled: Boolean
)

class SpaceStudySuite extends munit.FunSuite:
  private def positiveInt(value: Int): PositiveInt =
    PositiveInt.create(value) match
      case Left(error)  => fail(s"invalid positive int: $error")
      case Right(valid) => valid

  private def positiveDouble(value: Double): PositiveDouble =
    PositiveDouble.create(value) match
      case Left(error)  => fail(s"invalid positive double: $error")
      case Right(valid) => valid

  private val configSpace: Space[ValidConfig] =
    val depths =
      Space.intRange(positiveInt(1), positiveInt(4)) match
        case Left(error)  => fail(s"invalid integer space: $error")
        case Right(value) => value
    val rates =
      Space.logUniform(
        positiveDouble(1.0e-4),
        positiveDouble(1.0)
      ) match
        case Left(error)  => fail(s"invalid log space: $error")
        case Right(value) => value
    (depths, rates, Space.choice(true, false)).mapN(ValidConfig.apply)

  test("applicative products emit only complete valid configurations") {
    val candidates =
      Grid.candidates(configSpace, GridStrategy(positiveInt(5)))
    assertEquals(candidates.length, 4 * 5 * 2)
    assert(
      candidates.forall(config =>
        config.depth.toInt > 0 &&
          config.rate.toDouble > 0.0 &&
          config.rate.toDouble.isFinite
      )
    )
  }

  test("random interpretation is stable across equal seeds") {
    val first =
      RandomSearch.candidates(configSpace, positiveInt(50), Seed(91L))
    val second =
      RandomSearch.candidates(configSpace, positiveInt(50), Seed(91L))
    assertEquals(first, second)
    assertEquals(first.length, 50)
  }

  test("primitive search-space conveniences retain validation") {
    val grid =
      for
        rates <- Space.logUniform(1.0e-4, 1.0)
        depths <- Space.intRange(1, 4)
        space = (depths, rates).mapN((depth, rate) => (depth, rate))
        candidates <- Grid.candidates(space, continuousPoints = 3)
      yield candidates

    assertEquals(grid.map(_.length), Right(12))
    assertEquals(
      Space.intRange(0, 4),
      Left(NumericSpaceError.NonPositiveInt(0))
    )
    assertEquals(
      RandomSearch.candidates(
        Space.constant(true),
        trials = 0,
        Seed(1L)
      ),
      Left(NumericSpaceError.NonPositiveInt(0))
    )
  }

  test("positive literal constructors reject invalid programs") {
    val validInt = PositiveInt.const(3)
    val validDouble = PositiveDouble.const(0.25)
    assertEquals(validInt.toInt, 3)
    assertEquals(validDouble.toDouble, 0.25)
    assert(
      typeCheckErrors("alder.tune.PositiveInt.const(0)").nonEmpty
    )
    assert(
      typeCheckErrors("alder.tune.PositiveDouble.const(-1.0)").nonEmpty
    )
  }

  test("study returns the best configuration rather than a fitted artifact") {
    val training =
      TestData.nonEmpty[Use.Train, Int](
        Vector(RowId(0L) -> 1, RowId(1L) -> 2),
        new DataFingerprint(
          FingerprintPolicy.Summary("study-test"),
          "training"
        )
      ) match
        case Some(value) => value
        case None        => fail("training fixture must be nonempty")
    val study =
      Study.grid[Id, Int, Int, Nothing](
        Space.choice(5, 3, 1),
        GridStrategy(positiveInt(2)),
        ObjectiveDirection.Minimize
      ) { (config, data) =>
        assertEquals(data.size, 2L)
        Right((config - 3).toDouble.abs)
      }
    study.run(training) match
      case Left(error) => fail(s"unexpected study failure: $error")
      case Right(selection) =>
        assertEquals(selection.best, 3)
        assertEquals(selection.trials.length, 3)
        assertEquals(selection.audit.successfulTrials, 3)
  }

  test("study treats non-finite objectives as failed trials") {
    val training =
      TestData.nonEmpty[Use.Train, Int](
        Vector(RowId(0L) -> 1),
        new DataFingerprint(
          FingerprintPolicy.Summary("study-test"),
          "nonfinite"
        )
      ) match
        case Some(value) => value
        case None        => fail("training fixture must be nonempty")
    val study =
      Study.grid[Id, Int, Int, Nothing](
        Space.choice(1, 2),
        GridStrategy(positiveInt(2)),
        ObjectiveDirection.Minimize
      ) { (_, _) => Right(Double.NaN) }
    study.run(training) match
      case Left(StudyError.NoSuccessfulTrial(failures)) =>
        assertEquals(failures.length, 2)
        assert(
          failures.forall {
            case TrialFailure.NonFiniteObjective(value) =>
              value.isNaN
          }
        )
      case other =>
        fail(s"expected all-trials-failed result, got $other")
  }

  test("study retains the typed evaluation cause for each failed candidate") {
    enum CandidateError derives CanEqual:
      case Rejected(config: Int)

    val training =
      TestData.nonEmpty[Use.Train, Int](
        Vector(RowId(0L) -> 1),
        new DataFingerprint(
          FingerprintPolicy.Summary("study-test"),
          "typed-failure"
        )
      ) match
        case Some(value) => value
        case None        => fail("training fixture must be nonempty")
    val study =
      Study.grid[Id, Int, Int, CandidateError](
        Space.choice(1, 2),
        GridStrategy(positiveInt(2)),
        ObjectiveDirection.Minimize
      ) { (config, _) =>
        if config == 1 then
          Left(TrialFailure.Evaluation(CandidateError.Rejected(config)))
        else Right(0.0)
      }

    study.run(training) match
      case Left(error) => fail(s"unexpected study failure: $error")
      case Right(selection) =>
        assertEquals(selection.best, 2)
        assertEquals(
          selection.trials.map(_.objective),
          Vector(
            Left(
              TrialFailure.Evaluation(
                CandidateError.Rejected(1)
              )
            ),
            Right(0.0)
          )
        )
  }

  test("objective direction is explicit and auditable") {
    val training =
      TestData.nonEmpty[Use.Train, Int](
        Vector(RowId(0L) -> 1),
        new DataFingerprint(
          FingerprintPolicy.Summary("study-test"),
          "maximize"
        )
      ) match
        case Some(value) => value
        case None        => fail("training fixture must be nonempty")
    val study =
      Study.grid[Id, Int, Int, Nothing](
        Space.choice(1, 2, 3),
        GridStrategy(positiveInt(2)),
        ObjectiveDirection.Maximize
      ) { (config, _) => Right(config.toDouble) }
    study.run(training) match
      case Left(error) => fail(s"unexpected study failure: $error")
      case Right(selection) =>
        assertEquals(selection.best, 3)
        assertEquals(
          selection.audit.objectiveDirection,
          ObjectiveDirection.Maximize
        )
  }

  test("Space has no monadic flatMap") {
    val errors = typeCheckErrors(
      """
        import alder.tune.*
        Space.constant(1).flatMap(value => Space.constant(value + 1))
      """
    )
    assert(errors.nonEmpty)
  }

  test("study rejects evaluation-role data at compile time") {
    val errors = typeCheckErrors(
      """
        import alder.kernel.*
        import alder.tune.*
        import cats.Id
        def invalid(
          study: Study[Id, Int, Int, String],
          test: NonEmptyData[Use.Test, Int]
        ) = study.run(test)
      """
    )
    assert(errors.nonEmpty)
  }

package alder.tune.laws

import alder.kernel.*
import alder.testkit.TestData
import alder.tune.*
import cats.Id
import cats.data.EitherT
import cats.kernel.Eq
import munit.DisciplineSuite

final class OffsetPipe(
    offset: Double
) extends Pipe[Double, Nothing, Double]:
  def run(value: Double): Either[Failure[Nothing], Double] =
    Right(value + offset)

final class OffsetLearner
    extends Learner[Id, Double, Double, Unit, Double]:
  type FitError = Nothing
  type RunError = Nothing
  type Model = OffsetPipe

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[Double, Double, Unit]]
  )(using context: FitContext)
      : FitResult[Id, Nothing, Trained[OffsetPipe]] =
    val mean =
      data.data.foldRows((0.0, 0L)) {
        case ((sum, count), _, example) =>
          (sum + example.target, count + 1L)
      } match
        case (sum, count) => sum / count.toDouble
    EitherT.rightT(
      context.complete(
        new OffsetPipe(mean),
        data,
        OffsetLearner.descriptor
      )
    )

object OffsetLearner:
  val descriptor: ComponentDescriptor =
    ComponentDescriptor(
      ComponentId("alder.tune-laws.offset"),
      ComponentVersion("1"),
      AuditValue.record(),
      BackendFingerprint("pure", "1", AuditValue.record())
    )

class PublishedTuneLawsSuite extends DisciplineSuite:
  private def positive(value: Int): PositiveInt =
    PositiveInt.create(value) match
      case Left(error)  => fail(s"invalid positive int: $error")
      case Right(valid) => valid

  private val booleanSpace = Space.choice(true, false)

  private given Eq[Boolean] = Eq.fromUniversalEquals

  checkAll(
    "Boolean Space",
    new SpaceTests(
      new SpaceLaws[Boolean]:
        def space: Space[Boolean] = booleanSpace
        def gridStrategy: GridStrategy = GridStrategy(positive(3))
        def trials: PositiveInt = positive(100)
        def seed: Seed = Seed(73L)
        def valid(value: Boolean): Boolean =
          value || !value
    ).all
  )

  private given Eq[Int] = Eq.fromUniversalEquals

  private val studyCandidates = Vector(5, 3, 1)
  private val study =
    Study.grid[Id, Int, Int](
      Space.choice(5, 3, 1),
      GridStrategy(positive(2)),
      ObjectiveDirection.Minimize
    ) { (config, _) =>
      Right((config - 3).toDouble.abs)
    }

  checkAll(
    "Train-only Study",
    new StudyTests(
      new StudyLaws[Int]:
        def selection: Either[
          StudyError,
          Selection[Int]
        ] =
          study.run(
            TestData.nonEmpty[Use.Train, Int](
              Vector(RowId(0L) -> 1),
              new DataFingerprint(
                FingerprintPolicy.Summary("study-law"),
                "train"
              )
            ) match
              case Some(value) => value
              case None => fail("study fixture must be nonempty")
          )
        def candidates: Vector[Int] = studyCandidates
    ).all
  )

  private val training =
    TestData.nonEmpty[
      Use.Train,
      Example[Double, Double, Unit]
    ](
      Vector(
        RowId(0L) -> Example(1.0, 2.0, ()),
        RowId(1L) -> Example(2.0, 4.0, ())
      ),
      new DataFingerprint(
        FingerprintPolicy.Summary("tuning-erasure-law"),
        "training"
      )
    ) match
      case Some(value) => value
      case None        => fail("training fixture must be nonempty")

  private val context =
    FitContext.root(
      Seed(7L),
      PlanFingerprint("tuning-erasure-law"),
      SchemaFingerprint("double"),
      NumericMode.Deterministic
    )

  private val learner = new OffsetLearner

  private given Eq[Either[Failure[Nothing], Double]] =
    Eq.fromUniversalEquals
  private given Eq[Failure[Nothing]] =
    Eq.fromUniversalEquals

  checkAll(
    "Learner eraseModel",
    new TuningErasureTests(
      new TuningErasureLaws[Double, Nothing, Nothing, Double]:
        def concrete
            : Either[
              Failure[Nothing],
              Trained[? <: Pipe[Double, Nothing, Double]]
            ] =
          learner.fit(training)(using context).value

        def erased
            : Either[
              Failure[Nothing],
              Trained[Pipe[Double, Nothing, Double]]
            ] =
          learner.eraseModel.fit(training)(using context).value

        def inputs: Vector[Double] =
          Vector(-1.0, 0.0, 4.0)
    ).all
  )

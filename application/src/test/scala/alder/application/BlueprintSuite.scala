package alder.application

import alder.data.{KFold, crossFitted}
import alder.kernel.*
import alder.testkit.{TestData, VisibilityEncoder, VisibilityValue}
import cats.Id
import cats.data.EitherT
import scala.compiletime.testing.typeCheckErrors

class BlueprintSuite extends munit.FunSuite:
  private final class Scale
      extends Transform.Leaf[Id, Double, Double]:
    type FitError = Nothing
    type RunError = Nothing
    type Fitted = Pipe[Double, Nothing, Double]

    protected def descriptor: ComponentDescriptor =
      ComponentDescriptor(
        ComponentId("alder.test.scale"),
        ComponentVersion("1"),
        AuditValue.record(),
        BackendFingerprint("test", "1", AuditValue.record())
      )

    protected def replayFailure(
        failure: Failure[RunError]
    ): Failure[FitError] =
      failure.widen[FitError]

    protected def fitPipe[U <: Use.Fit](
        data: NonEmptyData[U, Double]
    )(using FitContext): Either[Failure[FitError], Fitted] =
      val _ = data
      Right(Pipe.total(_ * 2.0))

  private final class Shift
      extends Transform.Leaf[Id, Double, Double]:
    type FitError = Nothing
    type RunError = Nothing
    type Fitted = Pipe[Double, Nothing, Double]

    protected def descriptor: ComponentDescriptor =
      ComponentDescriptor(
        ComponentId("alder.test.shift"),
        ComponentVersion("1"),
        AuditValue.record(),
        BackendFingerprint("test", "1", AuditValue.record())
      )

    protected def replayFailure(
        failure: Failure[RunError]
    ): Failure[FitError] =
      failure.widen[FitError]

    protected def fitPipe[U <: Use.Fit](
        data: NonEmptyData[U, Double]
    )(using FitContext): Either[Failure[FitError], Fitted] =
      val _ = data
      Right(Pipe.total(_ + 1.0))

  private final class BiasLearner
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
          Pipe.identity[Double],
          data,
          ComponentDescriptor(
            ComponentId("alder.test.bias-learner"),
            ComponentVersion("1"),
            AuditValue.record(),
            BackendFingerprint("test", "1", AuditValue.record())
          )
        )
      )

  private final class ReadVisibility
      extends Transform.Leaf[Id, VisibilityValue, Double]:
    type FitError = Nothing
    type RunError = Nothing
    type Fitted = Pipe[VisibilityValue, Nothing, Double]

    protected def descriptor: ComponentDescriptor =
      ComponentDescriptor(
        ComponentId("alder.test.read-visibility"),
        ComponentVersion("1"),
        AuditValue.record(),
        BackendFingerprint("test", "1", AuditValue.record())
      )

    protected def replayFailure(
        failure: Failure[RunError]
    ): Failure[FitError] = failure.widen[FitError]

    protected def fitPipe[U <: Use.Fit](
        data: NonEmptyData[U, VisibilityValue]
    )(using FitContext): Either[Failure[FitError], Fitted] =
      val _ = data
      Right(Pipe.total(_.input))

  private final class VisibilityLearner
      extends Learner[Id, VisibilityValue, Double, String, Double]:
    type FitError = Nothing
    type RunError = Nothing
    type Model = Pipe[VisibilityValue, Nothing, Double]

    def fit[U <: Use.Fit](
        data: NonEmptyData[U, Example[VisibilityValue, Double, String]]
    )(using context: FitContext): FitResult[Id, FitError, Trained[Model]] =
      val mean =
        data.data.foldRows((0.0, 0L)) { case ((sum, n), _, example) =>
          (sum + example.target, n + 1L)
        } match
          case (sum, n) => sum / n.toDouble
      EitherT.right(
        context.complete(
          Pipe.total[VisibilityValue, Double](_ => mean),
          data,
          ComponentDescriptor(
            ComponentId("alder.test.visibility-learner"),
            ComponentVersion("1"),
            AuditValue.record(),
            BackendFingerprint("test", "1", AuditValue.record())
          )
        )
      )

  private final class StringBiasLearner
      extends Learner[Id, Double, Double, String, Double]:
    type FitError = Nothing
    type RunError = Nothing
    type Model = Pipe[Double, Nothing, Double]

    def fit[U <: Use.Fit](
        data: NonEmptyData[U, Example[Double, Double, String]]
    )(using context: FitContext): FitResult[Id, FitError, Trained[Model]] =
      EitherT.right(
        context.complete(
          Pipe.identity[Double],
          data,
          ComponentDescriptor(
            ComponentId("alder.test.string-bias-learner"),
            ComponentVersion("1"),
            AuditValue.record(),
            BackendFingerprint("test", "1", AuditValue.record())
          )
        )
      )

  test("Empty.learn retains the supplied learner identity") {
    val learner = new BiasLearner
    val facade = Blueprint.supervised[Double, Double].learn(learner)
    assert(facade.learner eq learner)
  }

  test("Empty.crossFit expands to FeatureMap.crossFitted") {
    val encoder = new VisibilityEncoder
    val resampler = KFold[Example[Double, Double, String]](3, shuffle = false) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected kfold: $error")
    val facade =
      Blueprint
        .apply[Id, Double, Double, String]
        .crossFit(encoder, resampler)
        .learn(new VisibilityLearner)
    val direct =
      FeatureMap
        .crossFitted(encoder, resampler)
        .learnWith(new VisibilityLearner)
    assertEquals(
      facade.learner.getClass.getName,
      direct.getClass.getName
    )
  }

  test("TargetBlind.withFeatureMap expands to transform.andThen(featureMap)") {
    val scale = new Scale
    val shift = new Shift
    val featureMap =
      FeatureMap.inputOnly[Id, Double, Double, Unit, Double, Shift](shift)
    val learner = new BiasLearner
    val facade =
      Blueprint
        .supervised[Double, Double]
        .via(scale)
        .withFeatureMap(featureMap)
        .learn(learner)
    val direct = scale.andThen(featureMap).learnWith(learner)
    assertEquals(
      facade.learner.getClass.getName,
      direct.getClass.getName
    )
  }

  test("TargetBlind.crossFit then learn fits with out-of-fold preparation") {
    val scale = new Scale
    val encoder = new VisibilityEncoder
    val resampler = KFold[Example[Double, Double, String]](3, shuffle = false) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected kfold: $error")
    val facade =
      Blueprint
        .apply[Id, Double, Double, String]
        .via(scale)
        .crossFit(encoder, resampler)
        .learn(new VisibilityLearner)
    val rows =
      Vector.tabulate(6) { index =>
        Example(index.toDouble, index.toDouble * 10.0, s"m$index")
      }
    val data =
      TestData.indexed[Use.Train, Example[Double, Double, String]](
        rows,
        DataFingerprint.external("blueprint-crossfit")
      ) match
        case Some(value) => value
        case None        => fail("expected data")
    val context =
      FitContext.root(
        Seed(5L),
        PlanFingerprint.external("blueprint-crossfit-v1"),
        SchemaFingerprint("double"),
        NumericMode.Deterministic
      )
    facade.learner.fit(data)(using context).value match
      case Left(error) => fail(s"unexpected fit failure: $error")
      case Right(trained) =>
        assertEquals(trained.audit.children.length, 3)
        assert(trained.artifact.run(1.0).isRight)
  }

  test("crossFit accepts FoldEncoder.andThen and matches the direct core") {
    val encoder = new VisibilityEncoder
    val postprocess = new ReadVisibility
    val resampler = KFold[Example[Double, Double, String]](3, shuffle = false) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected kfold: $error")
    val learner = new StringBiasLearner
    val combined = encoder.andThen(postprocess)
    val facade =
      Blueprint
        .apply[Id, Double, Double, String]
        .crossFit(combined, resampler)
        .learn(learner)
    val direct =
      FeatureMap
        .crossFitted(combined, resampler)
        .learnWith(learner)
    assertEquals(facade.learner.getClass.getName, direct.getClass.getName)
    assertEquals(combined.stageCount, 2)
  }

  test("via.via.learn expands to transform.andThen.learnWith") {
    val scale = new Scale
    val shift = new Shift
    val learner = new BiasLearner
    val facade =
      Blueprint.supervised[Double, Double].via(scale).via(shift).learn(learner)
    val direct = scale.andThen(shift).learnWith(learner)
    assertEquals(
      facade.learner.getClass.getName,
      direct.getClass.getName
    )
  }

  test("withFeatureMap.learn expands to featureMap.learnWith") {
    val scale = new Scale
    val featureMap =
      FeatureMap.inputOnly[Id, Double, Double, Unit, Double, Scale](scale)
    val learner = new BiasLearner
    val facade =
      Blueprint
        .supervised[Double, Double]
        .withFeatureMap(featureMap)
        .learn(learner)
    val direct = featureMap.learnWith(learner)
    assertEquals(
      facade.learner.getClass.getName,
      direct.getClass.getName
    )
  }

  test("named and anonymous mapOutput remain constructible") {
    val scale = new Scale
    val featureMap =
      FeatureMap.inputOnly[Id, Double, Double, Unit, Double, Scale](scale)
    val anonymous =
      Blueprint
        .supervised[Double, Double]
        .withFeatureMap(featureMap)
        .mapOutput((value: Double) => value + 1.0)
    val named =
      Blueprint
        .supervised[Double, Double]
        .withFeatureMap(featureMap)
        .mapOutput(
          NamedMap("plus-one", "1", (value: Double) => value + 1.0)
        )
    assertEquals(
      anonymous.featureMap.getClass.getName,
      named.featureMap.getClass.getName
    )
  }

  test("illegal Blueprint continuations fail to compile") {
    val afterLearn = typeCheckErrors(
      """package consumer
import alder.application.*
import alder.kernel.*
import cats.Id
def illegal[
  L <: Learner[Id, Double, Double, Unit, Double],
  T <: Transform[Id, Double, Double]
](
  complete: Blueprint.Complete[Id, Double, Double, Unit, Double, L],
  transform: T
) =
  complete.via(transform)
"""
    )
    val learnerReadyVia = typeCheckErrors(
      """package consumer
import alder.application.*
import alder.kernel.*
import cats.Id
def illegal[
  FM <: FeatureMap[Id, Double, Double, Unit, Double],
  T <: Transform[Id, Double, Double]
](
  ready: Blueprint.LearnerReady[Id, Double, Double, Unit, Double, FM],
  transform: T
) =
  ready.via(transform)
"""
    )
    val incompleteCrossFit = typeCheckErrors(
      """package consumer
import alder.application.*
import alder.data.*
import alder.kernel.*
import cats.Id
def illegal(
  encoder: FoldEncoder[Id, Double, Double, Unit, Double],
  resampler: Resampler[Example[Double, Double, Unit]]
) = Blueprint.supervised[Double, Double].crossFit(encoder, resampler)
"""
    )
    assert(afterLearn.nonEmpty)
    assert(learnerReadyVia.nonEmpty)
    assert(incompleteCrossFit.nonEmpty)
  }

package alder.application

import alder.kernel.*
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
    assert(afterLearn.nonEmpty)
    assert(learnerReadyVia.nonEmpty)
  }

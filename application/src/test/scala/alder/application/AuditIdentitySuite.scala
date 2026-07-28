package alder.application

import alder.kernel.*
import alder.testkit.TestData
import cats.Id
import munit.FunSuite

/** Gate 6: behaviour-changing policies alter audit identity. */
class AuditIdentitySuite extends FunSuite:
  private final class Passthrough
      extends Transform.Leaf[Id, Double, Double]:
    type FitError = Nothing
    type RunError = Nothing
    type Fitted = Pipe[Double, Nothing, Double]

    protected def descriptor: ComponentDescriptor =
      ComponentDescriptor(
        ComponentId("alder.test.passthrough"),
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
      Right(Pipe.identity[Double])

  test("named mapOutput descriptors differ from anonymous mapOutput") {
    val passthrough = new Passthrough
    val featureMap =
      FeatureMap.inputOnly[Id, Double, Double, Unit, Double, Passthrough](
        passthrough
      )
    val anonymous =
      featureMap.mapOutput((value: Double) => value * 2.0)
    val named =
      featureMap.mapOutput(
        NamedMap("double", "1", (value: Double) => value * 2.0)
      )
    val context =
      FitContext.root(
        Seed(1L),
        PlanFingerprint.external("audit-map-output"),
        SchemaFingerprint("double"),
        NumericMode.Deterministic
      )
    val data =
      TestData.indexed[Use.Train, Example[Double, Double, Unit]](
        Vector(
          Example(1.0, 1.0, ()),
          Example(2.0, 2.0, ()),
          Example(3.0, 3.0, ())
        ),
        DataFingerprint.external("audit-identity")
      ) match
        case Some(value) => value
        case None        => fail("expected data")
    val anonymousPrepared =
      anonymous.fit(data)(using context).value match
        case Left(error)  => fail(s"anonymous fit failed: $error")
        case Right(value) => value
    val namedPrepared =
      named.fit(data)(using context).value match
        case Left(error)  => fail(s"named fit failed: $error")
        case Right(value) => value
    assertNotEquals(
      anonymousPrepared.fitted.audit.component.parameters,
      namedPrepared.fitted.audit.component.parameters
    )
  }

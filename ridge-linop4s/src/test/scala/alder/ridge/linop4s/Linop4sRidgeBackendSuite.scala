package alder.ridge.linop4s

import alder.data.Coordinates
import alder.kernel.*
import alder.models.linear.*
import alder.testkit.TestData
import cats.Id

final case class NativePoint(x: Double) derives Coordinates, CanEqual

class Linop4sRidgeBackendSuite extends munit.FunSuite:
  private val context =
    FitContext.root(
      Seed(17L),
      PlanFingerprint("linop4s-ridge"),
      SchemaFingerprint("native-point"),
      NumericMode.Deterministic
    )

  private val training =
    TestData.nonEmpty[
      Use.Train,
      Example[NativePoint, Double, Unit]
    ](
      Vector(
        RowId(0L) -> Example(NativePoint(1.0), 3.0, ()),
        RowId(1L) -> Example(NativePoint(2.0), 5.0, ()),
        RowId(2L) -> Example(NativePoint(3.0), 7.0, ())
      ),
      new DataFingerprint(
        FingerprintPolicy.Summary("linop4s-ridge-test"),
        "three-points"
      )
    ) match
      case Some(value) => value
      case None        => fail("nonempty fixture was empty")

  test("matrix-free LSQR fits and records finite work evidence") {
    val config = RidgeConfig.create(0.5, fitIntercept = true) match
      case Left(error)  => fail(s"invalid config: $error")
      case Right(value) => value
    val backend =
      new Linop4sRidgeBackend[Id](
        Linop4sRidgeStrategy.LSQR,
        500,
        NumericMode.Deterministic
      )
    val model =
      new RidgeRegression[Id, NativePoint, Unit](config, backend)
        .fit(training)(using context)
        .value match
        case Left(error)    => fail(s"unexpected fit failure: $error")
        case Right(trained) => trained.artifact
    val prediction = model.run(NativePoint(4.0)) match
      case Left(error)  => fail(s"unexpected prediction failure: $error")
      case Right(value) => value
    assert(prediction.isFinite)
    assertEquals(model.solution.coefficientCount, 1)
    assert(model.solution.objective.isFinite)
    assert(model.solution.kktResidual < 1.0e-7)
    assert(model.solution.receipt.iterations.exists(_ > 0))
    assertEquals(
      model.solution.receipt.algorithm,
      SolverId.Linop4sLSQR
    )
    val operatorApplications =
      model.solution.receipt.extensions.fields.collectFirst {
        case ("operatorApplications", AuditValue.Integer(value)) => value
      }
    assert(operatorApplications.exists(value => value > 0L && value <= 20L))
  }

package alder.ridge.gale

import alder.data.Coordinates
import alder.kernel.*
import alder.models.linear.*
import alder.ridge.linop4s.*
import alder.testkit.TestData
import cats.Id
import gale.backend.PureBackend

final case class RidgePoint(x: Double, z: Double)
    derives Coordinates,
      CanEqual

final case class WeightedMeta(weight: Double)

object WeightedMeta:
  given WeightOf[WeightedMeta] with
    def apply(meta: WeightedMeta): Double = meta.weight

class RidgeBackendAgreementSuite extends munit.FunSuite:
  private val fingerprint =
    new DataFingerprint(
      FingerprintPolicy.Summary("ridge-agreement"),
      "ridge-agreement-data"
    )

  private val context =
    FitContext.root(
      Seed(41L),
      PlanFingerprint("ridge-agreement"),
      SchemaFingerprint("ridge-point"),
      NumericMode.Deterministic
    )

  private def config(
      penalty: Double,
      fitIntercept: Boolean = true
  ): RidgeConfig =
    RidgeConfig
      .create(penalty, fitIntercept, 1.0e-12)
      .fold(error => fail(s"invalid test config: $error"), identity)

  private def data(
      rows: Vector[(RidgePoint, Double)]
  ): NonEmptyData[Use.Train, Example[RidgePoint, Double, Unit]] =
    TestData.nonEmpty(
      rows.zipWithIndex.map { case ((input, target), index) =>
        RowId(index.toLong) -> Example(input, target, ())
      },
      fingerprint
    ) match
      case Some(value) => value
      case None        => fail("ridge test data must be nonempty")

  private def weightedData(
      rows: Vector[(RidgePoint, Double, Double)]
  ): NonEmptyData[
    Use.Train,
    Example[RidgePoint, Double, WeightedMeta]
  ] =
    TestData.nonEmpty(
      rows.zipWithIndex.map {
        case ((input, target, weight), index) =>
          RowId(index.toLong) ->
            Example(input, target, WeightedMeta(weight))
      },
      fingerprint
    ) match
      case Some(value) => value
      case None        => fail("weighted ridge test data must be nonempty")

  private def backends: Vector[RidgeBackend[Id]] =
    Vector(
      new GaleRidgeBackend[Id](
        PureBackend,
        GaleRidgeStrategy.AugmentedQR,
        NumericMode.Deterministic
      ),
      new GaleRidgeBackend[Id](
        PureBackend,
        GaleRidgeStrategy.NormalCholesky,
        NumericMode.Deterministic
      ),
      new Linop4sRidgeBackend[Id](
        Linop4sRidgeStrategy.LSQR,
        2000,
        NumericMode.Deterministic
      ),
      new Linop4sRidgeBackend[Id](
        Linop4sRidgeStrategy.NormalCG,
        2000,
        NumericMode.Deterministic
      )
    )

  private def fit(
      backend: RidgeBackend[Id],
      training: NonEmptyData[
        Use.Train,
        Example[RidgePoint, Double, Unit]
      ],
      ridgeConfig: RidgeConfig
  ): RidgeModel[RidgePoint] =
    new RidgeRegression[Id, RidgePoint, Unit](ridgeConfig, backend)
      .fit(training)(using context)
      .value match
      case Left(error) => fail(s"unexpected ridge failure: $error")
      case Right(trained) => trained.artifact

  private def fitWeighted(
      backend: RidgeBackend[Id],
      training: NonEmptyData[
        Use.Train,
        Example[RidgePoint, Double, WeightedMeta]
      ],
      ridgeConfig: RidgeConfig
  ): RidgeModel[RidgePoint] =
    new WeightedRidgeRegression[Id, RidgePoint, WeightedMeta](
      ridgeConfig,
      backend
    ).fit(training)(using context).value match
      case Left(error) => fail(s"unexpected weighted ridge failure: $error")
      case Right(trained) => trained.artifact

  private def prediction(
      model: RidgeModel[RidgePoint],
      point: RidgePoint
  ): Double =
    model.run(point) match
      case Left(error)  => fail(s"unexpected prediction failure: $error")
      case Right(value) => value

  private def assertAgreement(
      models: Vector[RidgeModel[RidgePoint]],
      probes: Vector[RidgePoint],
      tolerance: Double
  ): Unit =
    assertEquals(models.map(_.solution.coefficientCount).distinct, Vector(2))
    probes.foreach { probe =>
      val values = models.map(prediction(_, probe))
      values match
        case reference +: others =>
          others.foreach(value =>
            assertEqualsDouble(value, reference, tolerance)
          )
        case _ => fail("agreement requires at least one backend")
    }
    val objectives = models.map(_.solution.objective)
    objectives match
      case reference +: others =>
        others.foreach(value =>
          assertEqualsDouble(value, reference, tolerance)
        )
      case _ => fail("agreement requires at least one objective")
    models.foreach { model =>
      assert(model.solution.kktResidual.isFinite)
      assert(model.solution.kktResidual <= tolerance * 100.0)
      assert(model.solution.receipt.residual.nonEmpty)
    }

  test("all backends agree on full-rank predictions and objectives") {
    val training = data(
      Vector(
        RidgePoint(0.0, 0.0) -> 1.0,
        RidgePoint(1.0, 0.0) -> 3.0,
        RidgePoint(0.0, 1.0) -> 0.5,
        RidgePoint(1.0, 1.0) -> 2.5,
        RidgePoint(2.0, -1.0) -> 5.5
      )
    )
    val models = backends.map(fit(_, training, config(0.25)))
    assertAgreement(
      models,
      Vector(
        RidgePoint(-1.5, 0.25),
        RidgePoint(0.5, -3.0),
        RidgePoint(4.0, 2.0)
      ),
      2.0e-7
    )
  }

  test("weighted centering agrees without learner-side feature rebuilding") {
    val training = weightedData(
      Vector(
        (RidgePoint(-2.0, 1.0), -4.4, 0.25),
        (RidgePoint(-1.0, -1.0), -1.1, 4.0),
        (RidgePoint(0.0, 2.0), 0.2, 1.5),
        (RidgePoint(1.0, 0.5), 3.15, 0.75),
        (RidgePoint(2.0, -2.0), 6.6, 3.0),
        (RidgePoint(3.0, 1.5), 7.45, 2.0)
      )
    )
    val models = backends.map(fitWeighted(_, training, config(0.4)))
    assertAgreement(
      models,
      Vector(
        RidgePoint(-0.25, 1.25),
        RidgePoint(2.5, -1.5)
      ),
      3.0e-7
    )
  }

  test("prediction agreement is stable for nearly collinear coordinates") {
    val training = data(
      Vector.tabulate(20) { index =>
        val x = index.toDouble - 8.0
        val z = x * (1.0 + 1.0e-8) + (index % 3).toDouble * 1.0e-9
        RidgePoint(x, z) -> (1.5 + 2.0 * x - 0.5 * z)
      }
    )
    val models = backends.map(fit(_, training, config(1.0e-3)))
    assertAgreement(
      models,
      Vector(RidgePoint(-3.5, -3.500000034), RidgePoint(12.0, 12.00000012)),
      2.0e-5
    )
  }

  test("LSQR damping uses sqrt(lambda) and matches the analytic ridge answer") {
    val training = data(
      Vector(
        RidgePoint(1.0, 0.0) -> 2.0,
        RidgePoint(2.0, 0.0) -> 4.0,
        RidgePoint(3.0, 0.0) -> 5.0
      )
    )
    val model = fit(
      new Linop4sRidgeBackend[Id](
        Linop4sRidgeStrategy.LSQR,
        500,
        NumericMode.Deterministic
      ),
      training,
      config(7.0, fitIntercept = false)
    )
    val expected = (1.0 * 2.0 + 2.0 * 4.0 + 3.0 * 5.0) /
      (1.0 + 4.0 + 9.0 + 7.0)
    assertEqualsDouble(model.solution.coefficient(0), expected, 1.0e-9)
    assertEqualsDouble(model.solution.coefficient(1), 0.0, 1.0e-12)
  }

  test("zero-penalty QR and LSQR agree with the analytic OLS solution") {
    val training = data(
      Vector(
        RidgePoint(0.0, 0.0) -> 1.0,
        RidgePoint(1.0, 0.0) -> 3.0,
        RidgePoint(0.0, 1.0) -> 0.5,
        RidgePoint(1.0, 1.0) -> 2.5,
        RidgePoint(2.0, -1.0) -> 5.5
      )
    )
    val zeroPenaltyBackends: Vector[RidgeBackend[Id]] =
      Vector(
        new GaleRidgeBackend[Id](
          PureBackend,
          GaleRidgeStrategy.AugmentedQR,
          NumericMode.Deterministic
        ),
        new GaleRidgeBackend[Id](
          PureBackend,
          GaleRidgeStrategy.NormalCholesky,
          NumericMode.Deterministic
        ),
        new Linop4sRidgeBackend[Id](
          Linop4sRidgeStrategy.LSQR,
          1000,
          NumericMode.Deterministic
        )
      )
    val models = zeroPenaltyBackends.map(fit(_, training, config(0.0)))
    models.foreach { model =>
      assertEqualsDouble(model.solution.intercept, 1.0, 1.0e-10)
      assertEqualsDouble(model.solution.coefficient(0), 2.0, 1.0e-10)
      assertEqualsDouble(model.solution.coefficient(1), -0.5, 1.0e-10)
    }
    assertAgreement(
      models,
      Vector(RidgePoint(-0.5, 4.0), RidgePoint(2.5, -3.0)),
      1.0e-9
    )
  }

  test("a fitted model never returns a successful non-finite prediction") {
    val training =
      data(Vector(RidgePoint(1.0, 2.0) -> 3.0, RidgePoint(2.0, 1.0) -> 4.0))
    val model = fit(
      new GaleRidgeBackend[Id](
        PureBackend,
        GaleRidgeStrategy.AugmentedQR,
        NumericMode.Deterministic
      ),
      training,
      config(1.0)
    )
    model.run(RidgePoint(Double.PositiveInfinity, 1.0)) match
      case Left(
            Failure(
              _,
              RidgePredictionError.NonFiniteFeature(
                "x",
                Double.PositiveInfinity
              )
            )
          ) => ()
      case other => fail(s"expected non-finite prediction failure, got $other")
  }

  test("CG rejects a zero penalty through the typed backend error") {
    val training =
      data(Vector(RidgePoint(1.0, 2.0) -> 3.0, RidgePoint(2.0, 1.0) -> 4.0))
    val backend =
      new Linop4sRidgeBackend[Id](
        Linop4sRidgeStrategy.NormalCG,
        100,
        NumericMode.Deterministic
      )
    new RidgeRegression[Id, RidgePoint, Unit](config(0.0), backend)
      .fit(training)(using context)
      .value match
      case Left(
            Failure(
              _,
              RidgeBackendError.RequiresPositivePenalty(
                SolverId.Linop4sCG
              )
            )
          ) => ()
      case other => fail(s"expected positive-penalty rejection, got $other")
  }

  test("non-finite training values fail before a solver is invoked") {
    val training =
      data(Vector(RidgePoint(Double.NaN, 1.0) -> 2.0))
    fitFailure(training) match
      case RidgeBackendError.NonFiniteFeature(row, name, value) =>
        assertEquals(row, RowId(0L))
        assertEquals(name, "x")
        assert(value.isNaN)
      case other => fail(s"expected non-finite feature failure, got $other")
  }

  private def fitFailure(
      training: NonEmptyData[
        Use.Train,
        Example[RidgePoint, Double, Unit]
      ]
  ): RidgeBackendError =
    new RidgeRegression[Id, RidgePoint, Unit](
      config(1.0),
      new GaleRidgeBackend[Id](
        PureBackend,
        GaleRidgeStrategy.AugmentedQR,
        NumericMode.Deterministic
      )
    ).fit(training)(using context).value match
      case Left(Failure(_, error)) => error
      case Right(_)                => fail("invalid training data fitted")

package alder.data

import alder.kernel.*
import alder.testkit.*
import cats.Id
import scala.compiletime.testing.typeCheckErrors

class CrossFittedSuite extends munit.FunSuite:
  private def fingerprint(label: String): DataFingerprint =
    new DataFingerprint(FingerprintPolicy.Summary("crossfit-test"), label)

  private def examples[U <: Use.Fit](
      count: Int
  ): NonEmptyData[U, Example[Double, Double, String]] =
    val rows = Vector.tabulate(count) { index =>
      (
        RowId(index.toLong),
        Example(index.toDouble, index.toDouble * 10.0, s"m$index")
      )
    }
    DataRows.nonEmpty[U, Example[Double, Double, String]](
      rows,
      fingerprint(s"examples-$count")
    ) match
      case Right(data) => data
      case Left(error) => fail(s"unexpected fixture error: $error")

  private def rowsOf[U <: Use, A](
      data: NonEmptyData[U, A]
  ): Vector[(Long, A)] =
    data.data.foldRows(Vector.empty[(Long, A)])((rows, id, value) =>
      rows :+ (id.value, value)
    )

  private def rootContext: FitContext =
    FitContext.root(
      seed = Seed(101L),
      plan = PlanFingerprint("crossfit-suite"),
      schema = SchemaFingerprint("trace-example"),
      numericMode = NumericMode.Deterministic
    )

  private def kfold(
      count: Int
  ): KFold[Example[Double, Double, String]] =
    KFold[Example[Double, Double, String]](count, shuffle = false) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected KFold error: $error")

  test("crossFitted excludes every row from the state producing its OOF value") {
    val data = examples[Use.Train](6)
    val feature =
      FeatureMap.crossFitted(new VisibilityEncoder, kfold(3))
    val result = feature.fit(data)(using rootContext).value

    result match
      case Left(failure) => fail(s"unexpected cross-fit failure: $failure")
      case Right(prepared) =>
        val learnerReady
            : Prepared[
              Preparation.LearnerReady,
              Use.Train,
              feature.Fitted,
              Example[VisibilityValue, Double, String]
            ] = prepared
        val rows = rowsOf(learnerReady.rows)
        assertEquals(rows.map(_._1), Vector.range(0, 6).map(_.toLong))
        assertEquals(rows.map(_._2.target), Vector(0.0, 10.0, 20.0, 30.0, 40.0, 50.0))
        assertEquals(
          rows.map(_._2.meta),
          Vector("m0", "m1", "m2", "m3", "m4", "m5")
        )
        rows.foreach { (id, example) =>
          assert(!example.input.fittedOn.contains(RowId(id)))
          assertEquals(example.input.fittedOn.size, 4)
        }
  }

  test("crossFitted records compact fold receipt and all-row serving state") {
    val data = examples[Use.Train](6)
    val feature =
      FeatureMap.crossFitted(new VisibilityEncoder, kfold(3))
    val result = feature.fit(data)(using rootContext).value

    result match
      case Left(failure) => fail(s"unexpected cross-fit failure: $failure")
      case Right(prepared) =>
        prepared.lineage.crossFit match
          case None => fail("expected cross-fit lineage receipt")
          case Some(receipt) =>
            assertEquals(receipt.seed, Seed(101L))
            assertEquals(receipt.folds.map(_.index), Vector(0, 1, 2))
            assertEquals(
              receipt.folds.map(_.fittedState.stage),
              Vector.range(0, 3).map(StagePath.root.child)
            )
            assertEquals(receipt.serving.stage, StagePath.root.child(3))
            receipt.resampler.policy match
              case FingerprintPolicy.ContentDigest(algorithm) =>
                assertEquals(algorithm, "fnv1a64")
              case policy =>
                fail(s"expected content-digest resampler, got $policy")
            receipt.assignment.policy match
              case FingerprintPolicy.ContentDigest(algorithm) =>
                assertEquals(algorithm, "fnv1a64")
              case policy =>
                fail(s"expected content-digest assignment, got $policy")
        assertEquals(prepared.fitted.audit.children.length, 4)
        prepared.fitted.artifact.run(2.0) match
          case Left(failure) => fail(s"unexpected serving failure: $failure")
          case Right(value) =>
            assertEquals(
              value.fittedOn,
              Set(
                RowId(0L),
                RowId(1L),
                RowId(2L),
                RowId(3L),
                RowId(4L),
                RowId(5L)
              )
            )
  }

  test("crossFitted preserves Refit role through final serving fit") {
    val data = examples[Use.Refit](4)
    val feature =
      FeatureMap.crossFitted(new VisibilityEncoder, kfold(2))
    val result = feature.fit(data)(using rootContext).value

    result match
      case Left(failure) => fail(s"unexpected refit failure: $failure")
      case Right(prepared) =>
        val refit
            : Prepared[
              Preparation.LearnerReady,
              Use.Refit,
              feature.Fitted,
              Example[VisibilityValue, Double, String]
            ] = prepared
        assertEquals(rowsOf(refit.rows).length, 4)
  }

  test("assessment encode failure retains the fold state's stage") {
    val data = examples[Use.Train](4)
    val feature =
      FeatureMap.crossFitted(new VisibilityEncoder(Some(2.0)), kfold(2))
    val result = feature.fit(data)(using rootContext).value

    result match
      case Right(_) => fail("expected assessment encode failure")
      case Left(failure) =>
        assertEquals(failure.stage, StagePath.root.child(0))
        assertEquals(failure.cause, VisibilityRunError.Rejected(2.0))
  }

  test("an incomplete Resampler cannot drive crossFitted") {
    val errors = typeCheckErrors(
      """import alder.kernel.*
import alder.data.*
import cats.Id
def illegal(
  encoder: FoldEncoder[Id, Double, Double, Unit, Double],
  resampler: Resampler[Example[Double, Double, Unit]]
): Unit =
  val _ = FeatureMap.crossFitted(encoder, resampler)
"""
    )
    assert(errors.nonEmpty)
  }

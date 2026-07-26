package alder.data

import alder.kernel.{Seed as AlderSeed, *}
import alder.testkit.*
import cats.kernel.Hash
import org.scalacheck.{Gen, Prop, Test}
import scala.compiletime.testing.typeCheckErrors
import tessera.core.{Seed as TesseraSeed, *}
import tessera.designs.{KFold as TesseraKFold}

final case class AdapterMeta(group: String)

class TesseraResamplerSuite extends munit.FunSuite:
  private given GroupOf[AdapterMeta] with
    type Key = String
    def apply(meta: AdapterMeta): String = meta.group

  private given Hash[String] with
    def hash(value: String): Int = value.hashCode()
    def eqv(left: String, right: String): Boolean = left == right

  private def alderFingerprint(label: String): DataFingerprint =
    new DataFingerprint(
      FingerprintPolicy.Summary("alder.tessera-test/v1"),
      label
    )

  private def train[A](
      values: Vector[A],
      label: String = "population"
  ): NonEmptyData[Use.Train, A] =
    val rows = values.zipWithIndex.map { (value, index) =>
      (RowId(index.toLong), value)
    }
    DataRows.nonEmpty[Use.Train, A](rows, alderFingerprint(label)) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected fixture failure: $error")

  private def rowsOf[U <: Use, A](
      data: NonEmptyData[U, A]
  ): Vector[(Long, A)] =
    data.data.foldRows(Vector.empty[(Long, A)])((rows, id, value) =>
      rows :+ (id.value, value)
    )

  private def exactCompiled(
      size: Int,
      folds: Int,
      seed: Long
  ): Compiled[Split[Selection], Coverage.ExactOnce] =
    val space = IndexSpace.of(size) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected space failure: $error")
    TesseraKFold(folds).compile(space, TesseraSeed.fromLong(seed)) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected compile failure: $error")

  private def adapter[A](
      data: NonEmptyData[Use.Train, A],
      folds: Int,
      seed: Long
  ): CompleteResampler[A] =
    val compiled = exactCompiled(data.size.toInt, folds, seed)
    val population =
      TesseraResampler.populationFingerprint(data.fingerprint) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected population failure: $error")
    TesseraResampler.fromCompiled[A](compiled, population)(
      using DigestAlgorithm.fnv1a64
    ) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected receipt failure: $error")

  private def plan[A](
      resampler: CompleteResampler[A],
      data: NonEmptyData[Use.Train, A],
      seed: Long
  ): ResamplingPlan[Use.Train, A] =
    resampler.split(data, AlderSeed(seed)) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected adapter failure: $error")

  test("exact-once plans construct CompleteResampler without a coverage check") {
    val data = train(Vector.range(0, 12))
    val compiled = exactCompiled(12, 4, 91L)
    val population =
      TesseraResampler.populationFingerprint(data.fingerprint) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected population failure: $error")
    val receipt =
      compiled.receipt(population)(using DigestAlgorithm.fnv1a64) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected receipt failure: $error")

    val complete: CompleteResampler[Int] =
      TesseraResampler.complete(compiled.plan, receipt)
    assertEquals(plan(complete, data, 91L).foldCount, 4)
  }

  test("adapter laws cover rows once, keep folds disjoint, and reconstruct order") {
    val data = train(Vector.range(0, 17))
    val resampler = adapter(data, folds = 5, seed = 77L)
    val first = plan(resampler, data, 77L)
    val replay = plan(resampler, data, 77L)

    assertEquals(first.assignment.digest, replay.assignment.digest)
    val assessments =
      first.folds.flatMap(fold => rowsOf(fold.assessment))
    assertEquals(
      assessments.map(_._1).sorted,
      Vector.range(0, 17).map(_.toLong)
    )
    assertEquals(assessments.map(_._1).distinct.length, 17)
    first.folds.foreach { fold =>
      val analysis = rowsOf(fold.analysis).map(_._1)
      val assessment = rowsOf(fold.assessment).map(_._1)
      assertEquals(analysis, analysis.sorted)
      assertEquals(assessment, assessment.sorted)
      assertEquals(analysis.toSet.intersect(assessment.toSet), Set.empty[Long])
      assertEquals(analysis.length + assessment.length, 17)
    }
  }

  test("adapter retains the policy-tagged Tessera receipt in cross-fit lineage") {
    val values = Vector.tabulate(8) { index =>
      Example(index.toDouble, index.toDouble, s"m$index")
    }
    val data = train(values)
    val resampler = adapter(data, folds = 4, seed = 101L)
    val feature =
      FeatureMap.crossFitted(new VisibilityEncoder, resampler)
    given FitContext =
      FitContext.root(
        seed = AlderSeed(101L),
        plan = PlanFingerprint("tessera-crossfit"),
        schema = SchemaFingerprint("tessera-example"),
        numericMode = NumericMode.Deterministic
      )

    feature.fit(data).value match
      case Left(failure) => fail(s"unexpected cross-fit failure: $failure")
      case Right(prepared) =>
        val produced = rowsOf(prepared.rows)
        produced.foreach { (id, example) =>
          assert(!example.input.fittedOn.contains(RowId(id)))
        }
        prepared.lineage.crossFit.flatMap(_.tessera) match
          case None => fail("expected mapped Tessera receipt")
          case Some(receipt) =>
            assertEquals(receipt.designAlgorithm, "kfold/v1")
            assertEquals(receipt.digestAlgorithm, "fnv1a64/v1")
            assertEquals(receipt.planSeed, AlderSeed(101L))
            receipt.design.policy match
              case FingerprintPolicy.ContentDigest(algorithm) =>
                assertEquals(algorithm, "fnv1a64/v1")
              case policy =>
                fail(s"expected design content digest, got $policy")
            receipt.population.policy match
              case FingerprintPolicy.Summary(policyId) =>
                assertEquals(policyId, "alder.tessera-test/v1")
              case policy =>
                fail(s"expected summary population, got $policy")
  }

  test("group metadata becomes canonical labels and remains group atomic") {
    val values = Vector.tabulate(12) { index =>
      Example(index, index, AdapterMeta(s"g${index / 3}"))
    }
    val data = train(values)
    val labels = TesseraResampler.groupLabels(data) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected label failure: $error")
    val space = IndexSpace.of(12) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected space failure: $error")
    val compiled =
      TesseraKFold
        .grouped(3, labels)
        .compile(space, TesseraSeed.fromLong(33L)) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected grouped failure: $error")
    val population =
      TesseraResampler.populationFingerprint(data.fingerprint) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected population failure: $error")
    val resampler =
      TesseraResampler.fromCompiled[
        Example[Int, Int, AdapterMeta]
      ](compiled, population)(
        using DigestAlgorithm.fnv1a64
      ) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected receipt failure: $error")
    val groupedPlan = plan(resampler, data, 33L)
    assert(groupedPlan.tessera.flatMap(_.labels).nonEmpty)
    val groupFolds = groupedPlan.folds.flatMap { fold =>
      rowsOf(fold.assessment).map(row => row._2.meta.group -> fold.index)
    }
    groupFolds.groupMap(_._1)(_._2).values.foreach(indices =>
      assertEquals(indices.distinct.length, 1)
    )
  }

  test("bound plan rejects seed, size, and population identity mismatches") {
    val data = train(Vector.range(0, 8), "first")
    val resampler = adapter(data, folds = 4, seed = 9L)
    assertEquals(
      resampler.split(data, AlderSeed(10L)),
      Left(DataError.TesseraSeedMismatch(9L, 10L))
    )
    val short = train(Vector.range(0, 7), "first")
    assertEquals(
      resampler.split(short, AlderSeed(9L)),
      Left(DataError.TesseraPopulationSizeMismatch(8, 7L))
    )
    val different = train(Vector.range(0, 8), "second")
    assertEquals(
      resampler.split(different, AlderSeed(9L)),
      Left(DataError.TesseraPopulationFingerprintMismatch)
    )
  }

  test("malformed Alder content digests fail before receipt construction") {
    val malformed = new DataFingerprint(
      FingerprintPolicy.ContentDigest("sha256"),
      "not-hex"
    )
    assertEquals(
      TesseraResampler.populationFingerprint(malformed),
      Left(
        DataError.InvalidTesseraPopulationFingerprint(
          malformed.policy,
          malformed.digest
        )
      )
    )
  }

  test("Holdout, Bootstrap, and repeated exact plans cannot mint completeness") {
    val errors = typeCheckErrors(
      """import alder.data.*
import tessera.core.*
def holdout(
  plan: Plan[Split[Selection], Coverage],
  receipt: PlanReceipt
): CompleteResampler[Int] =
  TesseraResampler.complete(plan, receipt)
def bootstrap(
  plan: Plan[Split[Draw], Coverage],
  receipt: PlanReceipt
): CompleteResampler[Int] =
  TesseraResampler.complete(plan, receipt)
def repeated(
  plan: Plan[Split[Selection], Coverage.Exact],
  receipt: PlanReceipt
): CompleteResampler[Int] =
  TesseraResampler.complete(plan, receipt)
"""
    )
    assertEquals(errors.length, 3)
  }

  test("adapter invariants hold over generated sizes, folds, and seeds") {
    val property = Prop.forAll(
      Gen.choose(2, 50),
      Gen.choose(0, 500),
      Gen.choose(Long.MinValue, Long.MaxValue)
    ) { (rowCount, selector, rawSeed) =>
      val foldCount = 2 + selector % (rowCount - 1)
      val data = train(Vector.range(0, rowCount), s"generated-$rowCount")
      val resampler = adapter(data, foldCount, rawSeed)
      val observed = plan(resampler, data, rawSeed)
      val replay = plan(resampler, data, rawSeed)
      val assessments =
        observed.folds.flatMap(fold => rowsOf(fold.assessment))
      val coverage =
        assessments.map(_._1).sorted ==
          Vector.range(0, rowCount).map(_.toLong)
      val unique = assessments.map(_._1).distinct.length == rowCount
      val complements = observed.folds.forall { fold =>
        val analysis = rowsOf(fold.analysis).map(_._1).toSet
        val assessment = rowsOf(fold.assessment).map(_._1).toSet
        analysis.intersect(assessment).isEmpty &&
        analysis.size + assessment.size == rowCount
      }
      coverage && unique && complements &&
      observed.assignment.digest == replay.assignment.digest
    }
    val result = Test.check(
      Test.Parameters.default.withMinSuccessfulTests(100),
      property
    )
    assert(result.passed, result.toString)
  }

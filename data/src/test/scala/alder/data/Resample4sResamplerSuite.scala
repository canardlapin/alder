package alder.data

import alder.kernel.{Seed as AlderSeed, *}
import alder.testkit.*
import cats.Id
import cats.data.EitherT
import cats.kernel.Hash
import org.scalacheck.{Gen, Prop, Test}
import scala.compiletime.testing.typeCheckErrors
import resample4s.core.{Seed as Resample4sSeed, *}
import resample4s.designs.{KFold as Resample4sKFold}

final case class AdapterMeta(group: String)

final class Resample4sResamplerSuite extends munit.FunSuite:
  private final class Offset(amount: Double)
      extends Transform[Id, Double, Double]:
    type FitError = Nothing
    type RunError = Nothing
    type Fitted   = Pipe[Double, Nothing, Double]

    def fit[U <: Use.Fit](
        data: NonEmptyData[U, Double]
    )(using context: FitContext): FitResult[
      Id,
      Nothing,
      Prepared[Preparation.Reusable, U, Fitted, Double]
    ] =
      EitherT.fromEither(
        context.completeTransform(
          Pipe.total(_ + amount),
          data,
          ComponentDescriptor(
            ComponentId("alder.test.offset"),
            ComponentVersion("1"),
            AuditValue.record("amount" -> AuditValue.decimal(amount)),
            BackendFingerprint("test", "1", AuditValue.record())
          )
        )
      )

  private final class VisibilityModel(
      val observed: Vector[(RowId, VisibilityValue)]
  ) extends Pipe[VisibilityValue, Nothing, Double]:
    def run(
        value: VisibilityValue
    ): Either[Failure[Nothing], Double] = Right(value.input)

  private final class VisibilityLearner
      extends Learner[Id, VisibilityValue, Double, String, Double]:
    type FitError = Nothing
    type RunError = Nothing
    type Model    = VisibilityModel

    def fit[U <: Use.Fit](
        data: NonEmptyData[U, Example[VisibilityValue, Double, String]]
    )(using context: FitContext): FitResult[Id, Nothing, Trained[Model]] =
      val observed = data.data.foldRows(
        Vector.empty[(RowId, VisibilityValue)]
      )((rows, id, example) => rows :+ (id, example.input))
      EitherT.right(
        context.complete(
          new VisibilityModel(observed),
          data,
          ComponentDescriptor(
            ComponentId("alder.test.resample4s-visibility-learner"),
            ComponentVersion("1"),
            AuditValue.record(),
            BackendFingerprint("test", "1", AuditValue.record())
          )
        )
      )

  private given GroupOf[AdapterMeta] with
    type Key = String
    def apply(meta: AdapterMeta): String = meta.group

  private given Hash[String] with
    def hash(value: String): Int                  = value.hashCode()
    def eqv(left: String, right: String): Boolean = left == right

  private def alderFingerprint(label: String): DataFingerprint =
    new DataFingerprint(
      FingerprintPolicy.Summary("alder.resample4s-test/v1"),
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

  private def crossFitIn(
      lineage: PreparationLineage
  ): Option[CrossFitLineage] =
    lineage.crossFit match
      case present @ Some(_) => present
      case None =>
        lineage.children.iterator
          .flatMap(child => crossFitIn(child).iterator)
          .nextOption

  private def exactCompiled(
      size: Int,
      folds: Int,
      seed: Long
  ): Compiled[Split[Selection], Coverage.ExactOnce] =
    val space = IndexSpace.of(size) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected space failure: $error")
    Resample4sKFold(folds).compile(space, Resample4sSeed.fromLong(seed)) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected compile failure: $error")

  private def adapter[A](
      data: NonEmptyData[Use.Train, A],
      folds: Int,
      seed: Long
  ): CompleteResampler[A] =
    val compiled = exactCompiled(data.size.toInt, folds, seed)
    val population =
      Resample4sResampler.populationFingerprint(data.fingerprint) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected population failure: $error")
    Resample4sResampler.fromCompiled[A](compiled, population)(using
      DigestAlgorithm.fnv1a64
    ) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected receipt failure: $error")

  private def deferred[A](folds: Int): CompleteResampler[A] =
    Resample4sResampler.fromDesign[A](Resample4sKFold(folds))(using
      DigestAlgorithm.fnv1a64
    ) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected design failure: $error")

  private def plan[A](
      resampler: CompleteResampler[A],
      data: NonEmptyData[Use.Train, A],
      seed: Long
  ): ResamplingPlan[Use.Train, A] =
    resampler.split(data, AlderSeed(seed)) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected adapter failure: $error")

  test(
    "exact-once plans construct CompleteResampler without a coverage check"
  ) {
    val data     = train(Vector.range(0, 12))
    val compiled = exactCompiled(12, 4, 91L)
    val population =
      Resample4sResampler.populationFingerprint(data.fingerprint) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected population failure: $error")
    val receipt =
      compiled.receipt(population)(using DigestAlgorithm.fnv1a64) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected receipt failure: $error")

    val complete: CompleteResampler[Int] =
      Resample4sResampler.complete(compiled.plan, receipt)
    assertEquals(plan(complete, data, 91L).foldCount, 4)
  }

  test(
    "adapter laws cover rows once, keep folds disjoint, and reconstruct order"
  ) {
    val data      = train(Vector.range(0, 17))
    val resampler = adapter(data, folds = 5, seed = 77L)
    val first     = plan(resampler, data, 77L)
    val replay    = plan(resampler, data, 77L)

    assertEquals(first.assignment.digest, replay.assignment.digest)
    val assessments =
      first.folds.flatMap(fold => rowsOf(fold.assessment))
    assertEquals(
      assessments.map(_._1).sorted,
      Vector.range(0, 17).map(_.toLong)
    )
    assertEquals(assessments.map(_._1).distinct.length, 17)
    first.folds.foreach { fold =>
      val analysis   = rowsOf(fold.analysis).map(_._1)
      val assessment = rowsOf(fold.assessment).map(_._1)
      assertEquals(analysis, analysis.sorted)
      assertEquals(assessment, assessment.sorted)
      assertEquals(analysis.toSet.intersect(assessment.toSet), Set.empty[Long])
      assertEquals(analysis.length + assessment.length, 17)
    }
  }

  test(
    "adapter retains the policy-tagged Resample4s receipt in cross-fit lineage"
  ) {
    val values = Vector.tabulate(8) { index =>
      Example(index.toDouble, index.toDouble, s"m$index")
    }
    val data      = train(values)
    val resampler = adapter(data, folds = 4, seed = 101L)
    val feature =
      FeatureMap.crossFitted(new VisibilityEncoder, resampler)
    given FitContext =
      FitContext.root(
        seed = AlderSeed(101L),
        plan = PlanFingerprint("resample4s-crossfit"),
        schema = SchemaFingerprint("resample4s-example"),
        numericMode = NumericMode.Deterministic
      )

    feature.fit(data).value match
      case Left(failure) => fail(s"unexpected cross-fit failure: $failure")
      case Right(prepared) =>
        val produced = rowsOf(prepared.rows)
        produced.foreach { (id, example) =>
          assert(!example.input.fittedOn.contains(RowId(id)))
        }
        prepared.lineage.crossFit.flatMap(_.resample4s) match
          case None => fail("expected mapped Resample4s receipt")
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
                assertEquals(policyId, "alder.resample4s-test/v1")
              case policy =>
                fail(s"expected summary population, got $policy")
  }

  test(
    "design adapter binds the normalized child seed inside a learned workflow"
  ) {
    val values = Vector.tabulate(12) { index =>
      Example(index.toDouble, index.toDouble * 10.0, s"m$index")
    }
    val data      = train(values, "deferred-workflow")
    val resampler = deferred[Example[Double, Double, String]](4)
    val learner   = new VisibilityLearner
    val workflow =
      FeatureMap
        .crossFitted(new VisibilityEncoder, resampler)
        .learnWith(learner)
    val rootSeed = AlderSeed(17L)
    given FitContext =
      FitContext.root(
        rootSeed,
        PlanFingerprint("resample4s-deferred-workflow"),
        SchemaFingerprint("resample4s-example"),
        NumericMode.Deterministic
      )

    workflow.fit(data).value match
      case Left(failure) => fail(s"unexpected workflow failure: $failure")
      case Right(trained) =>
        val crossFit = trained.audit.preparation.crossFit match
          case Some(value) => value
          case None        => fail("expected cross-fit lineage")
        assertNotEquals(crossFit.seed, rootSeed)
        crossFit.resample4s match
          case Some(receipt) =>
            assertEquals(receipt.planSeed, crossFit.seed)
          case None => fail("expected Resample4s receipt")
        workflow.terminalModel(trained) match
          case Left(error) => fail(s"unexpected terminal focus: $error")
          case Right(terminal) =>
            assertEquals(
              terminal.artifact.observed.map(_._1.value),
              Vector.range(0, values.length).map(_.toLong)
            )
            terminal.artifact.observed.foreach { (id, value) =>
              assert(!value.fittedOn.contains(id))
            }

    val replay = workflow.fit(data).value match
      case Left(failure) => fail(s"unexpected replay failure: $failure")
      case Right(trained) =>
        trained.audit.preparation.crossFit match
          case Some(value) => value
          case None        => fail("expected replay cross-fit lineage")
    val first = workflow.fit(data).value match
      case Left(failure) => fail(s"unexpected first failure: $failure")
      case Right(trained) =>
        trained.audit.preparation.crossFit match
          case Some(value) => value
          case None        => fail("expected first cross-fit lineage")
    assertEquals(replay.seed, first.seed)
    assertEquals(replay.assignment.policy, first.assignment.policy)
    assertEquals(replay.assignment.digest, first.assignment.digest)
  }

  test("design adapter retains Resample4s compile failures") {
    val data      = train(Vector.range(0, 3), "too-few-rows")
    val resampler = deferred[Int](4)
    assertEquals(
      resampler.split(data, AlderSeed(5L)),
      Left(
        DataError.Resample4sDesignFailure(
          DesignError.TooManyFolds(4, 3)
        )
      )
    )
  }

  test(
    "design adapter follows normalized stage seeds across parenthesized prefixes"
  ) {
    val values = Vector.tabulate(12) { index =>
      Example(index.toDouble, index.toDouble * 10.0, s"m$index")
    }
    val data      = train(values, "deferred-parenthesized")
    val resampler = deferred[Example[Double, Double, String]](4)
    val first     = new Offset(1.0)
    val second    = new Offset(2.0)
    val feature =
      FeatureMap.crossFitted(new VisibilityEncoder, resampler)
    val left =
      first.andThen(second).andThen(feature).learnWith(new VisibilityLearner)
    val right =
      first.andThen(second.andThen(feature)).learnWith(new VisibilityLearner)
    given FitContext =
      FitContext.root(
        AlderSeed(29L),
        PlanFingerprint("resample4s-parenthesized"),
        SchemaFingerprint("resample4s-example"),
        NumericMode.Deterministic
      )

    val leftFit = left.fit(data).value match
      case Left(failure) => fail(s"unexpected left failure: $failure")
      case Right(value)  => value
    val rightFit = right.fit(data).value match
      case Left(failure) => fail(s"unexpected right failure: $failure")
      case Right(value)  => value
    val leftCrossFit = crossFitIn(leftFit.audit.preparation) match
      case Some(value) => value
      case None        => fail("expected left cross-fit lineage")
    val rightCrossFit = crossFitIn(rightFit.audit.preparation) match
      case Some(value) => value
      case None        => fail("expected right cross-fit lineage")
    assertEquals(leftCrossFit.seed, rightCrossFit.seed)
    assertEquals(
      leftCrossFit.assignment.policy,
      rightCrossFit.assignment.policy
    )
    assertEquals(
      leftCrossFit.assignment.digest,
      rightCrossFit.assignment.digest
    )
    leftCrossFit.resample4s match
      case Some(receipt) => assertEquals(receipt.planSeed, leftCrossFit.seed)
      case None          => fail("expected left Resample4s receipt")
    left.terminalModel(leftFit) match
      case Left(error) => fail(s"unexpected left terminal focus: $error")
      case Right(terminal) =>
        terminal.artifact.observed.foreach { (id, value) =>
          assert(!value.fittedOn.contains(id))
        }
  }

  test("group metadata becomes canonical labels and remains group atomic") {
    val values = Vector.tabulate(12) { index =>
      Example(index, index, AdapterMeta(s"g${index / 3}"))
    }
    val data = train(values)
    val labels = Resample4sResampler.groupLabels(data) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected label failure: $error")
    val space = IndexSpace.of(12) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected space failure: $error")
    val compiled =
      Resample4sKFold
        .grouped(3, labels)
        .compile(space, Resample4sSeed.fromLong(33L)) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected grouped failure: $error")
    val population =
      Resample4sResampler.populationFingerprint(data.fingerprint) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected population failure: $error")
    val resampler =
      Resample4sResampler.fromCompiled[
        Example[Int, Int, AdapterMeta]
      ](compiled, population)(using
        DigestAlgorithm.fnv1a64
      ) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected receipt failure: $error")
    val groupedPlan = plan(resampler, data, 33L)
    assert(groupedPlan.resample4s.flatMap(_.labels).nonEmpty)
    val groupFolds = groupedPlan.folds.flatMap { fold =>
      rowsOf(fold.assessment).map(row => row._2.meta.group -> fold.index)
    }
    groupFolds
      .groupMap(_._1)(_._2)
      .values
      .foreach(indices => assertEquals(indices.distinct.length, 1))
  }

  test("bound plan rejects seed, size, and population identity mismatches") {
    val data      = train(Vector.range(0, 8), "first")
    val resampler = adapter(data, folds = 4, seed = 9L)
    assertEquals(
      resampler.split(data, AlderSeed(10L)),
      Left(DataError.Resample4sSeedMismatch(9L, 10L))
    )
    val short = train(Vector.range(0, 7), "first")
    assertEquals(
      resampler.split(short, AlderSeed(9L)),
      Left(DataError.Resample4sPopulationSizeMismatch(8, 7L))
    )
    val different = train(Vector.range(0, 8), "second")
    assertEquals(
      resampler.split(different, AlderSeed(9L)),
      Left(DataError.Resample4sPopulationFingerprintMismatch)
    )
  }

  test("malformed Alder content digests fail before receipt construction") {
    val malformed = new DataFingerprint(
      FingerprintPolicy.ContentDigest("sha256"),
      "not-hex"
    )
    assertEquals(
      Resample4sResampler.populationFingerprint(malformed),
      Left(
        DataError.InvalidResample4sPopulationFingerprint(
          malformed.policy,
          malformed.digest
        )
      )
    )
  }

  test(
    "only exact-once selection plans and designs can mint completeness"
  ) {
    val errors = typeCheckErrors(
      """import alder.data.*
import resample4s.core.*
def holdout(
  plan: Plan[Split[Selection], Coverage],
  receipt: PlanReceipt
): CompleteResampler[Int] =
  Resample4sResampler.complete(plan, receipt)
def bootstrap(
  plan: Plan[Split[Draw], Coverage],
  receipt: PlanReceipt
): CompleteResampler[Int] =
  Resample4sResampler.complete(plan, receipt)
def repeated(
  plan: Plan[Split[Selection], Coverage.Exact],
  receipt: PlanReceipt
): CompleteResampler[Int] =
  Resample4sResampler.complete(plan, receipt)
"""
    )
    assertEquals(errors.length, 3)

    val incompleteDesign = typeCheckErrors(
      """import alder.data.*
import resample4s.core.*
def incomplete(
  design: Design[Split[Selection], Coverage]
)(using DigestAlgorithm): Either[DigestError, CompleteResampler[Int]] =
  Resample4sResampler.fromDesign[Int](design)
"""
    )
    val drawDesign = typeCheckErrors(
      """import alder.data.*
import resample4s.core.*
def draw(
  design: Design[Split[Draw], Coverage.ExactOnce]
)(using DigestAlgorithm): Either[DigestError, CompleteResampler[Int]] =
  Resample4sResampler.fromDesign[Int](design)
"""
    )
    val repeatedDesign = typeCheckErrors(
      """import alder.data.*
import resample4s.core.*
def repeated(
  design: Design[Split[Selection], Coverage.Exact]
)(using DigestAlgorithm): Either[DigestError, CompleteResampler[Int]] =
  Resample4sResampler.fromDesign[Int](design)
"""
    )
    assert(incompleteDesign.nonEmpty)
    assert(drawDesign.nonEmpty)
    assert(repeatedDesign.nonEmpty)
  }

  test("adapter invariants hold over generated sizes, folds, and seeds") {
    val property = Prop.forAll(
      Gen.choose(2, 50),
      Gen.choose(0, 500),
      Gen.choose(Long.MinValue, Long.MaxValue)
    ) { (rowCount, selector, rawSeed) =>
      val foldCount = 2 + selector % (rowCount - 1)
      val data      = train(Vector.range(0, rowCount), s"generated-$rowCount")
      val resampler = deferred[Int](foldCount)
      val observed  = plan(resampler, data, rawSeed)
      val replay    = plan(resampler, data, rawSeed)
      val assessments =
        observed.folds.flatMap(fold => rowsOf(fold.assessment))
      val coverage =
        assessments.map(_._1).sorted ==
          Vector.range(0, rowCount).map(_.toLong)
      val unique = assessments.map(_._1).distinct.length == rowCount
      val complements = observed.folds.forall { fold =>
        val analysis   = rowsOf(fold.analysis).map(_._1).toSet
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

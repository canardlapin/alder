package alder.data

import alder.kernel.*
import cats.kernel.{Hash, Order}
import org.scalacheck.{Gen, Prop, Test}
import scala.compiletime.testing.typeCheckErrors

final case class TestMeta(group: Int, time: Int)

given GroupOf[TestMeta] with
  type Key = Int
  def apply(meta: TestMeta): Int = meta.group

given TimeOf[TestMeta] with
  type Instant = Int
  def apply(meta: TestMeta): Int = meta.time

given Hash[Int] with
  def hash(value: Int): Int = value
  def eqv(left: Int, right: Int): Boolean = left == right

given Order[Int] with
  def compare(left: Int, right: Int): Int = java.lang.Integer.compare(left, right)

class DataSuite extends munit.FunSuite:
  private def fingerprint(label: String): DataFingerprint =
    new DataFingerprint(FingerprintPolicy.Summary("test"), label)

  private def train[A](values: Vector[A]): NonEmptyData[Use.Train, A] =
    val rows = values.zipWithIndex.map { (value, index) =>
      (RowId(index.toLong), value)
    }
    DataRows.nonEmpty[Use.Train, A](rows, fingerprint("train")) match
      case Right(data) => data
      case Left(error) => fail(s"unexpected fixture error: $error")

  private def rowsOf[U <: Use, A](
      data: Data[U, A]
  ): Vector[(Long, A)] =
    data.foldRows(Vector.empty[(Long, A)])((rows, id, value) =>
      rows :+ (id.value, value)
    )

  private def rows(value: Long): Rows =
    Rows(value) match
      case Right(result) => result
      case Left(error)   => fail(s"unexpected Rows error: $error")

  private def fraction(
      numerator: Long,
      denominator: Long
  ): Fraction =
    Fraction(numerator, denominator) match
      case Right(result) => result
      case Left(error)   => fail(s"unexpected Fraction error: $error")

  private def planOf[A](
      resampler: Resampler[A],
      data: NonEmptyData[Use.Train, A],
      seed: Seed
  ): ResamplingPlan[Use.Train, A] =
    resampler.split(data, seed) match
      case Right(plan) => plan
      case Left(error) => fail(s"unexpected resampling error: $error")

  test("in-memory rows receive stable ids and preserve input order") {
    val data =
      InMemoryData.unsplit(Vector("a", "b", "c"), fingerprint("letters"))
    assertEquals(
      rowsOf(data),
      Vector((0L, "a"), (1L, "b"), (2L, "c"))
    )
  }

  test("external in-memory identity remains explicitly policy tagged") {
    val data = InMemoryData.unsplit(Vector("a", "b"), "letters-v1")
    assertEquals(data.fingerprint.digest, "letters-v1")
    assertEquals(
      data.fingerprint.policy,
      FingerprintPolicy.Summary("alder.external-data-identity")
    )
  }

  test("fit context derives schema and deterministic defaults") {
    final case class Input(value: Double) derives Schema

    val context =
      Fit.context[Input](Seed(23L), "data-suite-fit")

    assertEquals(context.seed.value, 23L)
    assertEquals(context.plan.render, "data-suite-fit")
    assertEquals(context.schema, Schema[Input].fingerprint)
    assertEquals(context.numericMode, NumericMode.Deterministic)
  }

  test("in-memory batched access preserves rows without materialized row copies") {
    val data =
      InMemoryData.unsplit(Vector("a", "b", "c", "d", "e"), fingerprint("batch"))
    var batches = Vector.empty[Vector[(Long, String)]]
    data.foreachBatch(BatchSize.const(2)) { batch =>
      val rows = Vector.tabulate(batch.length)(index =>
        (batch.rowId(index).value, batch.value(index))
      )
      batches = batches :+ rows
    }
    assertEquals(
      batches,
      Vector(
        Vector((0L, "a"), (1L, "b")),
        Vector((2L, "c"), (3L, "d")),
        Vector((4L, "e"))
      )
    )
  }

  test("holdout is deterministic, disjoint, complete, and order preserving") {
    val source =
      InMemoryData.unsplit(Vector.range(0, 10), fingerprint("numbers"))
    val first = Holdout.split(source, testSize = 3, Seed(42L))
    val second = Holdout.split(source, testSize = 3, Seed(42L))

    (first, second) match
      case (Right(left), Right(right)) =>
        val leftTrain = rowsOf(left.train.data)
        val leftTest = rowsOf(left.test.data)
        val rightTrain = rowsOf(right.train.data)
        val rightTest = rowsOf(right.test.data)
        assertEquals(leftTrain, rightTrain)
        assertEquals(leftTest, rightTest)
        assertEquals(leftTrain.map(_._1), leftTrain.map(_._1).sorted)
        assertEquals(leftTest.map(_._1), leftTest.map(_._1).sorted)
        assertEquals(
          (leftTrain ++ leftTest).map(_._1).sorted,
          Vector.range(0, 10).map(_.toLong)
        )
        assertEquals(
          leftTrain.map(_._1).toSet.intersect(leftTest.map(_._1).toSet),
          Set.empty[Long]
        )
      case (Left(error), _) => fail(s"unexpected first split error: $error")
      case (_, Left(error)) => fail(s"unexpected second split error: $error")
  }

  test("holdout rejects empty training or test partitions") {
    val source =
      InMemoryData.unsplit(Vector.range(0, 4), fingerprint("numbers"))
    assertEquals(
      Holdout.split(source, testSize = 0, Seed(1L)),
      Left(DataError.InvalidHoldoutSize(0, 4L))
    )
    assertEquals(
      Holdout.split(source, testSize = 4, Seed(1L)),
      Left(DataError.InvalidHoldoutSize(4, 4L))
    )
  }

  test("RankV1 matches the normative rank and membership vectors") {
    val content =
      new DataFingerprint(
        FingerprintPolicy.ContentDigest("sha256"),
        "abc"
      )
    assertEquals(
      RankV1.rank(content, Seed(0L), RowId(0L)),
      Right(7172581403538783996L)
    )
    assertEquals(
      RankV1.rank(
        new DataFingerprint(
          FingerprintPolicy.SourceIdentity(
            "s3://bucket/data",
            "v1"
          ),
          "summary-42"
        ),
        Seed(17L),
        RowId(-9L)
      ),
      Right(-585442076852022257L)
    )
    assertEquals(
      RankV1.rank(
        new DataFingerprint(
          FingerprintPolicy.Summary("privacy-v1"),
          "deadbeef"
        ),
        Seed(-1L),
        RowId(42L)
      ),
      Right(-4250306088002722665L)
    )

    val source = InMemoryData.unsplit(Vector.range(0, 5), content)
    val holdout =
      Split.holdout(source, HoldoutSpec(rows(2L)), Seed(0L)) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected holdout error: $error")
    assertEquals(
      rowsOf(holdout.train.data).map(_._1),
      Vector(0L, 2L, 3L)
    )
    assertEquals(
      rowsOf(holdout.test.data).map(_._1),
      Vector(1L, 4L)
    )

    val specification =
      TrainValidationTestSpec(
        SplitAmount.Count(rows(1L)),
        SplitAmount.Count(rows(2L))
      ) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected TVT spec error: $error")
    val threeWay =
      Split.trainValidationTest(source, specification, Seed(0L)) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected TVT split error: $error")
    assertEquals(
      rowsOf(threeWay.train.data).map(_._1),
      Vector(0L, 3L)
    )
    assertEquals(
      rowsOf(threeWay.validation.data).map(_._1),
      Vector(1L)
    )
    assertEquals(
      rowsOf(threeWay.test.data).map(_._1),
      Vector(2L, 4L)
    )
  }

  test("split specifications use exact reduced-rational total-N apportionment") {
    val reduced = fraction(6L, 15L)
    assertEquals(reduced.numerator, 2L)
    assertEquals(reduced.denominator, 5L)
    assertEquals(reduced, fraction(2L, 5L))
    assertEquals(rows(2L), rows(2L))
    assertEquals(Rows(0L), Left(DataError.InvalidRows(0L)))
    assertEquals(
      Fraction(1L, 1L),
      Left(DataError.InvalidFraction(1L, 1L))
    )

    val validation = fraction(1L, 3L)
    val test = fraction(1L, 5L)
    val specification =
      TrainValidationTestSpec(
        SplitAmount.Proportion(validation),
        SplitAmount.Proportion(test)
      ) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected fraction spec error: $error")
    val source =
      InMemoryData.unsplit(Vector.range(0, 10), fingerprint("fraction"))
    val result =
      Split.trainValidationTest(source, specification, Seed(8L)) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected fraction split error: $error")
    assertEquals(result.train.size, 5L)
    assertEquals(result.validation.size, 3L)
    assertEquals(result.test.size, 2L)

    val half = fraction(1L, 2L)
    assertEquals(
      TrainValidationTestSpec(
        SplitAmount.Proportion(half),
        SplitAmount.Proportion(half)
      ),
      Left(DataError.InvalidThreeWayFractionSum(half, half))
    )

    val enormous = rows(Long.MaxValue)
    val enormousSpecification =
      TrainValidationTestSpec(
        SplitAmount.Count(enormous),
        SplitAmount.Count(enormous)
      ) match
        case Right(value) => value
        case Left(error) =>
          fail(s"unexpected large-row specification error: $error")
    assertEquals(
      Split.trainValidationTest(
        source,
        enormousSpecification,
        Seed(8L)
      ),
      Left(
        DataError.ExhaustiveSplit(
          10L,
          enormousSpecification.policy
        )
      )
    )
  }

  test("RankV1 rejects malformed text before duplicate source RowIds") {
    val unpaired = new String(Array(0xd800.toChar))
    val malformed =
      new DataFingerprint(
        FingerprintPolicy.ContentDigest(unpaired),
        "abc"
      )
    val duplicate = new InMemoryData[Use.Unsplit, Int](
      Vector(RowId(1L) -> 1, RowId(1L) -> 2),
      malformed
    )
    assertEquals(
      Split.holdout(duplicate, HoldoutSpec(rows(1L)), Seed(0L)),
      Left(
        DataError.InvalidRankText(
          RankTextField.ContentDigestAlgorithm,
          RankTextError.UnpairedSurrogate(0)
        )
      )
    )

    val validDuplicate = new InMemoryData[Use.Unsplit, Int](
      Vector(RowId(1L) -> 1, RowId(1L) -> 2),
      fingerprint("duplicate")
    )
    assertEquals(
      Split.holdout(
        validDuplicate,
        HoldoutSpec(rows(1L)),
        Seed(0L)
      ),
      Left(DataError.DuplicateSourceRow(RowId(1L)))
    )
  }

  test("split policy fingerprint changes even when membership does not") {
    val source =
      InMemoryData.unsplit(Vector.range(0, 10), fingerprint("policy"))
    val byRows =
      Split.holdout(source, HoldoutSpec(rows(1L)), Seed(11L)) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected row split error: $error")
    val byFraction =
      Split.holdout(
        source,
        HoldoutSpec(fraction(1L, 10L)),
        Seed(11L)
      ) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected fraction split error: $error")
    assertEquals(
      rowsOf(byRows.test.data),
      rowsOf(byFraction.test.data)
    )
    assertNotEquals(
      byRows.receipt.policy.digest,
      byFraction.receipt.policy.digest
    )
  }

  test("RankV1 split laws hold over generated sizes, counts, and seeds") {
    val property = Prop.forAll(
      Gen.choose(2, 80),
      Gen.choose(1, 1000),
      Gen.choose(Long.MinValue, Long.MaxValue)
    ) { (rowCount, selector, rawSeed) =>
      val heldOut = 1 + selector % (rowCount - 1)
      val source =
        InMemoryData.unsplit(
          Vector.range(0, rowCount),
          fingerprint(s"split-$rowCount")
        )
      Split.holdout(
        source,
        HoldoutSpec(rows(heldOut.toLong)),
        Seed(rawSeed)
      ) match
        case Left(_) => false
        case Right(result) =>
          val trainIds = rowsOf(result.train.data).map(_._1)
          val testIds = rowsOf(result.test.data).map(_._1)
          trainIds == trainIds.sorted &&
          testIds == testIds.sorted &&
          trainIds.toSet.intersect(testIds.toSet).isEmpty &&
          (trainIds ++ testIds).sorted ==
            Vector.range(0, rowCount).map(_.toLong) &&
          result.receipt.partitions.map(_.count).sum == rowCount.toLong
    }
    val result = Test.check(
      Test.Parameters.default.withMinSuccessfulTests(100),
      property
    )
    assert(result.passed, result.toString)
  }

  test("split roles and receipts cannot be forged or retagged") {
    val resultErrors = typeCheckErrors(
      """package consumer
import alder.data.*
import alder.kernel.*
def illegal[A](
  train: NonEmptyData[Use.Train, A],
  validation: NonEmptyData[Use.Validation, A],
  receipt: SplitReceipt
) =
  new ValidationSplit(train, validation, receipt)
"""
    )
    val receiptErrors = typeCheckErrors(
      """package consumer
import alder.data.*
import alder.kernel.*
val forged = new SplitReceipt(
  DataFingerprint.external("source"),
  new ProtocolFingerprint(
    FingerprintPolicy.Summary("policy"),
    "digest"
  ),
  Seed(0L),
  Vector.empty,
  SplitAlgorithm.RankV1
)
"""
    )
    val retagErrors = typeCheckErrors(
      """package consumer
import alder.kernel.*
def illegal[A](
  data: Data[Use.Unsplit, A]
): Data[Use.Train, A] = data
"""
    )
    assert(resultErrors.nonEmpty)
    assert(receiptErrors.nonEmpty)
    assert(retagErrors.nonEmpty)
  }

  test("KFold assessment partitions cover each row exactly once") {
    val data = train(Vector.range(0, 10))
    val kfold = KFold[Int](3) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected KFold config error: $error")
    val plan = planOf(kfold, data, Seed(11L))

    assertEquals(plan.foldCount, 3)
    val assessments = plan.folds.flatMap(fold => rowsOf(fold.assessment.data))
    assertEquals(assessments.map(_._1).sorted, Vector.range(0, 10).map(_.toLong))
    assertEquals(assessments.map(_._1).distinct.length, 10)
    plan.folds.foreach { fold =>
      val analysisIds = rowsOf(fold.analysis.data).map(_._1).toSet
      val assessmentIds = rowsOf(fold.assessment.data).map(_._1).toSet
      assertEquals(analysisIds.intersect(assessmentIds), Set.empty[Long])
      assertEquals(analysisIds.size + assessmentIds.size, 10)
    }
    val sizes = plan.folds.map(_.assessmentSize)
    val bounds = sizes.foldLeft((Long.MaxValue, Long.MinValue)) {
      case ((minimum, maximum), size) =>
        (math.min(minimum, size), math.max(maximum, size))
    }
    assert(bounds._2 - bounds._1 <= 1L)
  }

  test("KFold assignment is seed deterministic and policy tagged") {
    val data = train(Vector.range(0, 12))
    val kfold = KFold[Int](4) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected KFold config error: $error")
    val first = planOf(kfold, data, Seed(99L))
    val replay = planOf(kfold, data, Seed(99L))
    val other = planOf(kfold, data, Seed(100L))

    assertEquals(first.assignment.digest, replay.assignment.digest)
    assertNotEquals(first.assignment.digest, other.assignment.digest)
    first.assignment.policy match
      case FingerprintPolicy.ContentDigest(algorithm) =>
        assertEquals(algorithm, "fnv1a64")
      case policy => fail(s"expected content digest policy, got $policy")
  }

  test("KFold rejects invalid and over-large fold counts") {
    assertEquals(KFold[Int](1), Left(DataError.InvalidFoldCount(1)))
    val data = train(Vector(1, 2))
    val configured = KFold[Int](3) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected KFold config error: $error")
    assertEquals(
      configured.split(data, Seed(0L)),
      Left(DataError.TooManyFolds(3, 2L))
    )
  }

  test("complete resampling rejects duplicate logical RowIds") {
    val duplicateRows = Vector(
      (RowId(0L), 10),
      (RowId(0L), 20)
    )
    val data = new NonEmptyData(
      new InMemoryData[Use.Train, Int](
        duplicateRows,
        fingerprint("duplicate-ids")
      )
    )
    val kfold = KFold[Int](2, shuffle = false) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected KFold config error: $error")
    assertEquals(
      kfold.split(data, Seed(0L)),
      Left(DataError.InvalidResamplingAssignment)
    )
  }

  test("KFold invariants hold over generated sizes, folds, and seeds") {
    val property = Prop.forAll(
      Gen.choose(2, 80),
      Gen.choose(0, 1000),
      Gen.choose(Long.MinValue, Long.MaxValue)
    ) { (rowCount, selector, rawSeed) =>
      val foldCount = 2 + selector % (rowCount - 1)
      val data = train(Vector.range(0, rowCount))
      val result =
        KFold[Int](foldCount).flatMap(_.split(data, Seed(rawSeed)))
      result match
        case Left(_) => false
        case Right(plan) =>
          val assessments =
            plan.folds.flatMap(fold => rowsOf(fold.assessment.data))
          val coverage =
            assessments.map(_._1).sorted ==
              Vector.range(0, rowCount).map(_.toLong)
          val unique = assessments.map(_._1).distinct.length == rowCount
          val complements = plan.folds.forall { fold =>
            val analysis = rowsOf(fold.analysis.data).map(_._1).toSet
            val assessment = rowsOf(fold.assessment.data).map(_._1).toSet
            analysis.intersect(assessment).isEmpty &&
            analysis.size + assessment.size == rowCount
          }
          coverage && unique && complements
    }
    val result = Test.check(
      Test.Parameters.default.withMinSuccessfulTests(100),
      property
    )
    assert(result.passed, result.toString)
  }

  test("grouped KFold never splits a group and remains complete") {
    val examples = Vector.tabulate(12) { index =>
      Example(index, index % 2, TestMeta(group = index / 3, time = index))
    }
    val data = train(examples)
    val grouped = GroupedKFold[Int, Int, TestMeta](3) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected grouped config error: $error")
    val plan = planOf(grouped, data, Seed(17L))
    val groupFolds = plan.folds.flatMap { fold =>
      rowsOf(fold.assessment.data).map(row => row._2.meta.group -> fold.index)
    }
    val byGroup = groupFolds.groupMap(_._1)(_._2)
    assert(byGroup.values.forall(_.distinct.length == 1))
    assertEquals(groupFolds.length, examples.length)
  }

  test("grouped KFold rejects fewer groups than folds") {
    val examples = Vector.tabulate(6) { index =>
      Example(index, index, TestMeta(group = index / 3, time = index))
    }
    val data = train(examples)
    val grouped = GroupedKFold[Int, Int, TestMeta](3) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected grouped config error: $error")
    assertEquals(
      grouped.split(data, Seed(0L)),
      Left(DataError.TooFewGroups(3, 2))
    )
  }

  test("rolling origin is time ordered and deliberately incomplete") {
    val examples = Vector(
      Example(0, 0, TestMeta(0, 30)),
      Example(1, 1, TestMeta(1, 10)),
      Example(2, 2, TestMeta(2, 50)),
      Example(3, 3, TestMeta(3, 20)),
      Example(4, 4, TestMeta(4, 40)),
      Example(5, 5, TestMeta(5, 60))
    )
    val data = train(examples)
    val rolling: Resampler[Example[Int, Int, TestMeta]] =
      RollingOrigin[Int, Int, TestMeta](2, 2, 2) match
        case Right(value) => value
        case Left(error)  => fail(s"unexpected rolling config error: $error")
    val plan = planOf(rolling, data, Seed(5L))

    assertEquals(plan.foldCount, 2)
    plan.folds.foreach { fold =>
      val analysisTimes = rowsOf(fold.analysis.data).map(_._2.meta.time)
      val assessmentTimes = rowsOf(fold.assessment.data).map(_._2.meta.time)
      val latestAnalysis = analysisTimes.foldLeft(Int.MinValue)(math.max)
      val earliestAssessment =
        assessmentTimes.foldLeft(Int.MaxValue)(math.min)
      assert(latestAnalysis < earliestAssessment)
    }
    val assessedTimes =
      plan.folds.flatMap(fold =>
        rowsOf(fold.assessment.data).map(_._2.meta.time)
      )
    assertEquals(assessedTimes.sorted, Vector(30, 40, 50, 60))
  }

  test("rolling origin rejects invalid windows and absent assessment periods") {
    assertEquals(
      RollingOrigin[Int, Int, TestMeta](0, 1, 1),
      Left(DataError.InvalidRollingWindow(0, 1, 1))
    )
    val one = train(Vector(Example(1, 1, TestMeta(1, 10))))
    val configured = RollingOrigin[Int, Int, TestMeta](1, 1, 1) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected rolling config error: $error")
    assertEquals(
      configured.split(one, Seed(0L)),
      Left(DataError.NoRollingFolds(1L, 1))
    )
  }

  test("minimum valid KFold keeps analysis and assessment nonempty") {
    val data = train(Vector(10, 20))
    val kfold = KFold[Int](2, shuffle = false) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected KFold config error: $error")
    val plan = planOf(kfold, data, Seed(0L))
    assert(plan.folds.forall(_.analysisSize == 1L))
    assert(plan.folds.forall(_.assessmentSize == 1L))
  }

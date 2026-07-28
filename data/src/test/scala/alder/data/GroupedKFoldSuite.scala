package alder.data

import alder.kernel.*
import cats.kernel.Hash
import munit.FunSuite

class GroupedKFoldSuite extends FunSuite:
  private final case class Meta(group: String)
  private given GroupOf[Meta] with
    type Key = String
    def apply(meta: Meta): String = meta.group
  private given Hash[String] = Hash.fromUniversalHashCode

  private def fingerprint(label: String): DataFingerprint =
    new DataFingerprint(FingerprintPolicy.Summary("test"), label)

  private def train[A](values: Vector[A]): NonEmptyData[Use.Train, A] =
    val rows = values.zipWithIndex.map { (value, index) =>
      (RowId(index.toLong), value)
    }
    DataRows.nonEmpty[Use.Train, A](rows, fingerprint("train")) match
      case Right(data) => data
      case Left(error) => fail(s"unexpected fixture error: $error")

  private def assignment(
      plan: ResamplingPlan[Use.Train, Example[Int, Int, Meta]]
  ): Map[Long, Int] =
    plan.folds.flatMap { fold =>
      fold.assessment.data.foldRows(Vector.empty[(Long, Int)]) {
        (rows, id, _) =>
          rows :+ (id.value -> fold.index)
      }
    }.toMap

  test("same seed reproduces fold assignment; different seeds can differ") {
    val examples = Vector(
      Example(0, 0, Meta("aa")),
      Example(1, 1, Meta("bb")),
      Example(2, 2, Meta("aa")),
      Example(3, 3, Meta("cc")),
      Example(4, 4, Meta("bb")),
      Example(5, 5, Meta("dd")),
      Example(6, 6, Meta("cc")),
      Example(7, 7, Meta("ee")),
      Example(8, 8, Meta("dd")),
      // Hash-collision-prone short strings under universal hashing.
      Example(9, 9, Meta("Aa")),
      Example(10, 10, Meta("BB")),
      Example(11, 11, Meta("Aa"))
    )
    val data = train(examples)
    val grouped = GroupedKFold[Int, Int, Meta](3) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected grouped config: $error")

    val first = grouped.split(data, Seed(17L)) match
      case Right(plan) => plan
      case Left(error) => fail(s"unexpected split: $error")
    val second = grouped.split(data, Seed(17L)) match
      case Right(plan) => plan
      case Left(error) => fail(s"unexpected split: $error")
    val other = grouped.split(data, Seed(99L)) match
      case Right(plan) => plan
      case Left(error) => fail(s"unexpected split: $error")

    assertEquals(assignment(first), assignment(second))
    assertEquals(first.assignment.digest, second.assignment.digest)
    // Groups remain atomic under both seeds.
    List(first, other).foreach { plan =>
      val groupFolds = plan.folds.flatMap { fold =>
        fold.assessment.data.foldRows(Vector.empty[(String, Int)]) {
          (rows, _, example) =>
            rows :+ (example.meta.group -> fold.index)
        }
      }
      val byGroup = groupFolds.groupMap(_._1)(_._2)
      assert(byGroup.values.forall(_.distinct.length == 1))
    }
  }

  test("large group counts preserve completeness and group atomicity") {
    val examples = Vector.tabulate(200) { index =>
      Example(index, index, Meta(s"g-${index % 50}"))
    }
    val data = train(examples)
    val grouped = GroupedKFold[Int, Int, Meta](5) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected grouped config: $error")
    val plan = grouped.split(data, Seed(3L)) match
      case Right(value) => value
      case Left(error)  => fail(s"unexpected split: $error")
    val assessed =
      plan.folds.flatMap { fold =>
        fold.assessment.data.foldRows(Vector.empty[Long])((rows, id, _) =>
          rows :+ id.value
        )
      }.sorted
    assertEquals(assessed, Vector.range(0L, 200L))
    val byGroup = plan.folds
      .flatMap { fold =>
        fold.assessment.data.foldRows(Vector.empty[(String, Int)]) {
          (rows, _, example) =>
            rows :+ (example.meta.group -> fold.index)
        }
      }
      .groupMap(_._1)(_._2)
    assert(byGroup.values.forall(_.distinct.length == 1))
    assertEquals(byGroup.size, 50)
  }

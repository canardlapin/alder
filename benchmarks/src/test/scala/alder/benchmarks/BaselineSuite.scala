package alder.benchmarks

import alder.data.*
import alder.kernel.*
import alder.quickstart.*
import alder.testkit.TestData
import cats.kernel.Hash
import munit.FunSuite

/** Committed local baselines for 100k-row workloads.
  *
  * Run with:
  *
  * {{{
  * sbt -J-Xmx4G -Dsbt.task.cpus=1 benchmarks/test
  * }}}
  *
  * Thresholds are deliberately loose wall-clock budgets that catch quadratic
  * regressions without claiming absolute machine-independent timings.
  */
class BaselineSuite extends FunSuite:
  private final case class GroupedMeta(group: Int)

  private given GroupOf[GroupedMeta] with
    type Key = Int
    def apply(meta: GroupedMeta): Int = meta.group

  private given Hash[Int] = Hash.fromUniversalHashCode

  private final case class Point(x: Double, y: Double)
      derives Coordinates,
        Schema

  private def millis(body: => Unit): Double =
    // One warm-up pass, then measure.
    body
    val start = System.nanoTime()
    body
    (System.nanoTime() - start).toDouble / 1.0e6

  test("GroupedKFold stays near-linear for 100k rows and many groups") {
    val rows =
      Vector.tabulate(100000) { index =>
        Example(index.toDouble, index.toDouble, GroupedMeta(index))
      }
    val data =
      TestData.indexed[Use.Train, Example[Double, Double, GroupedMeta]](
        rows,
        DataFingerprint.external("bench-grouped")
      ) match
        case Some(value) => value
        case None        => fail("expected data")
    val resampler = GroupedKFold[Double, Double, GroupedMeta](10) match
      case Left(error)  => fail(s"grouped config: $error")
      case Right(value) => value
    val elapsed = millis {
      resampler.split(data, Seed(11L)) match
        case Left(error) => fail(s"split failed: $error")
        case Right(plan) => assertEquals(plan.foldCount, 10)
    }
    // Quadratic-in-group O(n·g) historically exceeded tens of seconds here.
    assert(elapsed < 15000.0, s"GroupedKFold took ${elapsed}ms")
  }

  test("standardize-fit-predict-score budget on 100k rows") {
    val pairs =
      Vector.tabulate(100000) { index =>
        val x = index.toDouble / 1000.0
        Point(x, x * 0.5) -> (2.0 * x + 1.0)
      }
    val data = Supervised.fromPairs(pairs, "bench-ridge")
    val elapsed = millis {
      val result =
        for
          scaler <- Standardize.emitZero[Point]
          ridge <- Ridge.lsqr[Point](0.1)
          blueprint =
            Blueprint.supervised[Point, Double].via(scaler).learn(ridge)
          specification <- Validation.rows(1000L)
          validated <- Experiment
            .validation(
              data,
              specification,
              Seed(3L),
              "bench-ridge-v1",
              blueprint,
              Metrics.rmse
            )
            .run
        yield validated
      result match
        case Left(error) => fail(s"workflow failed: $error")
        case Right(validated) =>
          assertEquals(validated.predictions.size, 1000L)
          assert(validated.score.value.isFinite)
    }
    assert(elapsed < 60000.0, s"100k workflow took ${elapsed}ms")
  }

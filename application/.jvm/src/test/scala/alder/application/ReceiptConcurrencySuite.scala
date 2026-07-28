package alder.application

import alder.data.*
import alder.kernel.*
import alder.metrics.*
import cats.Id
import cats.data.EitherT
import java.util.concurrent.CountDownLatch
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

class ReceiptConcurrencySuite extends munit.FunSuite:
  private given ExecutionContext = ExecutionContext.global

  test("concurrent selection-receipt consumers admit exactly one refit") {
    val observations =
      Vector.tabulate(6) { index =>
        val value = index.toDouble
        Example(value, value, ())
      }
    val source =
      InMemoryData.unsplit(
        observations,
        new DataFingerprint(
          FingerprintPolicy.ContentDigest("sha256"),
          "concurrency-source"
        )
      )
    val specification =
      ValidationSpec.rows(2L) match
        case Left(error)  => fail(s"unexpected specification error: $error")
        case Right(value) => value
    val split =
      Split.validation(source, specification, Seed(3L)) match
        case Left(error)  => fail(s"unexpected split error: $error")
        case Right(value) => value
    val context =
      FitContext.root(
        Seed(4L),
        PlanFingerprint("receipt-concurrency"),
        SchemaFingerprint("double"),
        NumericMode.Deterministic
      )
    val component =
      ComponentDescriptor(
        ComponentId("alder.test.identity"),
        ComponentVersion("1"),
        AuditValue.record(),
        BackendFingerprint("test", "1", AuditValue.record())
      )
    val learner =
      new Learner[Id, Double, Double, Unit, Double]:
        type FitError = Nothing
        type RunError = Nothing
        type Model = Pipe[Double, Nothing, Double]
        def fit[U <: Use.Fit](
            data: NonEmptyData[U, Example[Double, Double, Unit]]
        )(using fitContext: FitContext)
            : FitResult[Id, FitError, Trained[Model]] =
          EitherT.right(
            fitContext.complete(Pipe.identity[Double], data, component)
          )
    val trained =
      learner.fit(split.train)(using context).value match
        case Left(error)  => fail(s"unexpected fit error: $error")
        case Right(value) => value
    val sources =
      EvaluationSources
        .validation(split.train, split.validation.data) match
        case Left(error)  => fail(s"unexpected source error: $error")
        case Right(value) => value
    val evaluated =
      Evaluation
        .validated(
          learner,
          trained,
          sources,
          RegressionMetrics.rmse[Unit]
        )
        .fold(
          error => fail(s"unexpected evaluation error: $error"),
          identity
        )
    val selection = evaluated.select(SingleCandidate)
    val ready = new CountDownLatch(2)
    val start = new CountDownLatch(1)

    def attempt() =
      Future {
        ready.countDown()
        start.await()
        Refit.after(selection).from(evaluated.evaluation.allObserved)
      }

    val first = attempt()
    val second = attempt()
    ready.await()
    start.countDown()
    val results =
      Await.result(Future.sequence(Vector(first, second)), 10.seconds)

    assertEquals(results.count(_.isRight), 1)
    assertEquals(
      results.count {
        case Left(
              ApplicationRefitError.SelectionReceiptAlreadyUsed(id)
            ) =>
          id == selection.id
        case _ => false
      },
      1
    )
  }

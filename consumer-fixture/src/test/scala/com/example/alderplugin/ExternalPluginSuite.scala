package com.example.alderplugin

import alder.kernel.*
import alder.testkit.TestData
import cats.Id
import scala.compiletime.testing.typeCheckErrors

class ExternalPluginSuite extends munit.FunSuite:
  private val context =
    FitContext.root(
      Seed(3L),
      PlanFingerprint("consumer-fixture"),
      SchemaFingerprint("double"),
      NumericMode.Deterministic
    )

  private def trainDoubles(
      values: Double*
  ): NonEmptyData[Use.Train, Double] =
    TestData
      .indexed(
        values.toVector,
        new DataFingerprint(
          FingerprintPolicy.Summary("consumer-fixture"),
          "doubles"
        )
      )
      .get

  private def trainExamples(
      values: Vector[Example[Double, Double, Unit]]
  ): NonEmptyData[Use.Train, Example[Double, Double, Unit]] =
    TestData
      .indexed(
        values,
        new DataFingerprint(
          FingerprintPolicy.Summary("consumer-fixture"),
          "examples"
        )
      )
      .get

  test("external Transform.Leaf fits through completeTransform") {
    val data = trainDoubles(1.0, 2.0, 3.0)
    val prepared =
      new AddConstant[Id](1.5).fit(data)(using context).value match
        case Left(error)  => fail(s"unexpected fit error: $error")
        case Right(value) => value
    assertEquals(prepared.artifact.run(10.0), Right(11.5))
    assertEquals(
      prepared.fitted.audit.component.id.render,
      "com.example.add-constant"
    )
  }

  test("external Learner fits through FitContext.complete") {
    val data =
      trainExamples(
        Vector(
          Example(1.0, 2.0, ()),
          Example(2.0, 4.0, ()),
          Example(3.0, 6.0, ())
        )
      )
    val trained =
      new MeanLearner[Id]().fit(data)(using context).value match
        case Left(error)  => fail(s"unexpected fit error: $error")
        case Right(value) => value
    assertEquals(trained.artifact.run(0.0), Right(4.0))
    assertEquals(
      trained.audit.component.id.render,
      "com.example.mean-learner"
    )
  }

  test("external Transform.learnWith(Learner) fits and retains example component identity") {
    val data =
      trainExamples(
        Vector(
          Example(1.0, 2.0, ()),
          Example(2.0, 4.0, ()),
          Example(3.0, 6.0, ())
        )
      )
    val composed =
      new AddConstant[Id](1.0).learnWith(new MeanLearner[Id]())
    val trained =
      composed.fit(data)(using context).value match
        case Left(error)  => fail(s"unexpected composition fit error: $error")
        case Right(value) => value
    assertEquals(trained.artifact.run(10.0), Right(4.0))
    assertEquals(trained.audit.children.length, 2)
    assertEquals(
      trained.audit.children.map(_.component.id.render),
      Vector("com.example.add-constant", "com.example.mean-learner")
    )
  }

  test("external packages cannot construct Prepared or read protocol rows") {
    val replayErrors = typeCheckErrors(
      """package com.example.alderplugin
import alder.kernel.*
def illegal[U <: Use.Fit, E, X, Z, P <: Pipe[X, E, Z]](
  fitted: Trained[P],
  data: NonEmptyData[U, X],
  lineage: PreparationLineage
) =
  Prepared.replayed(fitted, data, lineage)
"""
    )
    val rowsErrors = typeCheckErrors(
      """package com.example.alderplugin
import alder.kernel.*
def illegal[S <: Preparation, U <: Use.Fit, A, B](
  prepared: Prepared[S, U, A, B]
) = prepared.rows
"""
    )
    assert(replayErrors.nonEmpty)
    assert(rowsErrors.nonEmpty)
  }

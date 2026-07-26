package alder.laws

import scala.compiletime.testing.typeCheckErrors

class CompileNegativeSuite extends munit.FunSuite:

  test("FeatureMap cannot feed another Transform") {
    val errors = typeCheckErrors(
      """import alder.kernel.*
import cats.Id
def illegal(
  feature: FeatureMap[Id, Double, Double, Unit, Double],
  transform: Transform[Id, Double, Double]
): Unit =
  val _ = feature.andThen(transform)
"""
    )
    assert(errors.nonEmpty)
  }

  test("FeatureMap cannot feed another FeatureMap") {
    val errors = typeCheckErrors(
      """import alder.kernel.*
import cats.Id
def illegal(
  first: FeatureMap[Id, Double, Double, Unit, Double],
  second: FeatureMap[Id, Double, Double, Unit, Double]
): Unit =
  val _ = first.andThen(second)
"""
    )
    assert(errors.nonEmpty)
  }

  test("Learner cannot feed another Learner") {
    val errors = typeCheckErrors(
      """import alder.kernel.*
import cats.Id
def illegal(
  first: Learner[Id, Double, Double, Unit, Double],
  second: Learner[Id, Double, Double, Unit, Double]
): Unit =
  val _ = first.learnWith(second)
"""
    )
    assert(errors.nonEmpty)
  }

  test("FeatureMap rowwise postprocessing does not accept a fitted Pipe") {
    val errors = typeCheckErrors(
      """import alder.kernel.*
import cats.Id
def illegal(
  feature: FeatureMap[Id, Double, Double, Unit, Double],
  fitted: Pipe[Double, String, Double]
): Unit =
  val _ = feature.mapOutput(fitted)
"""
    )
    assert(errors.nonEmpty)
  }

  test("Transform fitting rejects evaluation-only data") {
    val errors = typeCheckErrors(
      """import alder.kernel.*
import cats.Id
def illegal(
  transform: Transform[Id, Double, Double],
  data: NonEmptyData[Use.Test, Double]
)(using FitContext): Unit =
  val _ = transform.fit(data)
"""
    )
    assert(errors.nonEmpty)
  }

  test("Learner fitting rejects evaluation-only data") {
    val errors = typeCheckErrors(
      """import alder.kernel.*
import cats.Id
def illegal(
  learner: Learner[Id, Double, Double, Unit, Double],
  data: NonEmptyData[Use.Test, Example[Double, Double, Unit]]
)(using FitContext): Unit =
  val _ = learner.fit(data)
"""
    )
    assert(errors.nonEmpty)
  }

  test("consumer code cannot extract Prepared protocol rows") {
    val errors = typeCheckErrors(
      """package consumer
import alder.kernel.*
def illegal[
  S <: Preparation,
  U <: Use.Fit,
  A,
  B
](prepared: Prepared[S, U, A, B]): Unit =
  val _ = prepared.rows
"""
    )
    assert(errors.nonEmpty)
  }

  test("consumer code cannot forge NonEmptyData") {
    val errors = typeCheckErrors(
      """package consumer
import alder.kernel.*
def illegal(data: Data[Use.Train, Double]): NonEmptyData[Use.Train, Double] =
  new NonEmptyData(data)
"""
    )
    assert(errors.nonEmpty)
  }

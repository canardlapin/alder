package alder.tune.laws

import alder.kernel.*
import alder.laws.AuditSnapshot
import alder.tune.*
import cats.kernel.Eq
import org.scalacheck.Prop
import org.typelevel.discipline.Laws

trait SpaceLaws[A]:
  def space: Space[A]
  def gridStrategy: GridStrategy
  def trials: PositiveInt
  def seed: Seed
  def valid(value: A): Boolean

final class SpaceTests[A](
    laws: SpaceLaws[A]
) extends Laws:
  def all(using Eq[A]): RuleSet =
    new DefaultRuleSet(
      "space",
      None,
      "grid emits only complete valid values" ->
        Prop {
          val values =
            Grid.candidates(laws.space, laws.gridStrategy)
          values.nonEmpty && values.forall(laws.valid)
        },
      "random emits only complete valid values" ->
        Prop {
          val values =
            RandomSearch.candidates(
              laws.space,
              laws.trials,
              laws.seed
            )
          values.nonEmpty && values.forall(laws.valid)
        },
      "equal strategy and seed produce equal ordered candidates" ->
        Prop {
          val first =
            RandomSearch.candidates(
              laws.space,
              laws.trials,
              laws.seed
            )
          val second =
            RandomSearch.candidates(
              laws.space,
              laws.trials,
              laws.seed
            )
          first.length == second.length &&
          first.zip(second).forall((left, right) =>
            summon[Eq[A]].eqv(left, right)
          )
        }
    )

trait TuningErasureLaws[X, FitE, RunE, P]:
  def concrete: Either[Failure[FitE], Trained[? <: Pipe[X, RunE, P]]]
  def erased: Either[
    Failure[FitE],
    Trained[Pipe[X, RunE, P]]
  ]
  def inputs: Vector[X]

final class TuningErasureTests[X, FitE, RunE, P](
    laws: TuningErasureLaws[X, FitE, RunE, P]
) extends Laws:
  def all(using
      Eq[Either[Failure[RunE], P]],
      Eq[Failure[FitE]]
  ): RuleSet =
    new DefaultRuleSet(
      "tuningErasure",
      None,
      "eraseModel preserves fit audit and prediction behavior" ->
        Prop {
          (laws.concrete, laws.erased) match
            case (Right(concrete), Right(erased)) =>
              AuditSnapshot.equivalent(
                AuditSnapshot(concrete.audit),
                AuditSnapshot(erased.audit)
              ) &&
              laws.inputs.forall(input =>
                summon[Eq[Either[Failure[RunE], P]]].eqv(
                  concrete.artifact.run(input),
                  erased.artifact.run(input)
                )
              )
            case (Left(concrete), Left(erased)) =>
              summon[Eq[Failure[FitE]]].eqv(concrete, erased)
            case _ => false
        }
    )

trait StudyLaws[C, E]:
  def selection: Either[StudyError[E], Selection[C, E]]
  def candidates: Vector[C]

final class StudyTests[C, E](
    laws: StudyLaws[C, E]
) extends Laws:
  def all(using Eq[C]): RuleSet =
    new DefaultRuleSet(
      "study",
      None,
      "selection returns a candidate configuration with honest counts" ->
        Prop {
          laws.selection match
            case Left(_) => false
            case Right(selection) =>
              laws.candidates.exists(candidate =>
                summon[Eq[C]].eqv(candidate, selection.best)
              ) &&
              selection.audit.candidateCount ==
                selection.trials.length &&
              selection.audit.successfulTrials ==
                selection.trials.count(_.objective.isRight)
        }
    )

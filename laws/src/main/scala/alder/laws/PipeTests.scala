package alder.laws

import alder.kernel.*
import cats.kernel.Eq
import cats.syntax.eq.*
import org.scalacheck.{Arbitrary, Prop}
import org.typelevel.discipline.Laws

/** Pipe laws for a single pipe: identity and error-map identity. Observational
  * equality is over `run` results, which includes the failing stage path.
  */
final class PipeTests[A, E, B](pipe: Pipe[A, E, B]) extends Laws:

  def all(using Arbitrary[A], Eq[Either[Failure[E], B]]): RuleSet =
    new DefaultRuleSet(
      "pipe",
      None,
      "identity andThen p observationally equals p" -> Prop.forAll { (a: A) =>
        val composed: Either[Failure[E], B] =
          Pipe.identity[A].andThen(pipe).run(a)
        composed === pipe.run(a)
      },
      "p andThen identity observationally equals p" -> Prop.forAll { (a: A) =>
        val composed: Either[Failure[E], B] =
          pipe.andThen(Pipe.identity[B]).run(a)
        composed === pipe.run(a)
      },
      "mapError(identity) observationally equals p" -> Prop.forAll { (a: A) =>
        pipe.mapError((e: E) => e).run(a) === pipe.run(a)
      }
    )

/** Associativity across three composed pipes: successful output, first failing
  * stage, and stage path are all parenthesization-invariant.
  */
final class PipeCompositionTests[A, E1, B, E2, C, E3, D](
    p: Pipe[A, E1, B],
    q: Pipe[B, E2, C],
    r: Pipe[C, E3, D]
) extends Laws:

  def all(using Arbitrary[A], Eq[Either[Failure[E1 | E2 | E3], D]]): RuleSet =
    new DefaultRuleSet(
      "pipe.composition",
      None,
      "associativity" -> Prop.forAll { (a: A) =>
        val leftNested: Either[Failure[E1 | E2 | E3], D] =
          p.andThen(q).andThen(r).run(a)
        val rightNested: Either[Failure[E1 | E2 | E3], D] =
          p.andThen(q.andThen(r)).run(a)
        leftNested === rightNested
      }
    )

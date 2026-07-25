package alder.laws

import alder.kernel.*
import cats.kernel.Eq
import munit.DisciplineSuite

enum SqrtError derives CanEqual:
  case Negative

class PipeLawsSuite extends DisciplineSuite:

  private val stage = StagePath.root.child(0)

  private val sqrt: Pipe[Double, SqrtError, Double] =
    new Pipe[Double, SqrtError, Double]:
      def run(value: Double): Either[Failure[SqrtError], Double] =
        if value >= 0.0 then Right(math.sqrt(value))
        else Left(stage.failure(SqrtError.Negative))

  private val double: Pipe[Double, Nothing, Double] =
    Pipe.total(value => value * 2.0)

  // Nothing | SqrtError | Nothing normalizes to SqrtError, so this single
  // instance also serves the composition rule set.
  private given Eq[Either[Failure[SqrtError], Double]] =
    Eq.fromUniversalEquals

  checkAll("Pipe[Double, SqrtError, Double]", PipeTests(sqrt).all)

  checkAll(
    "Pipe composition associativity",
    PipeCompositionTests(double, sqrt, double).all
  )

# Build deterministic tuning spaces

`Space[A]` describes valid configurations. It is applicative rather than
monadic: every parameter domain is fixed before search begins, so grid and
random interpreters can audit the same product structure.

## Define a configuration space

```scala mdoc
import alder.kernel.Seed
import alder.tune.*
import cats.syntax.apply.*

final case class Config(
  penalty: PositiveDouble,
  iterations: PositiveInt
)

val configSpace =
  for
    penalties <- Space.logUniform(1.0e-4, 1.0e2)
    iterations <- Space.intRange(50, 52)
  yield (penalties, iterations).mapN(Config.apply)
```

Grid enumeration is deterministic:

```scala mdoc
val grid =
  configSpace.flatMap(space =>
    Grid.candidates(space, continuousPoints = 4)
  )

grid.map(_.size)
grid.map(_.head.penalty.toDouble)
```

Random search is deterministic for the same seed:

```scala mdoc
val first =
  configSpace.flatMap(space =>
    RandomSearch.candidates(space, trials = 3, seed = Seed(42L))
  )
val second =
  configSpace.flatMap(space =>
    RandomSearch.candidates(space, trials = 3, seed = Seed(42L))
  )

first == second
```

## Keep studies on Train data

A `Study` evaluates configurations only on
`NonEmptyData[Use.Train, A]`. It returns a `Selection[C]`, not fitted models.
After selection, the application constructs the concrete learner family again
and performs any authorized refit.

This does not compile:

```scala mdoc:fail
import alder.kernel.*
import cats.Id

def evaluateTest[C, A](
    study: Study[Id, C, A, String],
    test: NonEmptyData[Use.Test, A]
): Unit =
  val _ = study.run(test)
```

The boundary prevents a study from selecting configurations on held-out final
test data.

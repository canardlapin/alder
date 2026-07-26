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

val minimumPenalty = PositiveDouble.create(1.0e-4).toOption.get
val maximumPenalty = PositiveDouble.create(1.0e2).toOption.get
val minimumIterations = PositiveInt.create(50).toOption.get
val maximumIterations = PositiveInt.create(52).toOption.get
val continuousPoints = PositiveInt.create(4).toOption.get

val configSpace = (
  Space.logUniform(minimumPenalty, maximumPenalty).toOption.get,
  Space.intRange(minimumIterations, maximumIterations).toOption.get
).mapN(Config.apply)
```

Grid enumeration is deterministic:

```scala mdoc
val grid = Grid.candidates(
  configSpace,
  GridStrategy(continuousPoints)
)

grid.size
grid.head.penalty.toDouble
```

Random search is deterministic for the same seed:

```scala mdoc
val trials = PositiveInt.create(3).toOption.get

RandomSearch.candidates(configSpace, trials, Seed(42L)) ==
  RandomSearch.candidates(configSpace, trials, Seed(42L))
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
    study: Study[Id, C, A],
    test: NonEmptyData[Use.Test, A]
): Unit =
  val _ = study.run(test)
```

The boundary prevents a study from selecting configurations on held-out final
test data.

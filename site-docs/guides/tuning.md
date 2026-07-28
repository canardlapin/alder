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

## Cross-validated search over a learner family

`Search.crossValidatedGrid` expands each configuration across a
`CompleteResampler`, scores every assessment fold, discards the fold models, and
selects with the same first-best-tie policy as `Study`. Reconstruct the concrete
learner with `family(result.best)`.

```scala mdoc
import alder.data.{Holdout, KFold}
import alder.kernel.PlanFingerprint
import alder.metrics.RootMeanSquaredError
import alder.models.linear.{RidgeConfig, RidgeRegression}
import alder.quickstart.*
import alder.ridge.linop4s.Linop4sRidgeBackend
import alder.tune.{GridStrategy, PositiveInt, Space}
import cats.Id

final case class Point(x: Double) derives Coordinates, Schema

val points = Supervised.fromPairs(
  Vector.tabulate(12) { index =>
    val x = index.toDouble
    Point(x) -> (2.0 * x + 1.0)
  },
  "point-search-v1"
)

val backend = Linop4sRidgeBackend.lsqr[Id]()
val family: Double => RidgeRegression[Id, Point, Unit] =
  penalty =>
    RidgeConfig.create(penalty) match
      case Right(config) => RidgeRegression.sync[Point, Unit](config, backend)
      case Left(error)   => sys.error(error.toString)

val selected =
  for
    holdout <- Holdout.split(points, testSize = 2, Seed(3L)).left.map(_.toString)
    resampler <- KFold[Example[Point, Double, Unit]](3).left.map(_.toString)
    pointsPerAxis <- PositiveInt.create(1).left.map(_.toString)
    result <- Search
      .crossValidatedGridSync(
        Space.choice(0.01, 0.1, 1.0),
        GridStrategy(pointsPerAxis),
        resampler,
        family,
        Metrics.rmse,
        (score: RootMeanSquaredError) => score.value,
        Seed(5L),
        PlanFingerprint.external("point-search-v1")
      )
      .run(holdout.train)
      .left
      .map(_.toString)
  yield result

selected.map(result => (result.best, result.trials.length))
```

Fold models are never retained in the result. Only per-fold scores, the chosen
configuration, and study audit evidence remain.

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

# Predict and inspect fitted models

A fitted `Trained[A]` always carries its audit. Ordinary prediction uses the
fitted pipe. Algorithm-specific inspection uses capability evidence such as
`Coefficients` or `Explain`. Missing evidence is a compile error, not a runtime
"unsupported" branch.

## Fit a terminal ridge model

`Ridge.lsqr` targets dense standardized features. For direct inspection of a
ridge model on an application type, construct `RidgeRegression` on that type:

```scala mdoc:silent
import alder.data.{Fit, Holdout, InMemoryData}
import alder.kernel.*
import alder.models.linear.{
  RidgeConfig,
  RidgeModel,
  RidgeRegression
}
import alder.models.linear.given
import alder.quickstart.*
import alder.ridge.linop4s.Linop4sRidgeBackend
import cats.Id

final case class Point(x: Double) derives Coordinates, Schema

val points = Supervised.fromPairs(
  Vector(
    Point(1.0) -> 2.0,
    Point(2.0) -> 4.0,
    Point(3.0) -> 6.0,
    Point(4.0) -> 8.0
  ),
  "point-predict-v1"
)

val trained =
  for
    config <- RidgeConfig.create(0.1).left.map(_.toString)
    holdout <- Holdout.split(points, testSize = 1, Seed(7L)).left.map(_.toString)
    learner =
      RidgeRegression.sync[Point, Unit](
        config,
        Linop4sRidgeBackend.lsqr[Id]()
      )
    model <- Fit
      .learner(learner, holdout.train, Seed(11L), "point-ridge-v1")
      .left
      .map(_.toString)
  yield model
```

## Predict one row or many

```scala mdoc
trained.flatMap(_.predict(Point(5.0)).left.map(_.toString))
```

```scala mdoc
val inputs =
  InMemoryData.unsplit(
    Vector(Point(1.0), Point(2.0), Point(5.0)),
    "point-predict-all"
  )

trained.flatMap(_.predictAll(inputs).left.map(_.toString)).map(_.map(_._2))
```

`predictAll` keeps `RowId`s. It does not require pattern-matching on composition
wrappers.

## Inspect coefficients and attributions

Ridge supplies `Coefficients` and `Explain` for `RidgeModel[X]`:

```scala mdoc
trained.map { model =>
  val coefficients = Coefficients[RidgeModel[Point]]
  (
    coefficients.coefficientCount(model),
    coefficients.intercept(model),
    coefficients.coefficients(model).toVector
  )
}
```

```scala mdoc
trained.flatMap { model =>
  val explain = summon[Explain[RidgeModel[Point], Point]]
  explain(model, Point(5.0))
    .left
    .map(_.toString)
    .map(attribution =>
      (attribution.prediction, attribution.contributions.toVector)
    )
}
```

For a composed blueprint such as standardize-then-ridge, capabilities attach to
the terminal ridge artifact once you reach it, not to the outer composition
wrapper. Use `trained.terminal` when you need the exact fitted value without
navigating wrappers by hand.

See [Audit and reproducibility](../advanced/audit-and-reproducibility.md) for
what the accompanying audit records.

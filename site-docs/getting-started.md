# Fit your first workflow

This example fits a standardizer and ridge model, then predicts from the raw
input type. Alder records how the model was fitted and prevents the held-out
test rows from being passed to `fit`.

## Define the input and observations

`Coordinates` gives the model an ordered numeric view of `House`. `Schema`
gives the fit audit a stable description of that input. Scala derives both from
the case class.

```scala mdoc:silent
import alder.data.*
import alder.kernel.*
import alder.models.linear.*
import alder.preprocess.*
import alder.ridge.linop4s.*
import cats.Id
import cats.syntax.all.*

final case class House(
    area: Double,
    bedrooms: Double,
    age: Double
) derives Coordinates, Schema

val observations = Vector(
  Example(House(60.0, 1.0, 40.0), 210.0, ()),
  Example(House(75.0, 2.0, 25.0), 265.0, ()),
  Example(House(90.0, 2.0, 15.0), 315.0, ()),
  Example(House(110.0, 3.0, 10.0), 390.0, ()),
  Example(House(130.0, 4.0, 5.0), 470.0, ())
)

val data = InMemoryData.unsplit(observations, "house-prices-v1")
```

The string is an application-managed data identity, not a content hash. Use
the `DataFingerprint` overload when the application has a stronger fingerprint.

## Build the workflow

The split gives the two partitions different types. Only `split.train` can be
passed to this fit.

```scala mdoc:silent
val split =
  Holdout
    .split(data, testSize = 1, Seed(42L))
    .left
    .map(_.toString)

val config =
  RidgeConfig
    .create(penalty = 0.1)
    .left
    .map(_.toString)

val backend = Linop4sRidgeBackend.lsqr[Id]()

val workflow =
  config.map { validatedConfig =>
    StandardScaler
      .sync[House](ZeroVariance.EmitZero)
      .learnWith(
        RidgeRegression.sync[Standardized[House], Unit](
          validatedConfig,
          backend
        )
      )
  }
```

The synchronous factories are conveniences. The underlying components remain
effect-polymorphic when fitting needs `IO` or another effect.

## Fit and predict

`Fit.learner` derives the schema fingerprint, uses deterministic numerics by
default, and exposes the synchronous result as `Either`. The plan name and seed
remain explicit because they identify this fitting run.

```scala mdoc:silent
val prediction =
  (split, workflow).tupled.flatMap { (partitions, learner) =>
    Fit
      .learner(
        learner,
        partitions.train,
        seed = Seed(42L),
        plan = "house-price-ridge-v1"
      )
      .left
      .map(failure =>
        s"fit failed at ${failure.stage.render}: ${failure.cause}"
      )
      .flatMap(model =>
        model
          .run(House(100.0, 3.0, 12.0))
          .left
          .map(failure =>
            s"prediction failed at ${failure.stage.render}: ${failure.cause}"
          )
      )
  }
```

The result is a typed success or an attributed failure:

```scala mdoc
prediction.map(_.isFinite)
```

Continue with [How Alder prevents leakage](concepts/data-roles.md) when you need
to understand the data roles, or go directly to a task in
[Guides](guides/README.md).

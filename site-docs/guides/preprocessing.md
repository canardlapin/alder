# Fit target-blind preprocessing

This guide fits a `StandardScaler` on typed training data and runs its fitted
pipe on a new value.

## Define numeric coordinates

`Coordinates[A]` describes the complete ordered numeric representation of an
application type. Product derivation supports numeric case-class fields.

```scala mdoc:silent
import alder.data.*
import alder.kernel.*
import alder.preprocess.*

final case class Features(age: Double, score: Double)
    derives Coordinates,
      Schema
```

The coordinate names are stable and ordered:

```scala mdoc
Coordinates[Features].names.toVector
```

## Create and split data

The input starts as `Use.Unsplit`. `Holdout.split` constructs typed training and
test roles without exposing a retagging operation.

```scala mdoc:silent
val unsplit = InMemoryData.unsplit(
  Vector(
    Features(20.0, 1.0),
    Features(30.0, 2.0),
    Features(40.0, 4.0),
    Features(50.0, 8.0)
  ),
  "features-4"
)

val holdout =
  Holdout.split(unsplit, testSize = 1, Seed(17L))
```

## Fit the transform

The plan name and seed identify this fit. `Fit.transform` derives the schema and
uses deterministic numerics by default.

```scala mdoc:silent
import alder.data.Dense

val prepared =
  holdout
    .left
    .map(_.toString)
    .flatMap(partitions =>
      StandardScaler.sync[Features](ZeroVariance.Reject) match
        case Left(error) => Left(error.toString)
        case Right(scaler) =>
          Fit
            .transform(
              scaler,
              partitions.train,
              seed = Seed(101L),
              plan = "standardize-features-v1"
            )
            .left
            .map(_.toString)
    )
```

`prepared.rows` is intentionally unavailable to application code. Alder owns
those rows for safe composition. Application code uses the fitted pipe:

```scala mdoc
val standardized =
  prepared.flatMap(
    _.artifact
      .run(Features(35.0, 3.0))
      .left
      .map(_.toString)
  )

standardized.map((value: Dense[Standardized[Features]]) => value.values.toVector)
```

The audit identifies the fitting data, component, backend, numerical mode, and
stage:

```scala mdoc
prepared.map(_.fitted.audit.component.id.render)
prepared.map(_.fitted.audit.numerics)
prepared.map(_.fitted.audit.preparation.stage)
```

Choose `ScaleOnlyScaler` when structural zeros must remain zero. It exposes a
distinct `Scaled[A]` result brand and has no centering switch.

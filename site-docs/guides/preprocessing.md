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
import cats.Id

final case class Features(age: Double, score: Double)

given Coordinates[Features] = Coordinates.derived
given Schema[Features] = Schema.derived
```

The coordinate names are stable and ordered:

```scala mdoc
Coordinates[Features].names.toVector
```

## Create and split data

The input starts as `Use.Unsplit`. `Holdout.split` constructs typed training and
test roles without exposing a retagging operation.

```scala mdoc:silent
val fingerprint = new DataFingerprint(
  FingerprintPolicy.Summary("guide-input-v1"),
  "features-4"
)

val unsplit = InMemoryData.unsplit(
  Vector(
    Features(20.0, 1.0),
    Features(30.0, 2.0),
    Features(40.0, 4.0),
    Features(50.0, 8.0)
  ),
  fingerprint
)

val holdout = Holdout
  .split(unsplit, testSize = 1, Seed(17L))
  .toOption
  .get
```

## Fit with an explicit context

The root context fixes the plan identity, schema identity, seed, and numerical
mode recorded in the audit.

```scala mdoc:silent
given FitContext = FitContext.root(
  seed = Seed(101L),
  plan = PlanFingerprint("standardize-features-v1"),
  schema = summon[Schema[Features]].fingerprint,
  numericMode = NumericMode.Deterministic
)

val prepared = StandardScaler[Id, Features](ZeroVariance.Reject)
  .fit(holdout.train)
  .value
  .toOption
  .get
```

`prepared.rows` is intentionally unavailable to application code. Alder owns
those rows for safe composition. Application code uses the fitted pipe:

```scala mdoc
val standardized = prepared.fitted.artifact
  .run(Features(35.0, 3.0))
  .toOption
  .get

Coordinates[Standardized[Features]]
  .read(standardized)
  .map(_.toVector)
```

The audit identifies the fitting data, component, backend, numerical mode, and
stage:

```scala mdoc
prepared.fitted.audit.component.id.render
prepared.fitted.audit.numerics
prepared.fitted.audit.preparation.stage
```

Choose `ScaleOnlyScaler` when structural zeros must remain zero. It exposes a
distinct `Scaled[A]` result brand and has no centering switch.

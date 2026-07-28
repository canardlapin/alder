# Fit and validate your first workflow

This example fits a standardizer and ridge model, predicts every validation
row, and computes root mean squared error. Alder records how the model was
fitted and prevents validation rows from being passed to `fit`.

## Define the input and observations

`Coordinates` gives the model an ordered numeric view of `House`. `Schema`
gives the fit audit a stable description of that input. Integer bedrooms are
allowed: standardization emits dense coordinates rather than rebuilding
`House`.

```scala mdoc:silent
import alder.quickstart.*

final case class House(
    area: Double,
    bedrooms: Int,
    age: Double
) derives Coordinates, Schema

val data = Supervised.fromPairs(
  Vector(
    House(60.0, 1, 40.0) -> 210.0,
    House(75.0, 2, 25.0) -> 265.0,
    House(90.0, 2, 15.0) -> 315.0,
    House(110.0, 3, 10.0) -> 390.0,
    House(130.0, 4, 5.0) -> 470.0
  ),
  "house-prices-v1"
)
```

The string is an application-managed data identity, not a content hash.

## Build and run the workflow

One root seed expands into stable, plan-scoped seeds for splitting and fitting.
The experiment stops at a typed validation result: it does not silently select
or refit the candidate.

```scala mdoc:silent
val validated =
  for
    scaler <- Standardize.emitZero[House]
    ridge <- Ridge.lsqr[House](0.1)
    blueprint =
      Blueprint.supervised[House, Double].via(scaler).learn(ridge)
    specification <- Validation.rows(1L)
    result <- Experiment
      .validation(
        data,
        specification,
        Seed(42L),
        "house-price-ridge-v1",
        blueprint,
        Metrics.rmse
      )
      .runToValidated
  yield result
```

```scala mdoc
validated.map(result => (result.predictions.size, result.score.value))
```

This workflow stops after validation. It does not silently select the candidate,
add validation rows to training, or make a final-test claim. Those are separate
transitions because they change what data the model may use.

Continue with [Run Blueprint and Experiment routes](guides/experiments.md) for
selection, refit, train/validation/test, and precommitted holdout. Read
[How Alder prevents leakage](concepts/data-roles.md) when you need the reason
behind the data roles, or choose another task in [Guides](guides/README.md).

# Learn Alder through one workflow

This chapter follows one `House` dataset from construction through validation,
explicit selection, authorized refit, prediction, and audit inspection. The
same values continue through the chapter; there is no second quickstart hidden
behind different names.

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

## Fit and score validation data

One root seed expands into stable, plan-scoped seeds for splitting and fitting.
The experiment stops at a typed validation result: it does not silently select
or refit the candidate.

```scala mdoc:silent
// alder-first-workflow:start
val validated =
  for
    scaler <- Standardize.emitZero[House]
    ridge <- Ridge.lsqr[House](0.1)
    split <- Validation.rows(1L)
    workflow = Blueprint.supervised[House, Double].via(scaler).learn(ridge)
    result <- Experiment.validation(
      data, split, Seed(42L), "house-price-ridge-v1", workflow, Metrics.rmse
    ).run
  yield result
// alder-first-workflow:end
```

```scala mdoc
validated.map(result => (result.predictions.size, result.score.value))
```

The result contains one prediction for every validation row and the RMSE. It
does not silently select the candidate or add validation rows to training.

## Select and refit deliberately

Selection changes authority: it records why this candidate may proceed. Refit
then combines the training and validation partitions recorded by the route.

```scala mdoc
val refitted =
  for
    result <- validated
    selected = result.select(SingleCandidate)
    fitted <- selected.refit
  yield fitted

refitted.map(_.evaluation.score.value)
```

`SingleCandidate` is explicit because a reporting-only metric cannot authorize
selection. For a search, the selection policy and trial history occupy this
same boundary.

## Predict from the original input

The outer fitted workflow still accepts `House`; the standardizer is replayed
before the ridge model.

```scala mdoc
val prediction = refitted.flatMap(_.model.artifact
  .run(House(100.0, 3, 12.0))
  .left.map(_.toString)
)

prediction.map(_.isFinite)
```

## Inspect the evidence

The route retains both the prior validation result and the refitted audit.
The audit identifies the data, seed, numerical mode, backend, preparation
lineage, and component tree used for the fitted artifact.

```scala mdoc
refitted.map(result => (
  result.model.audit.data.policy,
  result.model.audit.seed,
  result.model.audit.children.map(_.component.id)
))
```

Continue with [Choose an experiment route](../how-to/experiments.md) when you
need a final held-out test or a precommitted candidate. Read
[How data roles prevent leakage](../understand/data-roles.md) for the reason
behind these transitions, or choose another task in [How-to](../how-to/README.md).

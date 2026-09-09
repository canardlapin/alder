# Learn Alder through one workflow

This chapter follows one `House` dataset through validation, evidence
inspection, prediction, explicit selection, and authorized refit. The same
values continue through the chapter; there is no second quickstart hidden
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
    House(52.0, 1, 55.0) -> 185.0,
    House(60.0, 1, 40.0) -> 210.0,
    House(68.0, 2, 35.0) -> 238.0,
    House(75.0, 2, 25.0) -> 265.0,
    House(82.0, 2, 22.0) -> 288.0,
    House(90.0, 2, 15.0) -> 315.0,
    House(98.0, 3, 18.0) -> 342.0,
    House(105.0, 3, 12.0) -> 370.0,
    House(110.0, 3, 10.0) -> 390.0,
    House(120.0, 4, 8.0) -> 430.0,
    House(130.0, 4, 5.0) -> 470.0,
    House(145.0, 4, 3.0) -> 525.0
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
val candidate =
  Standardize[House](zeroVariance = ZeroVariance.AsZero).learnWith(
    Ridge.lsqr[House](penalty = RidgePenalty.const(0.1))
  )

val validated =
  for
    split <- Validation.fraction(numerator = 1L, denominator = 4L)
    result <- Experiment.validation(
      data = data,
      specification = split,
      seed = Seed(42L),
      plan = "house-price-ridge-v1",
      learner = candidate,
      metric = Metrics.rmse
    ).run
  yield result
// alder-first-workflow:end
```

## Inspect the held-out result

The lifecycle result retains the scored validation rows and the complete audit.
Its `report` is a typed, read-only projection for ordinary inspection; it does
not replace either source of evidence.

```scala mdoc
val validationEvidence = validated.map { result =>
  val heldOut = result.predictions.data.foldRows(
    Vector.empty[(Double, Double)]
  ) { (rows, _, scored) =>
    rows :+ (scored.truth, scored.prediction)
  }
  (heldOut, result.report)
}

validationEvidence.map { case (heldOut, report) =>
  (
    heldOut,
    report.score.value,
    report.partitions,
    report.components.map(_.descriptor.id)
  )
}
```

The result shows the three held-out truth/prediction pairs, the RMSE, the
candidate identity, and the fitted component summary. It does not silently
select the candidate or add validation rows to training.

Rendering is a separate edge operation, and the score renderer remains
explicit:

```scala mdoc
validationEvidence.map { case (_, report) =>
  report.render(score => f"${score.value}%.3f")
}
```

If construction or execution fails, `ExperimentFailure` remains available for
typed pattern matching. Use `error.render` only when presenting the failure to
a person or log.

## Predict with the validated candidate

The validation result delegates prediction to the exact fitted workflow. The
standardizer is replayed before ridge, so the input remains `House`.

```scala mdoc
val candidatePrediction =
  validated.map(_.predict(House(100.0, 3, 12.0)))

candidatePrediction.map(_.exists(_.isFinite))
```

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

## Predict with the refitted workflow

The refitted workflow has seen the authorized Train and Validation rows. It
still accepts the original input type directly.

```scala mdoc
val prediction =
  refitted.map(_.predict(House(100.0, 3, 12.0)))

prediction.map(_.exists(_.isFinite))
```

## Inspect the complete refit audit

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
need a final held-out test or a precommitted candidate. Then use
[deterministic tuning](../how-to/tuning.md) without exposing Test data. Alder
does not yet ship a concrete target-aware encoder; the current
[target-aware chapter](../extend/target-aware.md) is explicitly an
extension-author tutorial. Read
[How data roles prevent leakage](../understand/data-roles.md) for the reason
behind these transitions, or choose another task in [How-to](../how-to/README.md).

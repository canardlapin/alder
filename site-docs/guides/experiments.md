# Run Blueprint and Experiment routes

`Blueprint` stages a supervised workflow without adding audit nodes.
`Experiment` owns the split, fit, score, select, and refit transitions so those
steps cannot be reordered by accident.

## Build a blueprint

```scala mdoc:silent
import alder.quickstart.*

final case class House(
    area: Double,
    bedrooms: Int,
    age: Double
) derives Coordinates, Schema

val houses = Supervised.fromPairs(
  Vector(
    House(60.0, 1, 40.0) -> 210.0,
    House(75.0, 2, 25.0) -> 265.0,
    House(90.0, 2, 15.0) -> 315.0,
    House(110.0, 3, 10.0) -> 390.0,
    House(130.0, 4, 5.0) -> 470.0,
    House(150.0, 4, 2.0) -> 520.0
  ),
  "house-experiments-v1"
)

val blueprint =
  for
    scaler <- Standardize.emitZero[House]
    ridge <- Ridge.lsqr[House](0.1)
  yield Blueprint.supervised[House, Double].via(scaler).learn(ridge)
```

`via` attaches target-blind preparation. `learn` attaches a terminal learner.
The completed value expands to `transform.learnWith(learner)` with the same
concrete component types.

## Validation: score, then decide

Validation fits on train, scores on validation, and stops. Selection and refit
are separate calls because they change which data the model may use.

```scala mdoc
val validated =
  for
    completed <- blueprint
    specification <- Validation.rows(1L)
    result <- Experiment
      .validation(
        houses,
        specification,
        Seed(42L),
        "house-validation-v1",
        completed,
        Metrics.rmse
      )
      .runToValidated
  yield result

validated.map(result => (result.predictions.size, result.score.value.isFinite))
```

```scala mdoc
val refitted =
  for
    result <- validated
    selected = result.select(SingleCandidate)
    model <- selected.refit
  yield model

refitted.flatMap { result =>
  result.model.artifact
    .run(House(100.0, 3, 12.0))
    .left
    .map(_.toString)
}
```

`select` requires an `ObjectiveMetric`. A reporting-only `Metric` can score
predictions but cannot authorize selection or receipt-gated refit.

## Train / validation / test

When you need a final held-out score after selection, use the three-way route.
Deployment refit is available only after the test transition.

```scala mdoc
import alder.data.TrainValidationTestSpec

val tested =
  for
    completed <- blueprint
    specification <- TrainValidationTestSpec.rows(1L, 1L)
    result <- Experiment
      .trainValidationTest(
        houses,
        specification,
        Seed(18L),
        "house-tvt-v1",
        completed.learner,
        Metrics.rmse
      )
      .runToTested
  yield result

tested.map(result => (result.evaluation.scored.size, result.score.value.isFinite))
```

```scala mdoc
val deployed =
  for
    result <- tested
    model <- result.deploymentRefit
  yield model

deployed.map(_.prior.score.value.isFinite)
```

## Precommitted holdout

Use precommitted evaluation when the candidate is already fixed and you only
need a Train/Test score. There is no selection step.

```scala mdoc
import alder.data.HoldoutSpec

val precommitted =
  for
    completed <- blueprint
    specification <- HoldoutSpec.rows(2L)
    result <- Experiment
      .precommitted(
        houses,
        specification,
        Seed(21L),
        "house-precommitted-v1",
        completed.learner,
        Metrics.rmse
      )
      .runToTested
  yield result

precommitted.map(result =>
  (result.evaluation.scored.size, result.score.value.isFinite)
)
```

Continue with [Predict and inspect fitted models](predicting.md) after you have
a `Trained` value, or [Build deterministic tuning spaces](tuning.md) when the
candidate itself must be chosen by search.

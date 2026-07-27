# Fit and validate your first workflow

This example fits a standardizer and ridge model, predicts every validation
row, and computes root mean squared error. Alder records how the model was
fitted and prevents validation rows from being passed to `fit`.

## Define the input and observations

`Coordinates` gives the model an ordered numeric view of `House`. `Schema`
gives the fit audit a stable description of that input. Scala derives both from
the case class.

```scala mdoc:silent
import alder.data.*
import alder.application.*
import alder.kernel.*
import alder.metrics.*
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

The split specification is checked but has no seed. One root seed expands into
stable, plan-scoped seeds for splitting and fitting. The resulting partitions
have different types; only `split.train` can be passed to this fit.

```scala mdoc:silent
val plan = PlanFingerprint.external("house-price-ridge-v1")
val seeds = PhaseSeeds(Seed(42L), plan)

val split =
  ValidationSpec
    .rows(1)
    .left
    .map(_.toString)
    .flatMap(specification =>
      Split
        .validation(data, specification, seeds.split)
        .left
        .map(_.toString)
    )

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

## Fit and score

`Fit.learner` derives the schema fingerprint, uses deterministic numerics by
default, and exposes the synchronous result as `Either`. `Evaluation.scored`
then predicts every validation example from its input while retaining that
row's truth, metadata, and `RowId`.

```scala mdoc:silent
val validated =
  (split, workflow).tupled.flatMap { (partitions, learner) =>
    Fit
      .learner(
        learner,
        partitions.train,
        seed = seeds.candidateFit,
        plan = plan
      )
      .left
      .map(failure =>
        s"fit failed at ${failure.stage.render}: ${failure.cause}"
      )
      .flatMap { model =>
        EvaluationSources
          .validation(
            partitions.train,
            partitions.validation.data
          )
          .left
          .map(error => s"validation sources failed: $error")
          .flatMap(sources =>
            Evaluation
              .scored(
                model,
                sources,
                RegressionMetrics.rmse[Unit]
              )
              .left
              .map(error => s"validation failed: $error")
          )
      }
  }
```

The result is a typed validation result, not a loose score:

```scala mdoc
validated.map(result => (result.scored.size, result.score.value))
```

This workflow stops after validation. It does not silently select the candidate,
add validation rows to training, or make a final-test claim. Those are separate
transitions because they change what data the model may use.

Continue with [How Alder prevents leakage](concepts/data-roles.md) when you need
to understand the data roles, or go directly to a task in
[Guides](guides/README.md).

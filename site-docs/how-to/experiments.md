# Choose an experiment route

Start with the question your evaluation is allowed to answer. `Experiment`
owns splitting, fitting, prediction, scoring, selection receipts, and refit so
those transitions cannot be silently reordered.

| Question | Route | Partitions | Selection | Last authorized refit |
| --- | --- | --- | --- | --- |
| How does this candidate score before I proceed? | `validation` | Train / Validation | explicit after scoring | Train + Validation |
| How does a selected candidate perform once on final held-out data? | `trainValidationTest` | Train / Validation / Test | explicit before Test | Train + Validation + Test after evaluation |
| How does an already-fixed candidate perform on a committed holdout? | `precommitted` | Train / Test | none | Train + Test after evaluation |

Use validation while designing or comparing candidates. Use the three-way
route when you need a final held-out score after selection. Use precommitted
holdout only when the candidate and evaluation rule were fixed before the
split.

## Validation stops before selection

The [Learn workflow](../learn/workflow.md) executes this route. Its result
contains validation predictions and a metric value, but no automatic authority
to refit:

```scala
val selected = validated.map(_.select(SingleCandidate))
val refitted = selected.flatMap(_.refit)
```

`select` requires an `ObjectiveMetric`. A reporting-only `Metric` can compute a
score but cannot produce selection evidence.

## Three-way evaluation protects the final test

`Experiment.trainValidationTest` scores candidates on Validation, requires the
route's selection transition, and evaluates exactly once on Test. Only the
tested result exposes `deploymentRefit`; a merely validated candidate cannot
fit the test partition.

## Precommitted evaluation has no selection phase

`Experiment.precommitted` fits Train and scores Test for an already-fixed
candidate. Removing the selection phase is a substantive commitment, not a
shorter spelling for train/validation/test.

## Operation effects

| Operation | Fits state? | Reads targets? | Changes data authority? | Audit consequence |
| --- | --- | --- | --- | --- |
| deterministic split | no | no | assigns typed roles | split policy, seed, partition fingerprints |
| target-blind transform fit | yes | no | no | fitted component and reusable preparation lineage |
| cross-fitted feature map | yes | yes, inside folds | produces learner-ready rows | fold assignments, seeds, encoder and serving fits |
| terminal learner fit | yes | yes | no downstream fitted stage allowed | terminal component audit |
| metric scoring | no fitted model | yes | no | score and metric identity |
| selection | no | uses score evidence | authorizes the next transition | selection policy and receipt |
| refit | yes | yes, on newly authorized roles | consumes receipt authority | refit receipt and complete fitted audit |

For prediction and terminal-model inspection, continue with
[Predict and inspect a fitted model](predicting.md). For why these transitions
have distinct types, read [Data roles and preparation scope](../understand/data-roles.md).

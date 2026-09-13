# Troubleshooting

## Fitting rejects the dataset type

**Symptom:** `fit` does not accept `Data[Use.Test, A]` or
`NonEmptyData[Use.Test, A]`.

**Cause:** evaluation data cannot enter fitting.

**Action:** fit on `Use.Train` or receipt-authorized `Use.Refit` data. Do not
cast or retag the dataset.

## Validation cannot select with a reporting metric

**Symptom:** `validated.select(...)` does not compile for a plain `Metric`.

**Cause:** selection and receipt-gated refit require an `ObjectiveMetric`, which
owns an optimization direction. A reporting-only metric can score predictions
but cannot authorize selection.

**Action:** use an objective metric such as RMSE or accuracy for selection, or
keep the reporting metric for evaluation-only paths.

## A feature map has no `andThen`

**Symptom:** a target-aware `FeatureMap` cannot feed a fitted `Transform`.

**Cause:** its out-of-fold rows are `LearnerReady`, not `Reusable`. Fitting
pooled preprocessing on them could reintroduce own-target leakage.

**Action:** attach the transform to the `FoldEncoder` before cross-fitting, use
`mapOutput` for a total stateless function, or feed the feature map directly to
a learner.

## Cross-fitting rejects a resampler

**Symptom:** `FeatureMap.crossFitted` rejects `Resampler[A]`.

**Cause:** ordinary resampling does not prove that every row appears in exactly
one assessment partition.

**Action:** use a `CompleteResampler`, such as `KFold` or `GroupedKFold`. For
Resample4s inside a composed workflow, use
`Resample4sResampler.fromDesign(exactOnceDesign)` so Alder binds the population
and normalized stage seed when splitting. Use `fromCompiled` only when the
compiled plan was created for that exact stage seed and population receipt.
Rolling-origin resampling is intentionally not complete.

## A precompiled Resample4s plan reports a seed mismatch

**Symptom:** a receipt-verified plan works when a feature map is fitted alone
but fails after `learnWith` or another composition with
`Resample4sSeedMismatch`.

**Cause:** Alder derives a private child-stage seed from the root plan and
stable ordinal. A plan compiled from the root seed is not a plan for that child
stage.

**Action:** retain `fromCompiled` as the strict prebound route when this exact
binding is intentional. Otherwise construct the complete resampler with
`Resample4sResampler.fromDesign`; it compiles and receipts the design only when
Alder supplies the actual stage context.

## A scaler reports a constant coordinate

**Symptom:** fitting returns `ScaleFitError.ConstantCoordinate`.

**Cause:** the training partition has zero population variance for that
coordinate.

**Action:** choose `ZeroVariance.AsZero` when a zero standardized coordinate
is meaningful, or remove the coordinate. Do not silently divide by zero.

## A backend rejects the numerical mode

**Symptom:** ridge fitting returns `NumericModeMismatch`.

**Cause:** the backend captured a different numerical policy at construction.

**Action:** construct the backend with the same `NumericMode` as the root
`FitContext`.

## Scala Native fails during the aggregate gate

**Symptom:** Native compilation or NIR generation exhausts the default sbt
heap.

**Action:** run:

```text
sbt -J-Xmx4G -Dsbt.task.cpus=1 test
```

Do not weaken source code or platform coverage to work around a build heap
limit.

## Compatibility checks have no previous artifacts

**Symptom:** MiMa reports that the previous-artifact set is empty.

**Cause:** this is expected before the first 0.1.0 publication.

**Action:** after publishing 0.1.0, pass
`-Dalder.compatibility.previous=0.1.0`.

## The build cannot resolve sibling projects

**Symptom:** sbt cannot load `../resample4s`, `../gale`, or `../linop4s`.

**Cause:** the pre-release build still uses development source composites.

**Action:** place the sibling checkouts beside Alder. A stable release must
replace these composites with published coordinates.

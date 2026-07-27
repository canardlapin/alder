# Data roles and preparation scope

Alder tracks a dataset's purpose in the type parameter of `Data[U, A]`.
There is no runtime value to inspect and no public retagging function.

## Dataset roles

```text
Use
├── Unsplit
├── Fit
│   ├── Train
│   └── Refit
└── Evaluation
    ├── Validation
    └── Test
```

- `Unsplit` data has not entered an evaluation protocol.
- `Train` data may fit components and run tuning studies.
- `Refit` data may fit a new artifact only after a one-shot receipt authorizes
  its exact source manifest.
- `Validation` and `Test` data may be evaluated but cannot be fitted.

Most fitting signatures accept `U <: Use.Fit`, so the same implementation can
fit training or receipt-authorized refit data without erasing the role. A
`Study` accepts exactly `Use.Train`; tuning on final refit data would repeat
model selection after evaluation.

Prediction, scoring, selection, and refit are separate transitions. A
`PredictionReceipt` proves that every held-out row produced a prediction but
grants no data access. Successful metric finishing produces a role-typed
`EvaluationReceipt`. Validation evaluation still cannot authorize refit:
explicit selection produces the `SelectionReceipt` that permits exactly
Train+Validation. A Test evaluation receipt can authorize only its exact
all-observed manifest for deployment refit.

## Prepared rows

Fitting preprocessing produces `Prepared[S, U, A, B]`. It contains a fitted
artifact and the training rows that Alder may safely pass to the next stage.
Those rows are private to Alder's composition protocol.

The preparation scope `S` has two cases:

```text
Preparation.LearnerReady
└── Preparation.Reusable
```

`Reusable` is the stronger guarantee. The stage did not use targets, so its
prepared rows may train another fitted transform or a learner.

`LearnerReady` means every row was prepared without its own target, usually by
out-of-fold encoding. Those rows may train a terminal learner. They may not
train another fitted preprocessor because pooled statistics could carry a row's
target back into its own representation.

This is why the following composition is absent from the API:

```scala mdoc:fail
import alder.kernel.*
import cats.Id

def leaks(
    featureMap: FeatureMap[Id, Double, Double, Unit, Double],
    transform: Transform[Id, Double, Double]
): Unit =
  val _ = featureMap.andThen(transform)
```

Use `FoldEncoder.andThen` to attach target-blind postprocessing inside each fold,
or use `FeatureMap.mapOutput` for a total function with no fitted state.

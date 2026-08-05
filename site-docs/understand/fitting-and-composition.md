# Fitting and composition

Alder separates the component that can fit from the `Pipe` that can run.

## Component roles

| Component | What fitting may observe | What it produces |
| --- | --- | --- |
| `Transform[F, X, Z]` | inputs `X` only | replayable prepared rows and `Pipe[X, E, Z]` |
| `FoldEncoder[F, X, Y, M, Z]` | inputs, targets, metadata | an encoder state used by cross-fitting |
| `FeatureMap[F, X, Y, M, Z]` | targets under a leakage-safe protocol | learner-ready rows and a serving pipe |
| `Learner[F, X, Y, M, P]` | all fitting targets | terminal prediction pipe |

`Pipe[-A, +E, +B]` is already fitted. Calling `run` returns either a value or a
`Failure[E]`. The failure retains the stage path, so a composed workflow reports
the leaf that failed instead of only the outer pipeline.

## Legal composition

```text
Transform  → Transform   = Transform
Transform  → FeatureMap  = FeatureMap
Transform  → Learner     = Learner
FeatureMap → Learner     = Learner
FoldEncoder → Transform  = FoldEncoder
```

A learner is terminal. A target-aware `FeatureMap` cannot feed fitted
preprocessing. Total rowwise mapping remains available through
`FeatureMap.mapOutput`.

## FitResult

`FitResult[F, E, A]` is an effect `F` around
`Either[Failure[E], A]`. Composition widens concrete error types into Scala 3
unions while retaining each component's exact member types.

Expected input, numerical, convergence, and backend failures belong in the
typed error channel. Exceptions are not the ordinary validation mechanism.

## Audit ownership

A leaf calls `FitContext.complete` once with:

- its fitted artifact;
- the exact fitting data; and
- a stable `ComponentDescriptor`.

Alder's combinators construct composite audits internally. They assign stable
stage ordinals, derive child seeds, and retain child audits. Extension code must
not construct `Trained`, `Audit`, `Prepared`, or `FitContext`.

See [Writing an extension](../extend/writing-an-extension.md) for the implementation
checklist and executable negative examples.

# Write an Alder extension

This guide is for authors implementing a transform, target-aware encoder, or
learner. The root `AGENTS.md` file contains the full repository checklist; this
page explains the public extension contract.

## Choose the component

- Implement `Transform` when fitting observes inputs only.
- Implement `FoldEncoder` when fitting observes targets and produces a state
  that can encode one input.
- Implement `Learner` when the result is terminal.
- Use `FeatureMap.inputOnly` to apply a transform to the inputs of examples.
- Use `FeatureMap.crossFitted` with a `CompleteResampler` to expose a
  target-aware encoder safely.

Do not implement a target-aware operation as a `Transform`. Its fitting input
does not contain targets, and adding targets through ambient state would defeat
the protocol.

## Complete one leaf audit

A leaf calls `FitContext.complete` exactly once after validation and fitting:

```scala
context.complete(
  artifact = fittedPipe,
  trainedOn = data,
  component = descriptor
)
```

Pass the exact `NonEmptyData` used for fitting. The descriptor must have a
stable component ID, component version, structured parameters, and the actual
backend fingerprint.

Do not construct `Trained`, `Audit`, `Prepared`, or `FitContext`. Alder owns
composite audits, stage ordinals, child seeds, preparation lineage, and replay.

## Preserve concrete member types

Keep the concrete component subtype in combinator type parameters. Erasing a
stage to `Transform[...]` or `Learner[...]` also erases its path-dependent
`FitError`, `RunError`, and fitted artifact members.

## Prove forbidden programs remain forbidden

Use `scala.compiletime.testing.typeCheckErrors` in plugin tests. The guide site
also checks representative failures with mdoc.

A target-aware feature map cannot feed another fitted transform:

```scala mdoc:fail
import alder.kernel.*
import cats.Id

def illegal(
    feature: FeatureMap[Id, Double, Double, Unit, Double],
    transform: Transform[Id, Double, Double]
): Unit =
  val _ = feature.andThen(transform)
```

Cross-fitting requires exact assessment coverage:

```scala mdoc:fail
import alder.data.*
import alder.kernel.*
import cats.Id

def incomplete(
    encoder: FoldEncoder[Id, Double, Double, Unit, Double],
    resampler: Resampler[Example[Double, Double, Unit]]
): Unit =
  val _ = FeatureMap.crossFitted(encoder, resampler)
```

## Run published laws

Add the matching laws artifact and `alder-testkit` at test scope. Instantiate
the relevant Discipline suite:

- `TransformTests`
- `FeatureMapTests`
- `CrossFitLeakageTests`
- `LearnerTests`
- `ArtifactCodecTests`
- `MetricTests`
- `SpaceTests`, `TuningErasureTests`, or `StudyTests`

Run the laws on every platform the plugin claims to support. Test typed
validation and numerical failures in addition to successful examples.

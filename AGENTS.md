# Extending Alder

Alder separates fitted preprocessing from terminal learning so that target
information cannot leak through an ordinary composition. Extension code must
preserve that distinction. `PRD.json` is the authoritative contract; this file
turns its extension rules into an implementer checklist.

## Choose the component type

Use this decision tree before writing an implementation:

1. Does fitting inspect only the input `X`?
   - Implement `Transform[F, X, Z]`.
   - If it must operate on `Example[X, Y, M]`, wrap it with
     `FeatureMap.inputOnly`. Do not copy targets or metadata by hand.
2. Does fitting inspect targets and produce an encoder state that can transform
   a single input?
   - Implement `FoldEncoder[F, X, Y, M, Z]`.
   - Construct the public feature map with `FeatureMap.crossFitted` and a
     `CompleteResampler`. A plain `Resampler` is not enough because it does not
     prove exact assessment coverage.
   - Apply fitted, target-blind postprocessing with `FoldEncoder.andThen` before
     constructing the cross-fitted feature map.
3. Is the component terminal, so that its fitted output is never used to fit a
   later stage?
   - Implement `Learner[F, X, Y, M, P]`.
   - Compose preparation with `featureMap.learnWith(learner)` or
     `transform.learnWith(learner)`.
4. Is the operation a total rowwise function with no fitted state?
   - Use `FeatureMap.mapOutput`.
   - Do not pass a fitted `Pipe` to `mapOutput`; a fitted stage has training
     dependencies and must remain visible in the audit.

The safe composition table is:

| Left component | Allowed right component | Result |
| --- | --- | --- |
| `Transform` | `Transform` | `Transform` |
| `Transform` | `FeatureMap` | `FeatureMap` |
| `Transform` | `Learner` | `Learner` |
| `FeatureMap` | total function through `mapOutput` | same `FeatureMap` scope |
| `FeatureMap` | `Learner` | `Learner` |
| `FoldEncoder` | `Transform` | `FoldEncoder` |
| `Learner` | none | terminal |

`Preparation.Reusable` rows may feed another fitted stage or a learner.
`Preparation.LearnerReady` rows may feed only a learner. The latter rows are
out-of-fold values: pooling them to fit another preprocessing stage would let
each row's target influence the pooled state. Do not expose or retag
`Prepared.rows`; Alder owns them as a protocol resource.

## Implement a leaf fit

A leaf implementation receives a `FitContext` and returns `FitResult`. Call
`context.complete` exactly once after fitting the leaf artifact:

```scala
def fit[U <: Use.Fit](
    data: NonEmptyData[U, Example[X, Y, M]]
)(using context: FitContext): FitResult[F, FitError, Trained[Model]] =
  // validate and fit inside F, then:
  context.complete(model, data, componentDescriptor)
```

Follow these rules:

- Pass the exact `NonEmptyData` used to fit the artifact. Do not rebuild it or
  change its role.
- Supply a stable `ComponentDescriptor` with structured parameters and a real
  backend fingerprint. Do not put opaque state in display strings.
- Report failures as `Failure[E]` through `FitResult`. Do not throw for an
  expected validation, numerical, or convergence failure.
- Do not construct `Trained`, `Audit`, `Prepared`, or `FitContext`.
- Do not call `FitContext.complete` for a composition. Alder's combinators call
  the private composite path, retain child audits, and assign stable stage
  ordinals and derived seeds.
- Do not derive a child seed or stage path yourself. The framework calls the
  internal `fitFrom` hook with the stage's stable ordinal.
- A target-blind `Transform` must return replayed `Reusable` rows. Alder's
  package-private replay factory performs the replay and preserves row IDs;
  external code cannot substitute cached training scores.

`FitContext` is final so extensions can consume it but cannot replace the
framework's audit and seed behavior.

## Dependency recipes

Use Scala 3.7.4. Pure plugins should cross-build for the same platforms as
their dependencies.

A component-only plugin needs the kernel. Add the published laws and testkit at
test scope:

```scala
libraryDependencies ++= Seq(
  "io.github.canardlapin" %%% "alder-kernel"  % alderVersion,
  "io.github.canardlapin" %%% "alder-laws"    % alderVersion % Test,
  "io.github.canardlapin" %%% "alder-testkit" % alderVersion % Test
)
```

A plugin that constructs cross-fitted feature maps also needs `alder-data`:

```scala
libraryDependencies ++= Seq(
  "io.github.canardlapin" %%% "alder-kernel" % alderVersion,
  "io.github.canardlapin" %%% "alder-data"   % alderVersion
)
```

Keep numerical backends in a separate artifact:

```scala
// plugin-core
libraryDependencies +=
  "io.github.canardlapin" %%% "alder-kernel" % alderVersion

// plugin-backend
libraryDependencies ++= Seq(
  "org.example"           %%% "plugin-core"   % pluginVersion,
  "org.example"           %%% "solver-core"   % solverVersion,
  "io.github.canardlapin" %%% "alder-laws"    % alderVersion % Test,
  "io.github.canardlapin" %%% "alder-testkit" % alderVersion % Test
)
```

The core artifact must not depend on a solver. Add `alder-data` to plugin core
only when it owns a resampling integration. Never add a runtime dependency on
an Alder laws artifact.

## Law checklist

Every public extension must:

- instantiate the relevant published Discipline suite:
  `TransformTests`, `FeatureMapTests`, `CrossFitLeakageTests`, `LearnerTests`,
  or `ArtifactCodecTests`;
- test identity and associativity for every composition it introduces;
- preserve row IDs and data roles;
- prove the `Transform` training-replay law;
- prove first-failure stage attribution and deterministic audit/seed behavior;
- test expected validation, numerical, convergence, and malformed-backend
  failures as typed values;
- use `CrossFitLeakageTests` for target-aware encoders and exercise a
  `CompleteResampler`;
- use `MetricTests` for a metric, including partition, merge, permutation, and
  invalid-value behavior;
- use `SpaceTests`, `TuningErasureTests`, or `StudyTests` for tuning
  extensions;
- run the laws on every platform the plugin claims to support.

Add compile-negative tests with `scala.compiletime.testing.typeCheckErrors`
when safety depends on code not compiling. Keep the snippet as a literal string
so that Scala 3.7.4 checks the intended source.

## Compile-negative examples

These programs must not compile:

```scala
// Evaluation data cannot be fitted.
transform.fit(testData: NonEmptyData[Use.Test, X])

// A target-aware FeatureMap cannot feed more fitted preprocessing.
featureMap.andThen(transform)

// Exact assessment coverage is required for cross-fitting.
FeatureMap.crossFitted(encoder, resampler: Resampler[Example[X, Y, M]])

// Search spaces are applicative, not monadic; later parameters cannot depend
// on earlier sampled values.
space.flatMap(next)

// A Study accepts Train only.
study.run(testData: NonEmptyData[Use.Test, A])

// Capability evidence must be explicit.
def weighted[M](using WeightOf[M]): Unit = ()
weighted[Unit]
```

Also retain negative tests that prevent consumer construction of
`NonEmptyData`, access to `Prepared.rows`, reuse of a refit receipt, and direct
construction of audit-bearing values.

## Local gates and release claims

Run the full local gate with:

```text
sbt -J-Xmx4G -Dsbt.task.cpus=1 test
```

Run the 100k-row performance baselines with:

```text
sbt -J-Xmx4G -Dsbt.task.cpus=1 benchmarks/test
```

Measure JVM statement coverage for a module with:

```text
sbt -J-Xmx4G -Dsbt.task.cpus=1 coverage applicationJVM/test coverageReport coverageOff
```

HTML reports land under `<module>/.jvm/target/scala-3.7.4/scoverage-report/`.

The twelve interface acceptance gates and their owner evidence commands are
listed in `RELEASE_GATES.md`.

Run compatibility tasks with:

```text
sbt -J-Xmx4G compatibilityCheck
```

Compile every public guide example and render the documentation site with:

```text
sbt -J-Xmx4G -Dsbt.task.cpus=1 docs/tlSite
```

Generate API documentation for every JVM artifact with:

```text
sbt -J-Xmx4G -Dsbt.task.cpus=1 apiDocs
```

The first `0.1.0` release has no previous Alder artifact, so the compatibility
tasks are configured but compare an empty baseline. After `0.1.0` is published,
run:

```text
sbt -J-Xmx4G -Dalder.compatibility.previous=0.1.0 compatibilityCheck
```

Do not describe configured checks as executed checks, local results as remote
CI, or a local package as a published artifact. Stable Alder artifacts must use
published dependency versions. Development composites and snapshot backends
are not release evidence.

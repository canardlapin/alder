# Alder

Alder helps Scala programs fit preprocessing and models without accidentally
training on held-out data or leaking a row's own target into its features.
Training, validation, and test data have different types, and every fitted
model retains an audit of the data identity, seed, backend, and fitting steps
that produced it.

The project uses Scala 3.7.4 and cross-builds its pure modules for the JVM,
Scala.js, and Scala Native.

## First workflow

```scala
import alder.quickstart.*

final case class House(area: Double, bedrooms: Int, age: Double)
    derives Coordinates, Schema

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

```scala
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

The marked body is checked for exact identity with the
[mdoc-compiled walkthrough](site-docs/learn/workflow.md). That chapter
continues by inspecting held-out predictions and the typed report, predicting
through the validated workflow, then selecting and refitting deliberately.

Successful lifecycle results predict without exposing wrapper internals:

```scala
validated.map(_.predict(House(100.0, 3, 12.0)))
```

## Choose your path

Depend on `alder-quickstart` for the ordinary supervised path. The executable
guide begins with [one complete workflow](site-docs/learn/workflow.md). Use the
[How-to pages](site-docs/how-to/README.md) for focused operations and
[Understand](site-docs/understand/README.md) for the protocol beneath them.

Plugin authors that only need the SPI should depend on `alder-kernel` (and
`alder-data` when constructing cross-fitted feature maps), plus `alder-laws`
and `alder-testkit` at test scope.

When a cross-fitted workflow is driven by Resample4s, pass its typed
`Design[Split[Selection], Coverage.ExactOnce]` to
`Resample4sResampler.fromDesign`. Alder compiles it against the actual fitting
population and framework-derived stage seed, so the adapter remains correct
inside `learnWith` and longer compositions. `fromCompiled` is the stricter
alternative for callers that already own the exact stage seed and population
receipt; it deliberately rejects any later mismatch.

## Pre-release status

The current checkout contains a cross-platform implementation and test suites.
It uses source composites for Resample4s, Gale, and linop4s during development.
It is not ready for a stable publication until every dependency used by a
published Alder artifact has a released coordinate and the release gates have
current evidence.

The dense Gale adapter is explicitly non-publishable while Gale remains a
snapshot dependency. An `origin` remote is configured, but this checkout has no
repository workflow definitions and does not prove remote required checks,
hosted documentation, or publication. See the
[current release-status ledger](site-docs/reference/release-status.md) for the
evidence checked on 2026-08-05.

## Build the current source

Place the sibling development checkouts at `../gale` and `../linop4s`.
For Resample4s, use either a sibling checkout at `../resample4s` or the locally
published `0.1.0-SNAPSHOT` artifacts. Then run:

```text
sbt -J-Xmx4G -Dsbt.task.cpus=1 test
```

Loose 100k-row wall-clock regression guards:

```text
sbt -J-Xmx4G -Dsbt.task.cpus=1 benchmarks/test
```

Interface acceptance gates are listed in [`RELEASE_GATES.md`](RELEASE_GATES.md).

This aggregate command compiles and tests the required JVM, Scala.js, and Scala
Native projects.

Compatibility tooling is configured for JVM binary and Scala 3 TASTy checks:

```text
sbt -J-Xmx4G compatibilityCheck
```

There is no previous artifact for the first `0.1.0` release. After that artifact
is published, select it explicitly:

```text
sbt -J-Xmx4G -Dalder.compatibility.previous=0.1.0 compatibilityCheck
```

## Documentation development

All Scala examples in the public guide are compiled by mdoc.

Generate the guide site and each module's Scala API reference with:

```text
sbt -J-Xmx4G -Dsbt.task.cpus=1 docs/tlSite
sbt -J-Xmx4G -Dsbt.task.cpus=1 apiDocs
```

The generated guide is local build output under `site/target/docs/site`. No
deployment workflow is present in this checkout, and a local build is not
evidence of a public documentation deployment.

See [AGENTS.md](AGENTS.md) for the contributor extension contract. The
[current status ledger](site-docs/reference/release-status.md) separates local
evidence from external release blockers; the
[2026-07-26 release-readiness audit](docs/reviews/release-readiness-2026-07-26.md)
is retained as a historical snapshot.

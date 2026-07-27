# Alder

Alder helps Scala programs fit preprocessing and models without accidentally
training on held-out data or leaking a row's own target into its features.
Training, validation, and test data have different types, and every fitted
model retains an audit of the data identity, seed, backend, and fitting steps
that produced it.

The project uses Scala 3.7.4 and cross-builds its pure modules for the JVM,
Scala.js, and Scala Native.

## Start here

The executable guide begins with
[a complete standardize-fit-validate workflow](site-docs/getting-started.md).
It then covers preprocessing, metrics, tuning, safe target-aware feature
construction, extension authoring, and troubleshooting.

## Pre-release status

The local implementation and its cross-platform tests are working. This
checkout uses source composites for Tessera, Gale, and linop4s during
development. It is not ready for a stable publication until every dependency
used by a published Alder artifact has a released coordinate.

The dense Gale adapter is explicitly non-publishable while Gale remains a
snapshot dependency. Remote CI and publication are also unverified because
this repository has no configured remote.

## Build the current source

Place the sibling development checkouts at `../gale` and `../linop4s`.
For Tessera, use either a sibling checkout at `../tessera` or the locally
published `0.1.0-SNAPSHOT` artifacts. Then run:

```text
sbt -J-Xmx4G -Dsbt.task.cpus=1 test
```

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

The generated guide is local build output under `site/target/docs/site`; no
public documentation deployment is configured yet.

See [AGENTS.md](AGENTS.md) for the contributor extension contract and
[the release-readiness audit](docs/reviews/release-readiness-2026-07-26.md)
for the distinction between local evidence and external release blockers.

# Alder

Alder is a typed fitting and evaluation protocol for Scala 3. It separates
target-blind transforms, leakage-aware feature maps, and terminal learners.
Data roles, row identities, preparation scope, failures, seeds, and audit
records remain explicit through composition.

The project uses Scala 3.7.4 and cross-builds its pure modules for the JVM,
Scala.js, and Scala Native.

## Repository status

The local implementation and its cross-platform tests are working. This
checkout uses source composites for Tessera, Gale, and linop4s during
development. It is not ready for a stable publication until every dependency
used by a published Alder artifact has a released coordinate.

The dense Gale adapter is explicitly non-publishable while Gale remains a
snapshot dependency. Remote CI and publication are also unverified because
this repository has no configured remote.

## Build

Place the sibling development checkouts at `../tessera`, `../gale`, and
`../linop4s`, then run:

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

## Documentation

Start with the [Alder guide](site-docs/README.md). It covers the first fitted
pipeline, typed data roles, safe composition, preprocessing, metrics, tuning,
extension authoring, audit behavior, module selection, and troubleshooting.
All Scala examples in the guide are compiled by mdoc.

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

# Getting started

This page is for users evaluating Alder before its first published release. It
shows how to build the source tree and where to begin in the API.

## Requirements

- JDK 21 or newer;
- sbt 1.x;
- Scala 3.7.4, selected by the build; and
- sibling checkouts named `tessera`, `gale`, and `linop4s`.

Use this directory layout:

```text
scala/
├── alder/
├── gale/
├── linop4s/
└── tessera/
```

From the Alder checkout, run:

```text
sbt -J-Xmx4G -Dsbt.task.cpus=1 test
```

The aggregate gate compiles and tests the supported JVM, Scala.js, and Scala
Native projects. Scala Native needs the larger heap on this repository.

Generate the public guides and API documentation separately:

```text
sbt -J-Xmx4G docs/tlSite
sbt -J-Xmx4G apiDocs
```

`docs/tlSite` compiles the Markdown examples and validates internal links.
`apiDocs` generates module-specific Scaladoc.

## Choose modules by task

Start with `alder-kernel`. Add only the modules required by the workflow:

| Task | Modules |
| --- | --- |
| Define or compose fitted components | `alder-kernel` |
| Store data, split rows, or cross-fit | `alder-data` |
| Standardize numeric features | `alder-preprocess` |
| Compute streaming metrics | `alder-metrics` |
| Fit backend-neutral ridge models | `alder-models-linear` plus one backend |
| Define deterministic search spaces | `alder-tune` |
| Encode fitted artifacts | `alder-codec` |
| Test an extension | the matching laws artifact plus `alder-testkit` at test scope |

The exact future dependency declarations are listed in
[Module reference](reference/modules.md). Do not publish an application against
the current source-composite build.

## What to read next

Read [Data roles and preparation scope](concepts/data-roles.md) before fitting a
component. It explains why a `FeatureMap` cannot feed another fitted
preprocessor. Then use the [Preprocessing guide](guides/preprocessing.md) for a
complete fitted example.

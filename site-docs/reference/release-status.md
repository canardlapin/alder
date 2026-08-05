# Current release status

This is the authoritative status ledger for the current Alder checkout. It was
last inspected on **2026-08-03** in a working tree based on commit `245d3e2`.
It distinguishes files and commands observed locally from evidence that would
require a remote service or a published artifact.

## Current conclusion

Alder is pre-release. The source tree contains the implementation, local test
and documentation gates, compatibility tooling, and loose 100k-row wall-clock
guards. That is not evidence of a published `0.1.0`, remote required checks, a
hosted documentation site, or consumer-visible package metadata.

## Evidence ledger

| Area | Current evidence | Consequence |
| --- | --- | --- |
| Git remote | `origin` is configured as `https://github.com/canardlapin/alder`. | A remote address exists; this inspection did not establish remote CI, branch protection, releases, or packages. |
| Workflows | No `.github` workflow files are present in this checkout. | No repository-defined remote gate can be claimed from source. |
| Publication | The build version is `0.1.0-SNAPSHOT`; the root aggregate and `alder-ridge-gale` are non-publishable. | No stable Alder artifact or immutable compatibility baseline is evidenced here. |
| Dependencies | The build uses development composites for Tessera, Gale, and linop4s; Gale remains a snapshot publication blocker. | A successful source build would not prove stable consumer POMs. |
| Compatibility | MiMa and TASTy-MiMa are configured. Their first-release tasks completed locally with the default empty baseline. | The tools execute, but an empty baseline cannot prove compatibility with a previous Alder release. |
| Coverage | sbt-scoverage 2.4.2 and JVM-oriented exclusions are configured. The application JVM report generated on 2026-08-03 measured 81.68% statement and 67.57% branch coverage. | These module-local diagnostic percentages are not a release threshold or cross-platform coverage claim. |
| Performance | `BaselineSuite` times grouped 10-fold splitting and a standardize-fit-predict-score workflow at 100,000 rows. | The loose elapsed-time ceilings may catch gross complexity regressions. They do not measure allocations or establish portable throughput. |
| Documentation | mdoc, Laika, and Scaladoc completed locally; generated output remains local. | Successful documentation tasks are build evidence, not deployment evidence. |
| Repository metadata | `build.sbt` declares Apache-2.0 metadata. No root license, contributing guide, code of conduct, security policy, or workflow directory is present. | Package metadata does not replace the missing repository documents. |

## Executed for this ledger

The source and repository-state observations above were made locally on
2026-08-03. These commands also completed successfully against the canonical
checkout:

| Command | Local result |
| --- | --- |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 test` | The aggregate JVM, Scala.js, and Scala Native gate completed in 127 seconds; all executed suites passed. Scala Native reported the environment's deprecated clang 15 warning. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 docs/tlSite` | After `docs/clean` removed stale prior-layout outputs, compiled 31 mdoc inputs with zero errors and rendered the canonical 24 HTML documents under `site/target/docs/site`. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 apiDocs` | Generated Scala API documentation for all 14 JVM artifacts. The affected linear-model Scaladoc was rerun after repairing a broken `WeightPolicy` link; the remaining repeated-classpath warning comes from the tool invocation. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 compatibilityCheck` | MiMa reported the expected empty first-release baselines and every configured TASTy compatibility task succeeded. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 benchmarks/test` | `BaselineSuite` passed 2 of 2 guards. The observed suite duration is deliberately not treated as portable benchmark evidence. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 coverage applicationJVM/test coverageReport coverageOff` | Tests passed and generated `application/.jvm/target/scala-3.7.4/scoverage-report/index.html`: 81.68% statement and 67.57% branch coverage. |

No remote CI, deployment, or publication claim follows from these local runs.

## Commands that own the remaining local evidence

```text
sbt -J-Xmx4G -Dsbt.task.cpus=1 test
sbt -J-Xmx4G -Dsbt.task.cpus=1 benchmarks/test
sbt -J-Xmx4G compatibilityCheck
sbt -J-Xmx4G -Dsbt.task.cpus=1 docs/tlSite
sbt -J-Xmx4G -Dsbt.task.cpus=1 apiDocs
sbt -J-Xmx4G -Dsbt.task.cpus=1 coverage applicationJVM/test coverageReport coverageOff
```

Run and report these gates separately. In particular, coverage instrumentation
changes execution, the benchmark suite has different evidence semantics from
the functional tests, and a local generated site is not a deployment.

## Historical context

The [2026-07-26 release-readiness audit](../../docs/reviews/release-readiness-2026-07-26.md)
is retained as a dated snapshot. Its statements about remotes, coverage, and
benchmarks describe that older checkout and must not be used as current status.

# Current release status

This is the authoritative status ledger for the current Alder checkout. It was
last inspected on **2026-08-05** in a working tree based on commit `59c392e`.
It distinguishes files and commands observed locally from evidence that would
require a remote service or a published artifact.

## Current conclusion

Alder is pre-release. The source tree contains the implementation, local test
and documentation gates, compatibility tooling, and loose 100k-row wall-clock
guards. A separate local `alder-sparse-pca` checkout now exercises the public
extension protocol and law artifacts. That is not evidence of a published
`0.1.0`, remote required checks, a hosted documentation site, or
consumer-visible package metadata.

## Evidence ledger

| Area | Current evidence | Consequence |
| --- | --- | --- |
| Git remote | `origin` is configured as `https://github.com/canardlapin/alder`. | A remote address exists; this inspection did not establish remote CI, branch protection, releases, or packages. |
| Workflows | No `.github` workflow files are present in this checkout. | No repository-defined remote gate can be claimed from source. |
| Publication | The build version is `0.1.0-SNAPSHOT`; the root aggregate and `alder-ridge-gale` are non-publishable. | No stable Alder artifact or immutable compatibility baseline is evidenced here. |
| Dependencies | The build uses development composites for Resample4s, Gale, and linop4s; Gale remains a snapshot publication blocker. | A successful source build would not prove stable consumer POMs. |
| Resample4s integration | Alder now loads the sibling `../resample4s` source composite and uses the `resample4s-core` and test-scoped `resample4s-designs` artifacts. The focused adapter suite passed 8 tests on each of JVM, Scala.js, and Scala Native against Resample4s commit `6bc4172`. | This replaces the obsolete dependency identity and proves the current source integration. Resample4s remains `0.1.0-SNAPSHOT`, so this is not stable dependency evidence. |
| Reference plugin | A separate sibling Git repository, `alder-sparse-pca`, contains a cross-platform core and JVM EJML backend. After the Resample4s cutover, its Alder `TransformTests`, projector-invariance, reconstruction, sparsity, and typed-failure tests passed against refreshed local Alder snapshots on 2026-08-05. | This proves the current source SPI can support the required external plugin shape. The plugin has no remote, release, or published coordinates. |
| Compatibility | MiMa and TASTy-MiMa are configured. Their first-release tasks completed locally with the default empty baseline. | The tools execute, but an empty baseline cannot prove compatibility with a previous Alder release. |
| Coverage | sbt-scoverage 2.4.2 and JVM-oriented exclusions are configured. The application JVM report generated on 2026-08-03 measured 81.68% statement and 67.57% branch coverage. | These module-local diagnostic percentages are not a release threshold or cross-platform coverage claim. |
| Performance | `BaselineSuite` times grouped 10-fold splitting and a standardize-fit-predict-score workflow at 100,000 rows. | The loose elapsed-time ceilings may catch gross complexity regressions. They do not measure allocations or establish portable throughput. |
| Documentation | mdoc, Laika, and Scaladoc completed locally; generated output remains local. | Successful documentation tasks are build evidence, not deployment evidence. |
| Repository metadata | `build.sbt` declares Apache-2.0 metadata. No root license, contributing guide, code of conduct, security policy, or workflow directory is present. | Package metadata does not replace the missing repository documents. |

## Executed for this ledger

The source and repository-state observations above were made locally on
2026-08-05. These commands also completed successfully against the canonical
checkout:

| Command | Local result |
| --- | --- |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 test` | After the Resample4s cutover, the aggregate JVM, Scala.js, and Scala Native gate completed in 114 seconds; all executed suites passed. Scala Native reported the environment's deprecated clang 15 warning. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 'dataJVM/testOnly alder.data.Resample4sResamplerSuite'` | The adapter passed 8 tests against the live sibling Resample4s checkout. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 'dataJS/testOnly alder.data.Resample4sResamplerSuite' 'dataNative/testOnly alder.data.Resample4sResamplerSuite'` | Scala.js passed 8 tests. Scala Native compiled the new sources but linked stale incremental objects for deleted pre-migration classes. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 dataNative/clean 'dataNative/testOnly alder.data.Resample4sResamplerSuite'` | The clean Scala Native rerun passed all 8 tests. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 docs/tlSite` | After `docs/clean` removed stale prior-layout outputs, compiled 31 mdoc inputs with zero errors and rendered the canonical 24 HTML documents under `site/target/docs/site`. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 apiDocs` | Generated Scala API documentation for all 14 JVM artifacts. The affected linear-model Scaladoc was rerun after repairing a broken `WeightPolicy` link; the remaining repeated-classpath warning comes from the tool invocation. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 compatibilityCheck` | MiMa reported the expected empty first-release baselines and every configured TASTy compatibility task succeeded. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 benchmarks/test` | `BaselineSuite` passed 2 of 2 guards. The observed suite duration is deliberately not treated as portable benchmark evidence. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 coverage applicationJVM/test coverageReport coverageOff` | Tests passed and generated `application/.jvm/target/scala-3.7.4/scoverage-report/index.html`: 81.68% statement and 67.57% branch coverage. |

The external reference-plugin court used only locally published Alder snapshot
artifacts. From this checkout, `kernel`, `data`, `laws`, and `testkit` were
published to the local Ivy resolver for JVM, Scala.js, and Scala Native. In the
separate plugin checkout:

| Command | Local result |
| --- | --- |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 clean test` | After refreshing the migrated Alder snapshots, `alder-sparse-pca-core` passed four tests on each of JVM, Scala.js, and Scala Native; `alder-sparse-pca-ejml` passed five JVM tests. Scala Native also emitted a transient compiler diagnostic while resolving `NumberFormatException`, then linked and passed; the existing clang 15 deprecation warning remains. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 coreJVM/makePom ejml/makePom` | The core POM retained Alder laws, testkit, MUnit, and Discipline at test scope. The EJML POM retained its solver dependencies at runtime and its law/test dependencies at test scope. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 coreJVM/doc ejml/doc` | API documentation completed for both plugin artifacts; Scaladoc emitted only the repeated-classpath tool warning. |

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

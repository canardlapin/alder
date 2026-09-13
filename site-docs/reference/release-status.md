# Current release status

This is the authoritative status ledger for the current Alder checkout. It was
last inspected on **2026-09-13** after the stage-bound Resample4s adapter and
immutable provider-source closure were qualified. It distinguishes files and
commands observed locally from evidence that would require a remote service, an
independent reviewer, or a published artifact.

## Current conclusion

Alder is pre-release. Ordinary builds now resolve exact immutable source
revisions for Resample4s, Gale, and linop4s; local provider checkouts require
explicit `alder.*.build` overrides. The source tree contains the implementation,
local test and documentation gates, compatibility tooling, and loose 100k-row
wall-clock guards. The separate `alder-sparse-pca` repository has a public
review branch that exercises the public extension protocol and law artifacts.
None of this is evidence of a published stable artifact, remote required checks,
a hosted documentation site, consumer-visible package metadata, or completion
of the owner-reviewed quickstart paraphrase gate.

## Evidence ledger

| Area | Current evidence | Consequence |
| --- | --- | --- |
| Git remote | `origin` is configured as `git@github-canardlapin:canardlapin/alder.git`; migration commit `f26563a` was merged to `main` through [PR #1](https://github.com/canardlapin/alder/pull/1). The evidence correction and the application-surface reopening were merged to `main` through [PR #2](https://github.com/canardlapin/alder/pull/2) as merge commit `2640e0a`. | Both are merged; this inspection did not establish remote CI, branch protection, releases, or packages. |
| Workflows | No `.github` workflow files are present in this checkout. | No repository-defined remote gate can be claimed from source. |
| Publication | The build version is `0.1.0-SNAPSHOT`; the root aggregate and `alder-ridge-gale` are non-publishable. | No stable Alder artifact or immutable compatibility baseline is evidenced here. |
| Dependencies | Ordinary builds pin Resample4s `6bc4172a966c92f1b06811eac64ac2bada9fef9b`, Gale `099832ff15c8a4a8fcf3398c7b779fb4bbc12434`, and linop4s `fec77db060b130b3c609a43d07ff0bfb31088aae` as source dependencies. Explicit `alder.resample4s.build`, `alder.gale.build`, and `alder.linop4s.build` properties select local checkouts for coordinated development. | The ordinary source build is reproducible without sibling-directory discovery. It does not prove stable consumer POMs or Maven Central availability. |
| Resample4s integration | Alder uses the exact Resample4s source revision above and test-scoped `resample4s-designs`. On 2026-09-13, the committed stage-bound `fromDesign` route passed its focused 11-test adapter suite and 2-test failure-rendering suite, followed by the complete affected-module suites on JVM, Scala.js, and Scala Native. | This proves the adapter binds exact-once designs to Alder's normalized child-stage seed without weakening the strict precompiled route. Resample4s remains pre-release, so downstream admission must pin its exact revision. |
| Reference plugin | The public [`canardlapin/alder-sparse-pca`](https://github.com/canardlapin/alder-sparse-pca) repository contains an empty `main` review base. Commit `4237a30` carries the cross-platform core, JVM EJML backend, and Apache-2.0 license on `agent/add-sparse-pca-reference-plugin` in [draft PR #1](https://github.com/canardlapin/alder-sparse-pca/pull/1). After the Resample4s cutover, its Alder `TransformTests`, projector-invariance, reconstruction, sparsity, and typed-failure tests passed against refreshed local Alder snapshots on 2026-08-05. | This proves the current source SPI can support the required external plugin shape and records remote review evidence. The plugin still has no remote CI receipt, merged implementation, release, or published coordinates. |
| Application surface | The ordinary workflow now uses direct `learnWith`, named policy and experiment arguments, a multi-row fractional validation split, direct lifecycle prediction, typed reports, and explicit selection on routes that cross that boundary. The external `com.example` fixture compiles the documented quickstart surface and checks that Alder-owned rows, splits, and receipts cannot be forged. | The implementation and automated semantic gates are complete locally. The application surface is not fully frozen until the independent Scala-reader paraphrase record required by gate 12 is attached. |
| Compatibility | MiMa and TASTy-MiMa are configured. Their first-release tasks completed locally with the default empty baseline. | The tools execute, but an empty baseline cannot prove compatibility with a previous Alder release. |
| Coverage | sbt-scoverage 2.4.2 and JVM-oriented exclusions are configured. The application JVM report generated on 2026-08-05 measured 82.94% statement and 77.48% branch coverage after the report and failure-rendering additions. | These module-local diagnostic percentages are not a release threshold or cross-platform coverage claim. |
| Performance | `BaselineSuite` times grouped 10-fold splitting and a standardize-fit-predict-score workflow at 100,000 rows. | The loose elapsed-time ceilings may catch gross complexity regressions. They do not measure allocations or establish portable throughput. |
| Documentation | mdoc compiled 31 inputs with zero errors, Laika rendered 25 HTML documents, and Scaladoc completed for all 14 JVM artifacts; generated output remains local. | Successful documentation tasks are build evidence, not deployment evidence. |
| Repository metadata | `build.sbt` declares Apache-2.0 metadata. No root license, contributing guide, code of conduct, security policy, or workflow directory is present. | Package metadata does not replace the missing repository documents. |

## Executed for this ledger

The source and repository-state observations above were made locally on
2026-08-05. These commands also completed successfully against the canonical
checkout:

| Command | Local result |
| --- | --- |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 test` | On the final application-surface tree, the aggregate JVM, Scala.js, and Scala Native gate completed in 107 seconds; all executed suites passed, including the external consumer and 100k-row guards. Scala Native reported the environment's deprecated clang 15 warning. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 'dataJVM/testOnly alder.data.Resample4sResamplerSuite'` | The adapter passed 8 tests against the live sibling Resample4s checkout. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 'dataJS/testOnly alder.data.Resample4sResamplerSuite' 'dataNative/testOnly alder.data.Resample4sResamplerSuite'` | Scala.js passed 8 tests. Scala Native compiled the new sources but linked stale incremental objects for deleted pre-migration classes. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 dataNative/clean 'dataNative/testOnly alder.data.Resample4sResamplerSuite'` | The clean Scala Native rerun passed all 8 tests. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 docs/tlSite` | Compiled 31 mdoc inputs with zero errors and rendered the canonical 25 HTML documents under `site/target/docs/site`. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 apiDocs` | Generated Scala API documentation for all 14 JVM artifacts. The affected linear-model Scaladoc was rerun after repairing a broken `WeightPolicy` link; the remaining repeated-classpath warning comes from the tool invocation. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 compatibilityCheck` | MiMa reported the expected empty first-release baselines and every configured TASTy compatibility task succeeded. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 benchmarks/test` | `BaselineSuite` passed 2 of 2 guards in 2.001 seconds. The observed suite duration is deliberately not treated as portable benchmark evidence. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 coverage applicationJVM/test coverageReport coverageOff` | All 39 application tests passed and generated `application/.jvm/target/scala-3.7.4/scoverage-report/index.html`: 82.94% statement and 77.48% branch coverage. Expected no-data warnings were emitted for aggregate modules not exercised by this module-local run. |

The following focused candidate checks were run on 2026-09-12 and repeated
after formatting on 2026-09-13. They do not amend the older aggregate-release
evidence above:

| Command | Local result |
| --- | --- |
| `sbt -Dsbt.supershell=false 'dataJVM/testOnly alder.data.Resample4sResamplerSuite' 'applicationJVM/testOnly alder.application.ExperimentFailureRenderingSuite'` | Passed 11 adapter tests and 2 rendering tests. The adapter suite was rerun after its final property and parenthesization additions. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 -Dsbt.supershell=false 'dataJS/testOnly alder.data.Resample4sResamplerSuite' 'applicationJS/testOnly alder.application.ExperimentFailureRenderingSuite'` | Passed 11 adapter tests and 2 rendering tests. The adapter suite was rerun after its final property and parenthesization additions. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 -Dsbt.supershell=false 'dataNative/testOnly alder.data.Resample4sResamplerSuite' 'applicationNative/testOnly alder.application.ExperimentFailureRenderingSuite'` | Passed 11 adapter tests and 2 rendering tests; the adapter suite was rerun after its final property and parenthesization additions, and the environment repeated its existing deprecated clang 15 warning. |

The immutable provider-source closure was then tested from a clone with no
sibling provider directories:

| Command | Local result |
| --- | --- |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 -Dsbt.supershell=false fmtCheck compatibilityCheck test` | Resolved the three exact Git revisions above; formatting and every configured MiMa/TASTy-MiMa task passed. The aggregate tests passed on JVM, Scala.js, and Scala Native, including data 64/64 on every platform and application 39/39 on JVM plus 38/38 on Scala.js and Native. Scala Native repeated the existing deprecated clang 15 warning. |

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

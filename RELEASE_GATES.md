# Interface acceptance gates

These twelve gates are the Phase 3 release-readiness checklist for Alder 0.1.
A release review must not claim freeze while any gate lacks current evidence.

| # | Gate | Owner evidence | Command / location |
|---|---|---|---|
| 1 | README first workflow: one curated import, direct `learnWith`, named experiment arguments, meaningful multi-row validation, a size budget targeting roughly twelve non-data lines and enforced at a ceiling of twenty, and exact identity with compiled source | `README.md`, `site-docs/learn/workflow.md`, semantic quickstart source contract | `sbt -J-Xmx4G -Dsbt.task.cpus=1 quickstartJVM/test docs/tlSite` |
| 2 | Proof and representation vocabulary absent on the common path (`Id`, `Unit`, `Prepared`, `Use`, `FitContext`, `PhaseSeeds`, `EvaluationSources`, `.artifact`); tests do not require exact provisional constructor fragments | canonical Learn source + semantic quickstart source contract | `sbt -J-Xmx4G -Dsbt.task.cpus=1 quickstartJVM/test` |
| 3 | Mixed Double/Int standardize+fit with explicit zero-variance policy, penalty, and real backend | Learn workflow `House` + cross-platform quickstart suite | `sbt -J-Xmx4G -Dsbt.task.cpus=1 docs/tlSite quickstartJVM/test quickstartJS/test quickstartNative/test` |
| 4 | External `com.example` plugin Transform+Learner | `consumer-fixture` module | `sbt -J-Xmx4G -Dsbt.task.cpus=1 consumerFixtureJVM/test consumerFixtureJS/test consumerFixtureNative/test` |
| 5 | Validation cannot authorize different-config refit, and every route crossing selection names its policy in source | `ApplicationLifecycleSuite` candidate-audit and route-runner tests | `sbt -J-Xmx4G -Dsbt.task.cpus=1 applicationJVM/test` |
| 6 | Feature schema/name/size cannot contradict; feature view / weight / group / named function changes retain the required audit identity | data/preprocess/application invariant and audit suites | `sbt -J-Xmx4G -Dsbt.task.cpus=1 dataJVM/test preprocessJVM/test applicationJVM/test` |
| 7 | Lifecycle results delegate whole-workflow `predict` / `predictAll`; explicit audited `LearnedWith.terminalModel` still focuses transformed-feature coordinates | application lifecycle + `RidgeCapabilitiesSuite` + kernel composition tests | `sbt -J-Xmx4G -Dsbt.task.cpus=1 applicationJVM/test modelsLinearJVM/test lawsJVM/test` |
| 8 | Reporting Metric can evaluate but cannot select; ObjectiveMetric can | application compile-negatives | `sbt -J-Xmx4G -Dsbt.task.cpus=1 applicationJVM/test` |
| 9 | First diagnostic names the user stage, role, field, or policy for invalid scalar configuration, split, missing features, reporting-only selection, illegal target-aware ordering, and receipt reuse | `ApplicationLifecycleSuite`, `ExperimentRoutesSuite`, `QuickstartSuite`, and external-package `ExternalPluginSuite` assertions | `sbt -J-Xmx4G -Dsbt.task.cpus=1 applicationJVM/test quickstartJVM/test consumerFixtureJVM/test`; full cross-platform `test` before a platform-wide claim |
| 10 | Façade ≡ core for outputs, exact failures, stage paths, audits, derived seeds, lineage, row IDs, and receipts; runtime-class equality alone is insufficient | application and quickstart equivalence suites | `sbt -J-Xmx4G -Dsbt.task.cpus=1 applicationJVM/test quickstartJVM/test` |
| 11 | 100k-row gross-regression guards with loose wall-clock ceilings | `benchmarks/BaselineSuite` | `sbt -J-Xmx4G benchmarks/test` |
| 12 | External public-API consumer compiles from documented modules, and an external Scala reader can paraphrase every first-workflow decision | external-package `consumer-fixture`, `AGENTS.md` recipes, recorded owner evidence linked from Mote | `sbt -J-Xmx4G -Dsbt.task.cpus=1 consumerFixtureJVM/test consumerFixtureJS/test consumerFixtureNative/test`; owner-reviewed reader record |

## Local release gate bundle

```text
sbt -J-Xmx4G -Dsbt.task.cpus=1 fmtCheck
sbt -J-Xmx4G -Dsbt.task.cpus=1 test
sbt -J-Xmx4G -Dsbt.task.cpus=1 benchmarks/test
sbt -J-Xmx4G -Dsbt.task.cpus=1 coverage applicationJVM/test coverageReport coverageOff
sbt -J-Xmx4G compatibilityCheck
sbt -J-Xmx4G -Dsbt.task.cpus=1 docs/tlSite
sbt -J-Xmx4G -Dsbt.task.cpus=1 apiDocs
git diff --check
```

Gate 11 does not measure allocations or establish portable throughput. It can
catch gross complexity regressions in the two covered paths; its elapsed-time
ceilings are intentionally loose and machine-dependent.

Do not describe configured checks as executed checks, local results as remote
CI, or generated local documentation as a deployment.

Gate 12 includes an owner-only human evidence item. A passing build cannot be
substituted for an unrun reader exercise, and the application surface is not
fully frozen until that record exists.

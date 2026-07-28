# Interface acceptance gates

These twelve gates are the Phase 3 release-readiness checklist for Alder 0.1.
A release review must not claim freeze while any gate lacks current evidence.

| # | Gate | Owner evidence | Command / location |
|---|---|---|---|
| 1 | README first workflow: one import, ~12 non-data lines | `site-docs/getting-started.md` | `sbt docs/mdoc` |
| 2 | Forbidden names absent on the common path (`Id`, `Unit`, `Prepared`, `Use`, `FitContext`, `PhaseSeeds`, `EvaluationSources`) | getting-started source review + `ForbiddenNamesSuite` | `sbt quickstartJVM/test` |
| 3 | Mixed Double/Int standardize+fit | getting-started `House` + quickstart suite | `sbt docs/mdoc` / `quickstartJVM/test` |
| 4 | External `com.example` plugin Transform+Learner | `consumer-fixture` module | `sbt consumerFixtureJVM/test` |
| 5 | Validation cannot authorize different-config refit | `ApplicationLifecycleSuite` candidate-audit test | `sbt applicationJVM/test` |
| 6 | Feature view / weight / group / named function change audit identity | preprocess/application audit suites | `sbt preprocessJVM/test applicationJVM/test` |
| 7 | `predict` / `predictAll` / `terminal` / `audit` without composition pattern-match | `RidgeCapabilitiesSuite` + kernel extensions | `sbt modelsLinearJVM/test` |
| 8 | Reporting Metric can evaluate but cannot select; ObjectiveMetric can | application compile-negatives | `sbt applicationJVM/test` |
| 9 | First diagnostic names user stage/role/field/policy | lifecycle and consumer-fixture failures | targeted suite assertions |
| 10 | Façade ≡ core observational equivalence | `QuickstartSuite` Blueprint expansion test | `sbt quickstartJVM/test` |
| 11 | 100k-row time/allocation baselines | `benchmarks/BaselineSuite` | `sbt -J-Xmx4G benchmarks/test` |
| 12 | External model package depends only on documented SPI modules | `AGENTS.md` dependency recipes + consumer-fixture deps | review `build.sbt` / plugin recipes |

## Local release gate bundle

```text
sbt -J-Xmx4G -Dsbt.task.cpus=1 test
sbt -J-Xmx4G -Dsbt.task.cpus=1 benchmarks/test
sbt -J-Xmx4G compatibilityCheck
sbt -J-Xmx4G -Dsbt.task.cpus=1 docs/tlSite
sbt -J-Xmx4G -Dsbt.task.cpus=1 apiDocs
```

Do not describe configured checks as executed checks, or local results as remote CI.

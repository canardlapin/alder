# Interface acceptance gates

These twelve gates are the Phase 3 release-readiness checklist for Alder 0.1.
A release review must not claim freeze while any gate lacks current evidence.

| # | Gate | Owner evidence | Command / location |
|---|---|---|---|
| 1 | README first workflow: one import, at most 12 non-data lines, exact identity with compiled source | `README.md`, `site-docs/learn/workflow.md`, `ForbiddenNamesSuite` | `sbt quickstartJVM/test docs/tlSite` |
| 2 | Forbidden names absent on the common path (`Id`, `Unit`, `Prepared`, `Use`, `FitContext`, `PhaseSeeds`, `EvaluationSources`) | canonical Learn source + `ForbiddenNamesSuite` | `sbt quickstartJVM/test` |
| 3 | Mixed Double/Int standardize+fit | Learn workflow `House` + cross-platform quickstart suite | `sbt docs/tlSite quickstartJVM/test quickstartJS/test quickstartNative/test` |
| 4 | External `com.example` plugin Transform+Learner | `consumer-fixture` module | `sbt consumerFixtureJVM/test` |
| 5 | Validation cannot authorize different-config refit | `ApplicationLifecycleSuite` candidate-audit test | `sbt applicationJVM/test` |
| 6 | Feature view / weight / group / named function change audit identity | preprocess/application audit suites | `sbt preprocessJVM/test applicationJVM/test` |
| 7 | Whole-workflow `predict` / `predictAll`; explicit audited `LearnedWith.terminalModel` focus in transformed-feature coordinates | `RidgeCapabilitiesSuite` + kernel composition tests | `sbt modelsLinearJVM/test lawsJVM/test` |
| 8 | Reporting Metric can evaluate but cannot select; ObjectiveMetric can | application compile-negatives | `sbt applicationJVM/test` |
| 9 | First diagnostic names user stage/role/field/policy | lifecycle and consumer-fixture failures | targeted suite assertions |
| 10 | Façade ≡ core observational equivalence | `QuickstartSuite` Blueprint expansion test | `sbt quickstartJVM/test` |
| 11 | 100k-row gross-regression guards with loose wall-clock ceilings | `benchmarks/BaselineSuite` | `sbt -J-Xmx4G benchmarks/test` |
| 12 | External model package depends only on documented SPI modules | `AGENTS.md` dependency recipes + consumer-fixture deps | review `build.sbt` / plugin recipes |

## Local release gate bundle

```text
sbt -J-Xmx4G -Dsbt.task.cpus=1 test
sbt -J-Xmx4G -Dsbt.task.cpus=1 benchmarks/test
sbt -J-Xmx4G compatibilityCheck
sbt -J-Xmx4G -Dsbt.task.cpus=1 docs/tlSite
sbt -J-Xmx4G -Dsbt.task.cpus=1 apiDocs
```

Gate 11 does not measure allocations or establish portable throughput. It can
catch gross complexity regressions in the two covered paths; its elapsed-time
ceilings are intentionally loose and machine-dependent.

Do not describe configured checks as executed checks, local results as remote
CI, or generated local documentation as a deployment.

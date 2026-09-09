# Refined application surface: local freeze evidence

Status: automated and local owner evidence complete; independent reader
evidence open.

This record closes the implementation portion of Mote epic
`alder-appsurface-epic`. It does not declare the application API fully frozen.
Gate 12 in `RELEASE_GATES.md` still requires an owner-reviewed external Scala
reader to paraphrase every decision in the first workflow.

## Six work packages

| Mote package | Result |
| --- | --- |
| `alder-appsurface-governance` | Reopened the application spelling without reopening the kernel, lifecycle authority, or evidence model. |
| `alder-appsurface-quickstart` | Made direct `learnWith`, named arguments, explicit policies, and a multi-row fractional holdout the ordinary path; demoted `Blueprint` to optional staged construction. |
| `alder-appsurface-lifecycle` | Added direct `predict` / `predictAll`, typed report projections, stable edge rendering, and explicit TVT selection. |
| `alder-appsurface-construction` | Made validated feature schemas authoritative and introduced policy/domain vocabulary for zero variance and ridge scalar configuration. |
| `alder-appsurface-docs` | Reordered the learning path around validation, inspection, prediction, deliberate refit, and audit; separated application use from extension-author material. |
| `alder-appsurface-freeze` | Replaced spelling locks and shallow checks with semantic equivalence, diagnostic, external-package, cross-platform, and build evidence. The human reader item remains open. |

## Resulting first-use contract

The README workflow now exposes the decisions a reader must understand:

- derive numeric coordinates and an application schema;
- standardize training data with `ZeroVariance.AsZero`;
- fit ridge with a validated non-negative penalty and the explicit LSQR
  backend;
- reserve one quarter of twelve observations, producing three held-out rows;
- evaluate RMSE under an explicit seed and plan identity; and
- stop at validation, before selection or refit.

`Blueprint` remains a lawful, type-preserving expansion aid. It is no longer a
required first-user noun. `Coordinates` and `Schema` remain separate. No
universal estimator, hidden solver, implicit seed, mutable pipeline, or
automatic lifecycle transition was introduced.

## Semantic acceptance

The quickstart court compares direct composition with the `Blueprint`
expansion for:

- held-out row IDs, predictions, and score;
- exact prediction and fit failures with stage paths;
- audit plan, data/schema fingerprints, derived seeds, numerics, lineage,
  component descriptors, backend fingerprints, and refit evidence through
  `AuditSnapshot`;
- evaluation and selection receipt identities;
- single-row and whole-data prediction; and
- refitted audit and prediction behavior.

The first-workflow source contract continues to require one curated import,
README/mdoc identity, absence of proof and representation vocabulary, explicit
scientific decisions, and a rough size budget. It no longer freezes exact
constructor fragments. It also rejects selection, refit, test, or deployment
transitions inside the first validation example.

## Diagnostics and external ownership

Compiler fixtures require recognizable user concepts for invalid ridge
penalties, missing `FeatureView` derivation, illegal processing after
`LearnerReady` preparation, incomplete cross-fitting without a
`CompleteResampler`, reporting-only selection without an `ObjectiveMetric`,
implicit route selection, and opening test data from validation.

An audit of the compile-negative fixtures found that snippets beginning with a
`package` declaration were failing in the compiler-test wrapper before reaching
the intended constraint. Those declarations were removed. Privacy-sensitive
checks now run in the real external package `com.example.alderplugin`, where
they prove that consumers cannot construct or inspect `Prepared` rows or forge
`NonEmptyData`, validation splits, prediction receipts, or evaluation
receipts. The same fixture compiles and runs the documented quickstart surface
through the source composite. It does not prove published-artifact
availability.

## Type-discipline reconciliation

The application diff was reviewed against the Scala type-discipline checklist
and is clean:

- report route and partition shape are ADTs, not strings or sentinels;
- plan, backend, and component summaries are derived from retained audit and
  component descriptors rather than duplicated state;
- typed experiment failures remain available for matching, while rendering is
  an explicit edge operation;
- built-in data, metric, selection, refit, receipt, and fingerprint failures
  have exhaustive stable rendering; application-owned leaf errors may provide
  explicit renderers; and
- no production suppression, unchecked cast, null sentinel, thrown expected
  failure, or widened `Any` channel was added.

All touched public examples and tests use the new vocabulary. Historical
review notes retain old spellings as dated evidence and are not normative.

## Local verification receipt

Executed on 2026-08-05 in the current working tree:

| Command | Result |
| --- | --- |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 test` | Passed in 107 seconds across JVM, Scala.js, and Scala Native, including the external consumer fixture and benchmark aggregate. The host emitted its existing clang 15 deprecation warning. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 benchmarks/test` | `BaselineSuite`: 2 of 2 passed; suite duration 2.001 seconds. This is a local gross-regression guard, not portable throughput evidence. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 coverage applicationJVM/test coverageReport coverageOff` | 39 of 39 application tests passed; 82.94% statement and 77.48% branch coverage. Coverage is module-local diagnostic evidence, not a release threshold. |
| `sbt -J-Xmx4G compatibilityCheck` | All configured TASTy checks passed. MiMa ran with the expected empty first-release baseline, so no prior-release binary compatibility is claimed. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 docs/tlSite` | 31 mdoc inputs compiled with zero errors; 25 HTML documents rendered. This is local build evidence, not deployment. |
| `sbt -J-Xmx4G -Dsbt.task.cpus=1 apiDocs` | Scaladoc completed for all 14 JVM artifacts. The tool emitted its existing repeated-classpath warning. |
| `git diff --check` | Passed. |
| `jq empty PRD.json` | Passed; PRD SHA-256: `7aa1852a1d66aa11bc3fb3f0aef6093a3dfd29d9b7e973dcbed6987cb34e3e02`. |

The repository's attempted `fmtCheck` alias resolves to an unavailable
`scalafmtCheckAll` task. No formatting-gate success is claimed; formatting is
not listed among Alder's required local release commands.

## Remaining freeze gate

An independent Scala reader must read the first workflow without explanatory
prose and accurately identify the feature derivation, training-only
standardization, zero-variance policy, ridge penalty and backend, holdout
fraction, metric, seed and identity, and the fact that execution stops before
selection or refit. The owner must attach that record to
`alder-appsurface-freeze` in Mote.

Until then, the implementation is locally verified but the refined application
surface and its epic remain open rather than being called fully frozen.

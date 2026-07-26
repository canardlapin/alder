# Alder 0.1 release-readiness audit

## Conclusion

The Alder implementation is locally coherent on Scala 3.7.4, and its required
aggregate JVM, Scala.js, and Scala Native test gate passed before this audit.
The repository is not ready for a stable publication. Its development build
uses sibling source composites, no Alder 0.1 artifact exists for a real
compatibility comparison, the required separate sparse-PCA reference plugin is
absent, and this checkout has no remote from which to obtain CI or publication
evidence.

These are release blockers, not test failures. Local implementation evidence
and external publication evidence are reported separately below.

## Assurance scorecard

| Assurance dimension | Rating | Evidence and consequence |
| --- | --- | --- |
| ScalaCheck use and generator quality | Present but incomplete | Public generators cover finite and adversarial numeric values; data properties use explicit case counts. There are no custom shrinkers or fixed expensive-case seeds, so failure reduction and replay are not uniformly controlled. |
| Reusable law-test module | Strong | `alder-laws`, `alder-metrics-laws`, and `alder-tune-laws` publish laws against public APIs and cross-build for JVM, Scala.js, and Native. `alder-testkit` publishes protocol fixtures separately. |
| Test framework and Discipline integration | Strong | MUnit Discipline suites execute `RuleSet` values for pipes, transforms, feature maps, cross-fit leakage, learners, codecs, metrics, spaces, erasure, and studies. |
| Typeclass lawfulness and coherence | Present but incomplete | The main algebraic instance is the applicative `Space`; published laws cover its behavior. Capability typeclasses live with the kernel vocabulary. A repository-wide orphan/coherence policy is documented but not mechanically enforced. |
| Backend or provider conformance | Strong locally | Gale QR/Cholesky and linop4s LSQR/CG adapters share typed ridge contracts. Tests cover agreement, analytic answers, malformed data, non-finite values, receipts, termination, and capability-specific behavior. The Gale artifact remains non-publishable. |
| Cross-platform and cross-version CI | Present but incomplete | The Scala 3.7.4 aggregate gate executes JVM, Scala.js, and Native locally. No remote workflow or required-check state exists, and no future-Scala advisory job is configured. |
| Numerical and computational assurance | Strong locally | Ridge tests use analytic OLS/ridge answers, backend agreement, objectives, residual evidence, iteration limits, non-finite policies, and hostile inputs. Metrics test deterministic merging and adversarial floating-point cases. |
| Differential and independent oracles | Strong locally | Dense Gale and matrix-free linop4s implementations are independent engines and are compared on predictions and objectives; analytic small cases provide a second oracle. |
| Failure, convergence, and resource contracts | Strong | Expected failures use ADTs and retain `StagePath`. Solver termination, non-finite values, invalid dimensions, malformed receipts, replay, and one-shot refit authorization are distinct and tested. The code is pure, so cancellation and close semantics do not apply. |
| Work and allocation accounting | Present but incomplete | Iterative backends record iterations, residual evidence, and operator applications in solver receipts. No allocation measurement or equivalent-work benchmark receipt exists. |
| Compiler discipline | Strong | Scala 3.7.4 builds with deprecation, feature, unchecked, unused, value-discard, explicit-nulls, strict-equality, and fatal-warning checks. Compile-negative tests protect role and constructor boundaries. |
| Formatting and semantic rewrites | Missing | No Scalafmt, Scalafix, WartRemover, or equivalent deterministic formatting/rewrite gate is configured. |
| Binary and source compatibility | Present but incomplete | MiMa 1.1.6 and TASTy-MiMa 1.4.0 are configured on publishable JVM projections. The first 0.1.0 has no prior artifact, so the empty comparison is honest but cannot prove release-to-release compatibility. |
| Coverage and mutation signal | Missing | No coverage or mutation tool is configured. The law suites provide behavioral evidence but do not identify unexecuted branches. |
| Benchmark and performance evidence | Missing | There is no JMH or equivalent benchmark module, committed environment receipt, allocation profile, or performance CI gate. No performance claim should be attached to 0.1. |
| Documentation and release evidence | Strong locally | The curated mdoc/Laika guide compiles its examples, and every JVM module has generated Scala 3 API documentation with snippet compilation enabled. Consumer probes, a public site deployment, remote CI, signed publication, and repository release metadata remain absent or unverified. |

## Evidence executed locally

Before the compatibility-tooling edit, the following aggregate gate passed on
Scala 3.7.4:

```text
sbt -J-Xmx4G -Dsbt.task.cpus=1 test
```

It compiled and tested all aggregated JVM, Scala.js, and Scala Native projects.
The only toolchain message outside sbt was a Scala Native clang 15 deprecation
warning.

For the first 0.1.0 release, the compatibility tasks resolve an empty
prior-artifact set:

```text
sbt -J-Xmx4G compatibilityCheck
```

After the documentation and compatibility configuration changes, these local
gates passed on Scala 3.7.4:

```text
sbt -J-Xmx4G -Dsbt.task.cpus=1 docs/tlSite apiDocs
sbt -J-Xmx4G -Dsbt.task.cpus=1 compatibilityCheck test
```

The guide run compiled 23 mdoc inputs and rendered 17 HTML pages. The API task
generated Scaladoc for all 13 JVM artifacts with snippet compilation enabled.
MiMa reported the expected empty first-release baseline, TASTy-MiMa completed
for every publishable JVM artifact, and the aggregate test gate completed on
the JVM, Scala.js, and Scala Native. Scala Native emitted its existing warning
that local clang 15 is older than the recommended clang 16.

After 0.1.0 is published, a real check must name that immutable version:

```text
sbt -J-Xmx4G -Dalder.compatibility.previous=0.1.0 compatibilityCheck
```

## Configured but not externally verified

- Scala 3.7.4 is the only Alder publication baseline.
- The pure shared source is compiled for JVM, Scala.js, and Scala Native.
- MiMa checks JVM classfile compatibility; TASTy-MiMa checks the Scala 3 public
  representation on the JVM projection.
- `alder-ridge-gale` has `publish / skip := true`.
- The root aggregate is not publishable.

Configuration is not proof of a remote required check or a published artifact.
This repository currently has no git remote, workflow history, release tag, or
repository-hosted package evidence.

## Stable-release blockers

1. Replace development source composites with published, non-snapshot Tessera,
   Gale, and linop4s coordinates for every artifact intended for publication.
   A source composite can compile locally but cannot provide a stable consumer
   POM.
2. Create the sparse-PCA reference plugin in its separate repository. It must
   compile on Scala 3.7.4 and pass the relevant published Alder laws. No such
   checkout or remote was available during this audit.
3. Configure a remote and run the required platform gates there. Record remote
   check URLs separately from local command output.
4. Publish 0.1.0, inspect the repository artifacts and POMs, and then run MiMa
   and TASTy-MiMa against the immutable 0.1.0 baseline for the next release.
5. Add formatting enforcement before accepting unrelated contributor changes.
   Coverage and benchmarks are follow-up assurance work; they do not replace
   the protocol laws.

Until items 1 through 4 are complete, describe Alder as locally implemented,
not released.

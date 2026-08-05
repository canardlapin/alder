# Alder

Alder helps Scala programs fit preprocessing and models without accidentally
training on held-out data or letting a row's own target leak into its features.
It is useful for libraries and applications that want those mistakes rejected
by the compiler instead of discovered after evaluation.

With Alder:

- training, validation, and test data have different types;
- preprocessing that sees targets must prepare training rows out of fold;
- a fitted model keeps the seed, data identity, backend, and fitting steps that
  produced it; and
- a failure identifies the stage that caused it.

The ordinary path is still familiar: define data, split it, compose a workflow,
fit, predict, and score.

## Start here

[Learn Alder through one workflow](learn/workflow.md) standardizes numeric
inputs, fits a ridge model, predicts and scores validation, selects explicitly,
refits on authorized data, predicts from the original input type, and inspects
the audit.

After that, choose a task in [How-to](how-to/README.md), read
[Understand](understand/README.md) for the reason behind the type boundaries,
or use [Extend](extend/README.md) when implementing a component.

## Current status

Alder uses Scala 3.7.4. Its pure modules are tested on the JVM, Scala.js, and
Scala Native. This documentation project executes examples on the JVM; it does
not substitute for the repository's cross-platform test gate.

Alder 0.1.0 is not published yet. The source build currently uses sibling
development checkouts for Gale and linop4s, plus either a Resample4s sibling or a
locally published Resample4s snapshot. The guide therefore does not present
unreleased Maven coordinates as an installation method. See
[Build Alder from source](reference/building-from-source.md) to try this
checkout. The [current release-status ledger](reference/release-status.md)
separates inspected configuration, executed local gates, and evidence that is
still external or missing.

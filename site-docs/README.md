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

[Fit your first workflow](getting-started.md) standardizes numeric inputs, fits
a ridge model, predicts every validation row from the original input type, and
computes RMSE. The example uses the application-facing conveniences while
preserving Alder's typed errors and audit.

After that, choose a task in [Guides](guides/README.md). Read
[How Alder prevents leakage](concepts/data-roles.md) when you want the reason
behind the type boundaries.

## Current status

Alder uses Scala 3.7.4. Its pure modules are tested on the JVM, Scala.js, and
Scala Native. This documentation project executes examples on the JVM; it does
not substitute for the repository's cross-platform test gate.

Alder 0.1.0 is not published yet. The source build currently uses sibling
development checkouts for Gale and linop4s, plus either a Tessera sibling or a
locally published Tessera snapshot. The guide therefore does not present
unreleased Maven coordinates as an installation method. See
[Build Alder from source](reference/building-from-source.md) to try this
checkout.

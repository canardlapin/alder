# Alder

Alder is a typed fitting and evaluation protocol for Scala 3. It helps library
authors build preprocessing and learning workflows without hiding when targets
were observed, which rows trained a stage, or why a computation failed.

Alder distinguishes three component roles:

- a `Transform` fits without seeing targets;
- a `FeatureMap` may use targets but must prepare each training row without its
  own target; and
- a `Learner` is terminal, so its in-sample predictions cannot train a later
  stage.

The types enforce the legal compositions. Fitted values also retain their data
fingerprint, stage path, derived seed, numerical mode, backend, and preparation
lineage in an immutable audit.

## A first result

Metrics are a small entry point because they need no fitting context. This
example computes root mean squared error with Alder's deterministic
superaccumulator:

```scala mdoc
import alder.kernel.Scored
import alder.metrics.*

val scored = Vector(
  Scored(3.0, 2.5, ()),
  Scored(4.0, 4.5, ())
)

RegressionMetrics
  .rmse[Unit]
  .evaluate(scored)
  .map(_.value)
```

Continue with [Getting started](getting-started.md) to build the current
pre-release source tree. Then read [Core concepts](concepts/README.md) before
composing fitted stages.

## Current status

Alder uses Scala 3.7.4. Its pure modules are tested on the JVM, Scala.js, and
Scala Native. This documentation project executes examples on the JVM; it does
not substitute for the repository's cross-platform test gate.

Alder 0.1.0 is not published yet. The source build currently uses sibling
development checkouts for Tessera, Gale, and linop4s. The guide therefore does
not present unreleased Maven coordinates as an installation method.

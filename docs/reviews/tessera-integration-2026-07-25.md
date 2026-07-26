# Tessera integration review

Date: 2026-07-25
Tracker: `bd-01KYE715H8SS2HVVVB971BRHNZ`

## Outcome

`alder-data` now interprets Tessera ordinal plans through
`TesseraResampler`. Its runtime dependency is `tessera-core`; the catalogue
artifact `tessera-designs` is present only in the integration test
configuration.

The total completeness factory accepts only:

```scala
Plan[Split[Selection], Coverage.ExactOnce]
```

It constructs `CompleteResampler` directly and does not call Alder's legacy
runtime coverage validator. Holdout, Bootstrap, and repeated exact plans are
compile-negative. Split-time errors are limited to facts that cannot be known
until the bound Alder value is supplied: population size, population
fingerprint, and seed.

## Boundary correction

The spike found a real mismatch before either surface was frozen. Tessera
`Coverage.Exact` means exact coverage within each repeat. Alder D19 means each
input `RowId` is assessed exactly once over the complete cross-fitting plan. A
two-repeat K-fold satisfies the former and violates the latter.

Tessera therefore added `Coverage.ExactOnce <: Coverage.Exact`. One-repeat
K-fold, LOO, LOGO, and delete-one designs mint the stronger proof; `.repeat`
drops it to per-repeat `Exact`. Alder's factory accepts only `ExactOnce`.

## Interpretation and audit

- Alder privately binds each Tessera ordinal to the corresponding ordered
  `RowId`.
- `GroupOf` metadata is coded into Tessera `Labels` by first population
  ordinal, after which Tessera owns canonical recoding.
- Alder and Tessera seeds map by their shared `Long` value. A bound plan rejects
  a different later Alder seed.
- Analysis and assessment stay protocol partitions of the parent `Train` or
  `Refit` role. Assessment rows are encoded but never passed to a fitting
  signature; retagging them as `Use.Test` would erase the parent role.
- Cross-fit lineage retains an Alder-owned, kernel-neutral rendering of the
  complete Tessera receipt: design and digest algorithms, design, population,
  optional labels, assignment fingerprints, and plan seed.

## Evidence

The integration suite checks exact coverage, disjointness, deterministic
replay, original-order reconstruction, group atomicity, fingerprint policy
tags, binding failures, compile-negative capability boundaries, and
instrumented cross-fit exclusion.

Passing platform commands:

```text
sbt -batch 'dataJVM/testOnly alder.data.TesseraResamplerSuite'
sbt -batch 'dataJS/testOnly alder.data.TesseraResamplerSuite'
sbt -batch 'dataNative/testOnly alder.data.TesseraResamplerSuite'
```

Each platform passed all eight integration tests. The repository-wide gate is
also green when run serially by platform:

```text
sbt -batch \
  'kernelJVM/test' 'lawsJVM/test' 'testkitJVM/test' 'dataJVM/test' \
  'preprocessJVM/test' 'metricsJVM/test' 'metricsLawsJVM/test' \
  'kernelJS/test' 'lawsJS/test' 'testkitJS/test' 'dataJS/test' \
  'preprocessJS/test' 'metricsJS/test' 'metricsLawsJS/test'

sbt -batch \
  'kernelNative/test' 'lawsNative/test' 'testkitNative/test' \
  'dataNative/test' 'preprocessNative/test' 'metricsNative/test' \
  'metricsLawsNative/test'
```

That is 114 passing tests on each platform. A root `sbt test` attempt launched
multiple Native linkers concurrently and exhausted the configured 1 GB sbt
heap; the serial commands above cover the identical aggregate project set with
one linker resident at a time.

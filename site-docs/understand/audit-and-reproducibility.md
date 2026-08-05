# Audit and reproducibility

Every `Trained[A]` pairs the fitted artifact with an immutable `Audit`. The
audit describes how the artifact was produced; it does not claim that the
artifact was deployed, published, or independently validated.

## Recorded evidence

An audit includes:

- normalized plan and schema fingerprints;
- the fitting data fingerprint;
- the root or derived seed;
- the numerical mode;
- component identity, version, and structured parameters;
- backend identity, version, and structured details;
- preparation lineage;
- refit authorization, when present; and
- child audits for composed workflows.

Stage paths and derived seeds use stable logical ordinals. Reparenthesizing an
associative composition does not change leaf stage identity.

Behaviour-changing policies belong in the audit identity. Changing a feature
view, a weight / group / time policy, or a named row function produces a
different component or policy fingerprint. An anonymous `mapOutput` function
does not.

## Fingerprint policies

Every fingerprint names its policy:

- `ContentDigest` identifies the digest algorithm;
- `SourceIdentity` records an external source and version; and
- `Summary` names a declared summary policy.

A digest string without its policy is not an Alder fingerprint.

## Numerical modes

`NumericMode.Deterministic` requests deterministic behavior from a compatible
backend. `FastMath` permits transformations that may change numerical results.
`NonDeterministic` records an explicit reason.

A backend captures its configuration when it is constructed and fingerprints
that configuration. It must reject a fit context whose requested mode conflicts
with the captured mode.

## What the audit does not prove

An audit does not prove that:

- a source fingerprint policy was appropriate for the application;
- an external data source still exists;
- backend results are mathematically correct;
- a model was evaluated on data not represented in the receipt; or
- a local artifact was published.

Use Alder's law suites, backend differential tests, and the receipt-gated
evaluation protocol for those distinct obligations.

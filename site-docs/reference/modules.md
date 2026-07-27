# Modules

## Runtime modules

| Module | Responsibility | Main dependencies |
| --- | --- | --- |
| `alder-kernel` | fitting, composition, roles, failures, audit, capabilities | Cats Core |
| `alder-data` | immutable data, typed splits, resampling, prediction evidence, private promotion authority | kernel, Tessera integration |
| `alder-preprocess` | target-blind standardization | kernel, data |
| `alder-metrics` | deterministic streaming metrics | kernel |
| `alder-application` | scored evaluation, selection receipts, and receipt-gated refit | kernel, data, metrics |
| `alder-models-linear` | ridge vocabulary and backend-neutral learner | kernel, data |
| `alder-ridge-linop4s` | matrix-free LSQR and CG ridge backend | models-linear, linop4s |
| `alder-ridge-gale` | dense QR and Cholesky ridge backend | models-linear, Gale |
| `alder-tune` | applicative spaces, deterministic search, Train-only studies | kernel, data, metrics |
| `alder-codec` | versioned artifact codecs and exact chain derivation | kernel |

`alder-ridge-gale` is non-publishable until Gale has a stable compatible
release.

## Test-support modules

| Module | Responsibility |
| --- | --- |
| `alder-laws` | pipe, transform, feature-map, learner, leakage, and codec laws |
| `alder-metrics-laws` | metric accumulation and permutation laws |
| `alder-tune-laws` | space, erasure, and study laws |
| `alder-testkit` | public generators, tolerances, and protocol fixtures |

Laws belong at test scope in downstream builds. They are published as ordinary
modules because plugin authors instantiate their Discipline suites.

## Extension dependency shape

Keep algorithm identity separate from a numerical backend:

```text
plugin-core ───────→ alder-kernel
      │
      └────────────→ alder-data       only when it owns resampling integration

application ───────→ alder-data
      └────────────→ alder-metrics

plugin-backend ────→ plugin-core
      ├────────────→ solver library
      └─ test ─────→ Alder laws and testkit
```

The core artifact must not acquire a solver dependency through tests or
convenience constructors.

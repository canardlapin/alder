# Alder API

Alder helps Scala programs fit preprocessing and models without training on
held-out data or leaking a row's own target into its features. The API uses
different data types for fitting and evaluation, and every fitted model carries
an audit of the run that produced it.

Start with these packages:

- `alder.data` provides application-facing fit setup, immutable data, typed
  splits, resampling, and cross-fitting.
- `alder.kernel` defines composition, data roles, failures, and audit contracts.
- `alder.preprocess` provides target-blind preprocessing.
- `alder.metrics` provides deterministic streaming metrics.
- `alder.application` combines prediction, scoring, selection, and
  receipt-gated refit.
- `alder.models.linear` defines backend-neutral ridge contracts.
- `alder.tune` provides applicative search spaces and Train-only studies.
- `alder.codec` provides versioned artifact codecs.

The repository guide explains complete workflows and extension rules. This API
reference documents individual symbols and their contracts.

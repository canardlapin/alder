# Alder API

Alder is a typed fitting and evaluation protocol for Scala 3. Its public API
separates target-blind transforms, leakage-aware feature maps, and terminal
learners. Dataset roles, row identity, preparation scope, failures, seeds, and
audit records remain explicit through composition.

Start with these packages:

- `alder.kernel` defines fitting, composition, data roles, failures, and audit
  contracts.
- `alder.data` provides immutable data, typed splits, resampling, cross-fitting,
  and receipt-gated refitting.
- `alder.preprocess` provides target-blind preprocessing.
- `alder.metrics` provides deterministic streaming metrics.
- `alder.models.linear` defines backend-neutral ridge contracts.
- `alder.tune` provides applicative search spaces and Train-only studies.
- `alder.codec` provides versioned artifact codecs.

The repository guide explains complete workflows and extension rules. This API
reference documents individual symbols and their contracts.

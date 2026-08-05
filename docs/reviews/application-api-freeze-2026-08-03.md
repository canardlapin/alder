# Application API freeze decision

This record resolves the application-surface proposals raised while revising
the Alder guide. `PRD.json` remains authoritative for semantic boundaries.

## Adopt

- Use `run` as the sole one-shot verb on each defined Experiment route. The
  receiver type fixes the exact result: validation returns `Validated`, while
  train/validation/test and precommitted holdout return their distinct `Tested`
  states. This is not a generic runner controlled by a runtime option.
- Use named arguments in the first workflow so source identity, plan identity,
  seed, blueprint, and metric remain legible.

## Retain

- Keep `select(SingleCandidate)` explicit. Selection changes authority by
  permitting validation-backed refit; the PRD ratifies that named policy.
- Keep `DataFingerprint` and `PlanFingerprint` distinct and policy-tagged. The
  existing String conveniences explicitly construct external identities.
- Keep `Coordinates` and `Schema` separate: one describes numeric coordinates,
  while the other identifies the audited input schema.
- Keep `Validation.fraction`; no second percentage or split-fraction API is
  needed.

## Reject

- Do not add `accept` as an implicit single-candidate selection policy.
- Do not add a string-only audit summary or merge distinct identity types into
  a generic wrapper.
- Do not add aliases for the previous `runToValidated` or `runToTested` names.
  Alder is pre-0.1, and one spelling is easier to learn and freeze.

## Defer

- A separate `ExperimentId` has no demonstrated semantics beyond the current
  policy-tagged plan and data identities.
- `RunConfig`, workflow-wide checked syntax, and percentage syntax would add
  surface without closing a current safety gap.
- Experiment-level `NumericMode` is deferred until the state machine carries it
  through every candidate, selected, and deployment fit. Advertising a field
  while the interpreter remains deterministically fixed would be misleading.

These decisions do not hide reporting-versus-objective metric capability,
selection policy, held-out roles, or the exact learner retained through the
lifecycle.

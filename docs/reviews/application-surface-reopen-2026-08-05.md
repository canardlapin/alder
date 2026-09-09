# Application surface reopening decision

Status: adopted for implementation before Alder 0.1.

This decision follows the first complete implementation of `Blueprint`,
`Experiment`, `alder.quickstart`, and the executable guide. It does not reopen
the kernel or the experiment lifecycle. It corrects the language through which
application authors first encounter those semantics.

The governing implementation epic is `alder-appsurface-epic` in Mote. The
earlier approachability epic remains historical evidence and now depends on
this successor acceptance work.

## Finding

The kernel is stronger than the current quickstart. The first workflow places
scientific intent, numerical policy, reproducibility, construction scaffolding,
checked configuration, and lifecycle execution at one syntactic level. A
reader must translate `emitZero`, `lsqr`, `Validation.rows`, `Blueprint`, a
positional `Experiment.validation` call, and `.run` before interpreting the
experiment.

The problem is identifier-to-meaning distance, not excessive type discipline.
Comments may explain why a decision matters; they should not be required to
translate what a public identifier means.

## Semantics that remain fixed

- `Transform`, `FeatureMap`, `FoldEncoder`, and `Learner` remain the only
  component protocols.
- `Preparation.Reusable` and `Preparation.LearnerReady` retain their legal
  consumers; target-aware preparation remains cross-fitted.
- Fitted artifacts retain exact errors, stage attribution, audit lineage,
  deterministic seeds, row identity, and receipt authority.
- `Experiment` retains role-typed validation, selection, refit, final test, and
  deployment transitions.
- Selection remains explicit. `select(SingleCandidate)` is retained and no
  `accept` alias is introduced in this tranche.
- Numerical backends, zero-variance behavior, data and plan identity, split
  policy, seed, and metric remain visible decisions.
- `Coordinates` and `Schema` remain distinct contracts.

## Application spelling reopened

1. Direct `transform.learnWith(learner)` or
   `featureMap.learnWith(learner)` composition is canonical for ordinary
   workflows. `Blueprint` remains an optional staged construction aid for
   complex and target-aware composition; it is not a required first-user noun.
2. The first workflow uses named arguments, a fractional multi-row validation
   split, and policy values in grammatical positions. Exact constructor names
   remain provisional until compile diagnostics and reader evidence pass.
3. Lifecycle results expose direct `predict` and `predictAll` delegation to the
   exact retained `Trained` value. Advanced model and artifact access remains.
4. A complete train/validation/test runner cannot choose `SingleCandidate`
   behind a no-argument `run`; the selection policy must appear in source.
5. `FeatureSchema` is the existing validated authority for coordinate names
   and size. No duplicate `CoordinateLayout` abstraction is introduced.
6. Typed report values may project route, partitions, score, learner/backend,
   identity, seed, and audit evidence. Rendering is a separate edge operation;
   neither reports nor renderers replace the audit or typed error ADTs.

## Rejected and deferred directions

- Reject a mutable universal estimator, runtime stage list, reflective clone,
  string parameter map, ambient backend, implicit seed, or automatic
  validation-to-refit transition.
- Reject a universal application error wrapper and string-only audit summary.
- Reject `accept` while the explicit one-candidate selection policy remains
  clear and semantically accurate.
- Defer a combined `AlderFeatures` derivation until it demonstrates value
  beyond saving one derivation name.
- Defer typed `DatasetId`, `PlanId`, or a combined run value until named
  arguments have been evaluated and a remaining category error is shown.
- Gate a smaller tuning façade on removing the exact evaluation-error erasure
  inside `Search`; convenience cannot make typed trial failures less exact.

## Acceptance evidence

The refined surface is not frozen by exact source fragments or line count.
Acceptance requires:

- the README block to be identical to compiled mdoc source;
- forbidden proof and representation vocabulary to remain absent;
- JVM, Scala.js, and Scala Native execution;
- direct-result prediction and explicit route selection;
- façade/core equivalence for behavior, exact failures, stage paths, audits,
  seeds, lineage, row IDs, and receipts;
- compiler diagnostics for invalid scalar configuration, split errors, missing
  feature derivation, reporting-only selection, illegal target-aware ordering,
  and receipt reuse;
- an external public-API consumer fixture; and
- recorded external Scala-reader paraphrase evidence.

The roughly twelve-line target remains a regression signal. It cannot override
clarity, an explicit scientific decision, sound ownership, or useful compiler
diagnostics.

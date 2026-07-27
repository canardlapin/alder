# Alder application DSL and experiment lifecycle amendment review

Date: 2026-07-26

Verdict: approved for implementation with the gates below. The amendment
defines one approachable entrance to Alder's existing algebra without adding a
fourth component protocol or a second execution semantics. Public source
spelling remains provisional until compile fixtures establish inference and
diagnostic quality.

## Review basis

- The canonical `PRD.json`, including P6 and decisions D24–D29.
- The live Scala 3.7.4 kernel, data, metrics, tuning, model, and documentation
  code.
- The current getting-started guide and its Train/Test example.
- The existing `Fit`, `Evaluation`, `Refit`, `Metric`, and `Study` signatures.
- The Mote application-DSL epic and its ratification gate.

## Plan reconciliation

The amendment preserves the original plan's three component abstractions:
`Transform`, `FeatureMap` or `FoldEncoder`, and `Learner`. `Blueprint` is
staged construction over exact concrete values. It does not store a trait-typed
stage list, interpret a new pipeline AST, allocate an audit stage, or widen
component errors.

`Experiment` owns application lifecycle sequencing, but every transition now
has a canonical expansion into the existing split, fit, evaluation, selection,
promotion, and refit operations. Route-specific complete methods are equal to
those transitions in sequence. They do not define a parallel fit or evaluation
protocol.

The amendment expands the 0.1 scope in one deliberate way: a usable application
entrance requires validation and three-way split results, scored evaluation,
metric identity, selection evidence, and receipt-gated refit. Those primitives
are implementation prerequisites, not behavior hidden inside the façade.

## Corrected findings

1. **Blocker — the guide's lifecycle did not exist.** `Holdout` produced Train
   and Test while `EvaluationSources.validation` required Validation. The guide
   did not score its held-out partition. The PRD now requires checked,
   non-empty `ValidationSplit` and `TrainValidationTestSplit` values and a first
   workflow that evaluates every Validation row with a real metric.
2. **Blocker — a façade could erase associated types.** A wrapper whose fields
   were typed as base `Transform` or `Learner` values would lose concrete error
   and model members. `Blueprint` states now retain stable exact values and use
   term-dependent members such as `complete.learner.Model`.
3. **Blocker — route evidence was dropped.** A validation-only refit was
   previously indistinguishable from a three-way refit, which could expose a
   test transition on the wrong route. Route and metric evidence now remain in
   every shared state. Test is available only from a
   `TrainValidationTestRoute` refit.
4. **Blocker — split ownership had two seed authorities.** The draft mixed
   materialized splits with an experiment-owned split phase. Experiment now
   stores an unseeded route-specific specification, derives the sole split seed
   from its root schedule, and calls the ordinary public `Split` constructor.
5. **Blocker — precommitted holdout lacked a lawful evaluation source.** The
   live data algebra offered validation evaluation and a final-test constructor
   that correctly requires a prior validation refit. It had no Train/Test
   constructor. The PRD now requires data-owned
   `EvaluationSources.precommittedTest`, with a Train+Test manifest and the same
   nonempty and RowId-disjointness checks.
6. **Blocker — validation scoring granted too much authority.** Successful
   prediction, completed metric evaluation, explicit selection, and data
   promotion are now separate evidence. A Validation `EvaluationReceipt`
   cannot authorize refit. Only a `SelectionReceipt` can add Validation rows;
   a Test receipt can authorize only its exact all-observed manifest.
7. **Blocker — application ownership created a potential module cycle.**
   `alder-data` now owns role partitions, evaluation sources, observed bundles,
   and private promotion authority without naming metrics or application
   states. `alder-application` combines those values with metrics and exposes
   the narrow public selection and refit transitions.
8. **Major — selection capability could be erased.** Experiment states now
   retain the exact metric value. Reporting metrics may evaluate, while only a
   retained `ObjectiveMetric` exposes selection.
9. **Major — score audit evidence was incoherent.** A free contravariant
   `ScoreAudit` instance could change receipt encoding without changing metric
   identity. `ObjectiveMetric` is invariant in its result and owns direction
   and canonical `auditScore`; its descriptor versions that policy.
10. **Major — the metric algebra contradicted FastMath.** A
    `CommutativeMonoid` accumulator cannot selectively waive associativity,
    commutativity, partition, or permutation laws. Those laws are now
    unconditional for `Metric`. A future order-sensitive fast metric must use a
    separately typed weaker SPI.
11. **Major — lifecycle failures lost phase information.** Fit, prediction,
    scoring, selection, promotion, and refit failures now retain a lifecycle
    phase. Component failures still retain their exact cause and `StagePath`;
    no application error reduces them to strings.
12. **Major — tuning's typed failure was incomplete.** The same error parameter
    now runs through `Study`, its callback, `TrialFailure`, every `Trial`,
    `Selection`, `StudyError`, and `run`. Model capability erasure does not
    become error erasure.
13. **Major — plan identity and determinism were overstated.**
    `PlanFingerprint` is policy-tagged, phase seeds use stable domain
    separation, and reproducibility claims require equal actual rows and
    component values. A `SourceIdentity` or `Summary` fingerprint does not
    prove externally managed content stayed equal.
14. **Major — optional refit made a generic terminal ambiguous.** The contract
    now uses route-constrained complete methods with exact terminal states.
    Validation convenience stops at `Validated`; selection is an explicit
    consequential decision, and deployment refit remains separate.
15. **Major — receipt reuse was assigned to the compiler.** Scala does not make
    aliased values affine. Role and lifecycle misuse remain compile-negative;
    same-role substitution and one-shot reuse are typed runtime failures,
    including under concurrent attempts.
16. **Major — fractional split semantics were underdetermined.** `Fraction` is
    now an exact reduced rational, both three-way fractions are apportioned
    against total source size with a fixed floor rule, and empty or exhaustive
    outcomes are typed failures. `RankV1` fixes UTF-8 framing, policy tags,
    integer encodings, FNV-1a64 and SplitMix64 constants, signed ordering, role
    assignment, source-order restoration, and normative golden vectors.
    Malformed rank text and duplicate source RowIds return field-specific typed
    errors before any split receipt is produced.
17. **Major — tuning reconstruction crossed the module boundary.** Base
    `alder-application` does not depend on `alder-tune` and cannot reconstruct a
    tuning family. The caller reconstructs `family(selection.best)`, completes
    the concrete Blueprint, and supplies that learner to Experiment. Any future
    convenience belongs in a separate one-way adapter.

## Ergonomic reconciliation

The proposed convenience removes proof transport, not decisions:

- Callers do not name `cats.Id`, `Unit` metadata, preparation brands,
  composition implementation classes, `EitherT`, or manual union widening.
- Callers still name source and plan identity, split policy, root seed,
  backend-bearing model preset, metric, and any selection policy that grants
  broader data access.
- The first guide stops at a scored `Validated` result. It does not hide
  selection, refit, Test access, or deployment policy to shorten the example.
- Every convenience value must remain nameable, passable, conditionally
  extensible, reusable, and repeatedly executable.
- Exact extensions and named presets are preferred to broad conversions or
  ambient backend inference.

The one-import and approximate line budget is a regression signal. It does not
override explicit identity, meaningful policy, ownership, exact error types, or
useful compiler diagnostics.

## Soundness assessment

The amended semantic contract is coherent. `Blueprint` retains the concrete
values that already carry Alder's proofs. `Experiment` retains the route and
metric capabilities that determine legal transitions. Private receipt
authority prevents identifiers or same-role values from minting promoted data.
The validation-only, selected three-way, and precommitted holdout routes now
have distinct legal state graphs and honest observed-source manifests.

The design also preserves dependency direction. Component plugins remain
unaware of the application modules. The generic application layer depends on
kernel, data, and metrics; the optional quickstart layer alone depends on
standardization and the named linop4s ridge preset.

## Should have changed in implementation

This review ratifies a contract; it does not claim the contract is implemented.
The live code must still gain or change:

- checked unseeded split specifications, exact rational apportionment,
  duplicate-RowId rejection, non-empty validation and three-way results, and
  deterministic `RankV1` split receipts;
- role-typed prediction and evaluation receipts plus the precommitted Train/Test
  evaluation-source constructor;
- scored metric evaluation, stable metric descriptors, objective capabilities,
  and lawful reproducible accumulators;
- policy-tagged plan fingerprints and the public phase-seed schedule;
- typed tuning failures throughout `Study`;
- exact-value `Blueprint`, route- and metric-preserving `Experiment`, selection
  authority, and the optional quickstart prelude;
- positive composition fixtures, compile-negative role and capability fixtures,
  runtime receipt-reuse tests, and diagnostic snapshots.

## Verification boundary

Verified directly:

- `PRD.json` parses as JSON.
- The amended module graph is acyclic.
- The specified lifecycle was reconciled against the live `Holdout`,
  `EvaluationSources`, `Refit`, `Metric`, and `Study` APIs.
- D24–D29 and the application-layer status distinguish fixed semantics from
  provisional spelling.

Still to be verified during implementation:

- Scala inference and the first diagnostic emitted for each invalid program.
- Exact façade/core result types under real compiler signatures.
- Observational equivalence, phase-seed stability, receipt concurrency, metric
  algebra laws, and all three platform builds.
- The rendered getting-started workflow and its line-budget signal.

Canonical PRD SHA-256:
`00a1282bc48e6f7bb7d7f9f21820ab286d32dadf5ca6b5298c30b83223be7b28`

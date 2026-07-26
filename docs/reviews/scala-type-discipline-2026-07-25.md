# Scala type-discipline review — 2026-07-25

Verdict: clean after two weakened-identity findings were corrected.

## Plan reconciliation

- Explicit `FitError`/`RunError` unions and attributed `Failure` values:
  upheld.
- `Reusable` versus `LearnerReady` preparation authority: upheld by exact
  result types and compile-negative consumer tests.
- `NonEmptyData` and prepared rows remain framework-controlled resources:
  upheld; external construction and row extraction do not compile.
- Row identity remains a domain type throughout splitting, alignment,
  cross-fitting, and leakage instrumentation: upheld after replacing internal
  `Long` keys and visibility sets with `RowId`.
- Fitted artifacts and encoder states retain mandatory audits: upheld.
- No warning suppression, casts, unchecked matches, exceptions, `null`,
  sentinel absence, or weakened strict compiler flags were introduced.

## Findings corrected during review

1. Testkit visibility evidence used `Set[Long]`, weakening the PRD's `RowId`
   identity contract. `VisibilityState`, `VisibilityValue`, and
   `CrossFitLeakageTests` now use `Set[RowId]`.
2. Alignment, complete-resampling validation, holdout selection, and OOF
   assembly unwrapped `RowId` into `Long` for internal map/set keys. Those
   structures now retain `RowId` end-to-end; unwrapping remains only at
   fingerprint hashing and deterministic numeric ordering boundaries.

## Should-have-changed audit

- All matches over the changed `PreparationLineageShape` and `AuditShape`
  enums cover their new cases exhaustively.
- `PreparationLineage.crossFit` is included in deterministic audit snapshots;
  no lineage field is silently omitted from the published laws.
- FeatureMap determinism compares serving behavior as well as prepared rows
  and audit state, respecting the rule that OOF rows need not equal serving
  replay.
- The new `ToleranceError` cases are constructed distinctly and tested;
  numeric absence/error states are not represented by sentinels.
- No unchanged construction or match site was found relying on an older
  `FoldEncoder`, `FeatureMap.Scope`, fingerprint, or lineage shape.

Verified directly: the complete working-tree Scala/build sources, including
untracked modules; strict compiler settings; focused JVM law/data/testkit
suites; exhaustiveness and escape-hatch scans. Cross-platform verification is
recorded separately by the aggregate `sbt test` gate.

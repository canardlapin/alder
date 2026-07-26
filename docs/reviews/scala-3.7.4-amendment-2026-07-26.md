# Scala 3.7.4 baseline amendment review

## Decision

Alder 0.1 publishes from Scala 3.7.4 on every supported platform. This amends
D1; the rest of the ratified PRD remains unchanged.

## Soundness review

The change is technically coherent with the dependency graph. Scala 3.7.4 can
read the older 3.3.8 TASTy emitted by Gale and linop4s, including the
development composite builds. The compatibility direction is not symmetric:
a consumer pinned to Scala 3.3.x cannot be promised compatibility with Alder
artifacts published from 3.7.4. The PRD now states that constraint directly.

Using one compiler baseline for JVM, Scala.js, and Scala Native also keeps
strict-warning and language-feature evidence comparable across platforms.
The full aggregate gate must be rerun after the switch; prior 3.3.8 results are
not acceptance evidence for the amended decision.

## Verdict

Approved with an explicit source/TASTy compatibility break from the former D1.
No public protocol or statistical invariant changes.

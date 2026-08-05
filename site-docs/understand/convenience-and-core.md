# From convenience API to core algebra

The quickstart layer shortens ordinary application code; it does not add a
second execution model. Each convenience expands to the same typed core value
that an extension author can construct directly.

| Convenience | Core meaning | Drop down when you need |
| --- | --- | --- |
| `Supervised.fromPairs` | `Data[Use.Unsplit, Example[X, Y, Unit]]` | metadata or an explicit fingerprint policy |
| `Standardize.emitZero` | a configured `StandardScaler` transform | another zero-variance policy or effect type |
| `Ridge.lsqr` | `RidgeRegression` with the linop4s backend | another solver, backend, or numerical configuration |
| `Blueprint.via` | `Transform.andThen` | a reusable component library boundary |
| `Blueprint.learn` | `learnWith` and a terminal `Learner` | direct control of exact component member types |
| `Experiment.validation` | split, fit, predict, metric, selection receipt, and refit protocol | custom orchestration that still preserves the same role transitions |

`Blueprint` contributes no fitted stage, audit node, or seed. This equivalence
is tested directly: the façade and core composition retain the same concrete
learner shape.

Use the application layer while the problem is “fit and evaluate this
supervised workflow.” Drop to `alder.kernel` and `alder.data` when authoring a
component, choosing an effect other than the synchronous path, carrying custom
metadata, or integrating a resampling protocol. The lower layer exposes more
types because those decisions have become part of your program rather than
defaults.

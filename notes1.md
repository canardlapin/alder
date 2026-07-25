Alder: a lawful ML framework for Scala 3

Working name: alder
Tagline: Typed learning. Lawful fitting.

This is an API-level design RFC targeting Scala 3.7.4 exactly. That release shipped on November 11, 2025. As of July 24, 2026, Scala lists 3.8.4 as the current Scala Next release and 3.3.8 as the current LTS, so a production publication matrix would need a separate compatibility decision; the design below nevertheless uses the requested 3.7.4 language level.  

Endorsement boundary: I cannot truthfully claim that Martin Odersky was consulted or approved this design. No such consultation occurred here. This is instead written to be approval-ready: conservative in its Scala mechanisms, explicit about what the compiler can prove, and free of type machinery that exists chiefly to impress its author.

The Typelevel standard worth adopting is not “use the most abstractions.” It is a small, modular, approachable, efficient, typeful foundation, accompanied by published laws and usable across Scala platforms. Cats explicitly describes those goals and uses Discipline with ScalaCheck for law testing; Typelevel’s core projects emphasize a small cross-platform foundation.  

⸻

1. The thesis

An Alder program is:

an immutable value that may be fitted only on training data and that produces an immutable, audited, typed function from an input X to a prediction P.

For preprocessing, fitting also produces the exact transformed training observations that may safely be passed to the next learner.

That last sentence is the important departure from most existing designs.

A fitted preprocessing artifact and its transformed training data are not necessarily the same computation:

* A standard scaler can fit once and replay the fitted scaler over its training rows.
* A target encoder must generate out-of-fold encodings for its training rows but use a full-data encoding model at inference.
* A calibration or stacking stage must train on out-of-fold predictions, never the base model’s in-sample predictions.

A framework that represents all of those operations as merely fit followed by transform is missing a statistically material distinction.

⸻

2. What to retain from the existing landscape

Scikit-learn gets one central idea right: the pipeline is a leakage boundary that jointly fits preprocessing and prediction during resampling. But its fitted lifecycle remains value-mutation-oriented, optional capabilities are inspected through runtime methods and estimator tags, and additional data such as weights and groups requires a metadata-routing mechanism.  

Parsnip usefully separates model specification from fitting. Recipes distinguish estimating preprocessing parameters from applying the trained recipe, and update the training set sequentially as steps are fitted. Tune and dials provide resampling, parameter ranges, transformations, and validation. Their dynamic setting naturally uses strings, captured expressions, tune() markers, and values such as unknown() that are finalized after seeing data. Alder should preserve the workflow concepts but replace those representational choices with ordinary Scala products and typed functions.  

Tribuo demonstrates that typed examples, predictions, datasets, and integrated provenance are practical in a general-purpose ML library. Alder should take those ideas further by separating fitted and unfitted phases in the type system and by making prediction semantics the result type rather than a runtime mode.  

So the inheritance is conceptual, not structural:

Retain	Replace
Explicit specification before fitting	Mutable estimator objects with learned fields
Pipelines as leakage boundaries	Arbitrary method-driven estimator chains
Sequentially fitted preprocessing	String column names and role tables
Resampling and parameter domains	tune() and unknown() placeholders
Typed prediction values	predict, predict_proba, and tag inspection
Provenance	Optional tracking layered outside the model
Backend interoperability	Engine names represented as strings

There is no universal Estimator base class in Alder.

⸻

3. The restraint rule

A type parameter belongs in the API only when changing it changes which programs are legal.

Therefore Alder types:

* the data-use role;
* input, target, metadata, transformed feature, and prediction shapes;
* fitted versus unfitted phase;
* component capabilities;
* expected error channels.

Alder does not put these into public types:

* matrix dimensions learned from data;
* fold numbers;
* seeds;
* backend versions;
* convergence tolerances;
* stage names;
* dataset fingerprints.

Those remain validated values and mandatory audit information.

This avoids type-level natural numbers, singleton column-name programs, public match-type puzzles, and path-dependent feature dimensions. Those techniques can be useful internally, but they are not an acceptable tax on every user or coding agent.

⸻

4. The kernel

4.1 Data has a use

package alder.kernel
sealed trait Use
object Use:
  sealed trait Unsplit extends Use
  sealed trait Train extends Use
  sealed trait Evaluation extends Use
  sealed trait Validation extends Evaluation
  sealed trait Test       extends Evaluation

A dataset’s role is phantom but its constructors are controlled:

trait Data[+U <: Use, +A]:
  def size: Long
  def fingerprint: DataFingerprint
final class NonEmptyData[+U <: Use, +A] private[alder] (
  val data: Data[U, A]
)

Loading data produces Data[Use.Unsplit, A]. A splitter produces train and test values:

final class Holdout[A] private[alder] (
  val train: NonEmptyData[Use.Train, A],
  val test:  Data[Use.Test, A]
)

Only Train appears in a fitting signature. There is no public asTrain, retag, or cast-like convenience method.

This catches gross procedural mistakes without pretending the type system can prove that a user did not manually reconstruct a new dataset from test rows. Alder’s safe API does not lie; deliberately leaving that API remains possible, as it is in every Scala library.

Each logical row also carries an internal stable RowId. Row identity supports:

* split-disjointness checks;
* out-of-fold reconstruction in original order;
* leakage tracing in law tests;
* auditable fold lineage.

4.2 Observations are ordinary Scala values

final case class Example[+X, +Y, +M](
  input:  X,
  target: Y,
  meta:   M
)
final case class Scored[+Y, +P, +M](
  truth:      Y,
  prediction: P,
  meta:       M
)

M is real application data, not Map[String, Any].

final case class LoanMeta(
  weight:     SampleWeight,
  customerId: CustomerId,
  observedAt: Instant
)
given WeightOf[LoanMeta] with
  def apply(m: LoanMeta): SampleWeight = m.weight
given GroupOf[LoanMeta] with
  type Key = CustomerId
  def apply(m: LoanMeta): CustomerId = m.customerId
given TimeOf[LoanMeta] with
  def apply(m: LoanMeta): Instant = m.observedAt

A grouped resampler requires GroupOf[M]. A rolling-origin resampler requires TimeOf[M]. A weighted learner or metric requires WeightOf[M].

There is no router to configure because the requirement is visible in the signature.

4.3 A fitted computation is just a typed pipe

trait Pipe[-A, +B]:
  type Error
  def apply(value: A): Either[Error, B]

A Pipe[X, P] is:

* immutable;
* pure;
* already fitted;
* locally executable;
* explicit about expected inference failure.

Fitting may perform effects. Local inference normally should not. A model backed by a remote service, native handle, or device runtime is compiled separately into an effectful serving interface:

trait Serving[F[_], -X, +P]:
  type Error
  def predict(input: X): F[Either[Error, P]]

That keeps resource ownership out of every ordinary model.

4.4 Fitted artifacts never travel without their audit

final class Trained[+A] private[alder] (
  val artifact: A,
  val audit:    Audit
)

Audit contains at least:

final case class Audit(
  plan:        PlanFingerprint,
  data:        DataFingerprint,
  schema:      SchemaFingerprint,
  seed:        Seed,
  backend:     BackendFingerprint,
  numerics:    NumericMode,
  preparation: PreparationLineage,
  children:    Vector[Audit]
)

This is not an experiment-tracker side channel. It is part of the result of fitting.

The audit does not need to embed raw training data. A configured fingerprinting policy can record content digests, source identities, or privacy-preserving summaries, but it must record which policy was used.

⸻

5. Three unfitted abstractions

5.1 Transform: target-blind preprocessing

import cats.data.EitherT
trait Transform[F[_], X, Z]:
  type FitError
  type RunError
  type Fitted <: Pipe[X, Z] {
    type Error = RunError
  }
  def fit(
    data: NonEmptyData[Use.Train, X]
  )(using FitContext): EitherT[F, FitError, Trained[Fitted]]

A Transform cannot inspect Y or M because they do not occur in its input. That is stronger and clearer than documenting that an implementation “should ignore y.”

A transform can be lifted into supervised preprocessing for any Y and M. The library—not the implementation—preserves targets, metadata, and row identities.

5.2 FeatureMap: leakage-aware supervised preprocessing

final class Prepared[+A, +B] private[alder] (
  val fitted:   Trained[A],
  val training: NonEmptyData[Use.Train, B]
)
trait FeatureMap[F[_], X, Y, M, Z]:
  type FitError
  type RunError
  type Fitted <: Pipe[X, Z] {
    type Error = RunError
  }
  def fit(
    data: NonEmptyData[Use.Train, Example[X, Y, M]]
  )(using FitContext): EitherT[
    F,
    FitError,
    Prepared[Fitted, Example[Z, Y, M]]
  ]

Prepared is the hinge of the design.

It returns both:

1. the full-data fitted pipe used for validation, testing, and serving;
2. the training observations that are safe to pass downstream.

For an input-only scaler, training is obtained by replaying the fitted scaler over each training input.

For a target-aware encoding, training contains out-of-fold values. It is intentionally not required to equal replaying the full fitted pipe over the same rows.

Safe extension points

Most third-party preprocessing uses one of two constructors:

object FeatureMap:
  def inputOnly[F[_], X, Y, M, Z](
    transform: Transform[F, X, Z]
  ): FeatureMap[F, X, Y, M, Z] =
    ???
  def crossFitted[F[_], X, Y, M, Z](
    encoder:   FoldEncoder[F, X, Y, M, Z],
    resampler: Resampler[Example[X, Y, M]]
  ): FeatureMap[F, X, Y, M, Z] =
    ???

A fold encoder defines only how to fit a state and apply that state:

trait FoldEncoder[F[_], X, Y, M, Z]:
  type State
  type FitError
  type RunError
  def fit(
    data: NonEmptyData[Use.Train, Example[X, Y, M]]
  )(using FitContext): EitherT[F, FitError, State]
  def encode(
    state: State,
    input: X
  ): Either[RunError, Z]

FeatureMap.crossFitted owns the protocol:

1. split the training data;
2. fit on each analysis partition;
3. encode only its held-out assessment partition;
4. reassemble the out-of-fold training rows;
5. fit one final state on all training rows for serving;
6. record both lineages in the audit.

There is deliberately no public encoder.fit(all).transform(all) shortcut.

A lower-level implementation SPI can exist, but it should live under alder.spi or alder.unsafe.spi and ship with a mandatory law suite.

5.3 Learner: the terminal learning algorithm

trait Learner[F[_], X, Y, M, P]:
  type FitError
  type RunError
  type Model <: Pipe[X, P] {
    type Error = RunError
  }
  def fit(
    data: NonEmptyData[Use.Train, Example[X, Y, M]]
  )(using FitContext): EitherT[
    F,
    FitError,
    Trained[Model]
  ]

A learner is terminal in an ordinary workflow. It does not pretend to be a generic transformer.

This matters for calibration, stacking, distillation, and target encoding. Feeding a model’s in-sample predictions into a second learner is usually the wrong protocol. Alder makes those operations explicit:

CrossFit.predictions(baseWorkflow, folds)
  .learnWith(calibrator)

Pure prediction postprocessing is safe and remains easy:

trained.mapPrediction(Decision.argmax)

⸻

6. Workflow composition

A target-blind transform can precede another transform or a learner:

val workflow =
  standardizer.andThen(ridge)

A feature map can be composed with another feature map and then a learner:

val workflow =
  targetEncoding
    .andThen(standardization)
    .learnWith(logisticRegression)

Internally, workflow fitting is exactly:

for
  prepared <- featureMap.fit(training)
  model    <- learner.fit(prepared.training)
yield compose(prepared.fitted, model)

The resulting model accepts the workflow’s original input X, not the intermediate feature type Z.

Composition assigns stable stage identities. Seeds are derived from:

root seed + normalized plan fingerprint + stable stage ordinal

Parenthesizing a composition differently must not change stage seeds, audit paths, or fitted behavior.

There is no:

* isFitted;
* check_is_fitted;
* mutable coefficient field appearing after a method call;
* clone protocol;
* reflective parameter map;
* special-case discovery of whether the last stage has predict_proba.

The unfitted value simply does not have prediction operations.

⸻

7. Prediction semantics are the result type

Alder does not encode “regression” or “classification” as a string mode.

Typical prediction types are:

Double
Distribution[Class]
Class
Ranking[Item]
SurvivalCurve[Time]
Quantiles[Probability, Double]
Gaussian

A probabilistic classifier is:

Learner[F, X, Class, M, Distribution[Class]]

A hard decision is a separate pure pipe:

val hard: Pipe[Distribution[Class], Class] =
  Decision.argmax

This gives metrics honest input types:

val rmse:
  Metric[Scored[Double, Double, M], RootMeanSquaredError]
val logLoss:
  Metric[Scored[C, Distribution[C], M], LogLoss]
val accuracy:
  Metric[Scored[C, C, M], Accuracy]

It is impossible to accidentally evaluate hard labels with log loss or probability distributions with ordinary accuracy unless the user explicitly applies a decision rule.

Distribution[C], Probability, Positive, NonNegative, and similar values have controlled constructors. Dynamic values return a typed validation result. Literal constructors may validate at compile time:

val penalty  = NonNegative.const(0.1)
val tolerance = Positive.const(1e-8)
// Does not compile:
val bad = NonNegative.const(-0.1)

Scala 3 opaque aliases are a good fit for these zero-overhead semantic values and feature brands. derives supports ordinary typeclass generation, while strict equality can prevent comparisons between unrelated domain types.  

⸻

8. Schemas without string columns

Application schemas remain case classes and enums:

final case class House(
  areaM2:     Double,
  bedrooms:  Double,
  ageYears:  Double
) derives Schema, Coordinates, CanEqual

Schema[A] describes serialization and external compatibility.

Coordinates[A] describes a complete, ordered numeric observation:

trait Coordinates[A]:
  def names: IArray[String]
  def size: Int
  def read(value: A): IArray[Double]
  def build(
    values: IArray[Double]
  ): Either[CoordinateError, A]

Derivation succeeds only for supported complete numeric products. For example:

final case class IncompleteHouse(
  areaM2:    Option[Double],
  bedrooms: Double
)

does not automatically acquire Coordinates[IncompleteHouse]. An imputation transform must first produce a complete type.

The library does not silently treat None, null, NaN, and “missing” as interchangeable representations.

Alder should support dynamic vectors too:

Dense[FeatureSpace]
Sparse[FeatureSpace]

Those carry a runtime schema fingerprint and a semantic phantom feature-space brand. Alder should not encode every learned one-hot dimension as a type-level natural number.

⸻

9. Metrics are mergeable values

import cats.kernel.CommutativeMonoid
trait Metric[-A, +S]:
  type Acc
  given accumulator: CommutativeMonoid[Acc]
  def observe(value: A): Acc
  def finish(
    accumulated: Acc
  ): Either[MetricError, S]

This means evaluation can be streamed or parallelized without changing the public metric.

The default numerical metrics should use a reproducible mergeable accumulator rather than pretending ordinary floating-point addition is strictly associative. A separate FastMath mode may trade bitwise reproducibility for speed, but that choice must appear in the audit and the applicable law set.

A weighted metric does not accept an optional argument:

def weightedRmse[M: WeightOf]:
  Metric[
    Scored[Double, Double, M],
    RootMeanSquaredError
  ]

The unweighted and weighted metrics are different values with different requirements.

⸻

10. Tuning searches only valid configurations

A model configuration is always complete and valid:

final case class RidgeConfig(
  penalty:      NonNegative,
  fitIntercept: Boolean,
  tolerance:    Positive
)

Tuning is represented separately:

sealed trait Space[+A]
object Space:
  given cats.Applicative[Space] = ???
  def constant[A](value: A): Space[A]
  def choice[A](
    head: A,
    tail: A*
  ): Space[A]
  def logUniform(
    minimum: Positive,
    maximum: Positive
  ): Space[Positive]

A ridge search space is a space of real RidgeConfig values:

import cats.syntax.all.*
val ridgeSpace: Space[RidgeConfig] =
  (
    Space
      .logUniform(
        Positive.const(1e-6),
        Positive.const(1e2)
      )
      .map(_.asNonNegative),
    Space.choice(true, false)
  ).mapN { (penalty, intercept) =>
    RidgeConfig(
      penalty      = penalty,
      fitIntercept = intercept,
      tolerance    = Positive.const(1e-8)
    )
  }

There is no partially valid model object containing a tuning sentinel.

Data-dependent spaces are ordinary explicit functions:

val sampledFeatures:
  FeatureSchema => Space[PositiveInt] =
    schema =>
      Space.intRange(
        PositiveInt.one,
        PositiveInt.const(schema.size)
      )

The data dependency cannot be forgotten because the function cannot be evaluated without a FeatureSchema.

A study accepts training data only:

val study =
  Tune.random(
    family = config =>
      standardizer.andThen(
        RidgeRegression(config, backend)
      ),
    space       = ridgeSpace,
    resampling  = KFold.const(5),
    metric      = RMSE,
    trials      = PositiveInt.const(50),
    seed        = Seed(7)
  )
val result =
  study.run(split.train)
// No overload accepts split.test.

Space should be applicative, not monadic. Conditional configurations are represented as an explicit sum type:

enum TreeShape:
  case DepthLimited(maxDepth: PositiveInt)
  case LeafLimited(maxLeaves: PositiveInt)

A Space[TreeShape] can choose between valid variants. This gives optimizers a finite, inspectable parameter structure and prevents arbitrary effectful configuration programs.

⸻

11. Capabilities are evidence, not tags

A model exposes only its universal operation: typed prediction.

Everything else is a typeclass:

trait ArtifactCodec[A]:
  def encode(
    trained: Trained[A]
  ): Either[CodecError, Array[Byte]]
  def decode(
    bytes: Array[Byte]
  ): Either[CodecError, Trained[A]]
trait Explain[A, -X]:
  type Attribution
  def apply(
    trained: Trained[A],
    input: X
  ): Either[ExplainError, Attribution]
trait Incremental[L]:
  type Update
  def update(
    learner: L,
    update: Update
  ): L

Generic functions state exactly what they need:

def save[A: ArtifactCodec](
  artifact: Trained[A],
  destination: ArtifactPath
): IO[Unit]
def explain[A, X](
  artifact: Trained[A],
  input: X
)(using E: Explain[A, X]): Either[
  ExplainError,
  E.Attribution
]

No codec means saving does not compile. No explanation implementation means explanation does not compile. No incremental capability means there is no universal partialFit method that might throw “unsupported.”

The codec law is observational:

decode(encode(model)).predict(x) == model.predict(x)

for all valid x, and the audit must round-trip without semantic loss.

Java serialization is not an artifact format.

⸻

12. The laws

The compiler, the law suite, and runtime validation have distinct jobs.

Law	Required behavior
Pipe identity	identity.andThen(p) and p.andThen(identity) are observationally equal to p.
Pipe associativity	Parenthesization does not change successful output, first failing stage, stage path, or seed allocation.
Feature preservation	Every prepared row retains its original row ID, target, and metadata exactly. Only input may change.
Serving coherence	Scoring an example transforms its input with the same fitted pipe used by serving.
Input-only replay	A lifted target-blind transform’s prepared training rows equal replaying its fitted pipe over the original training inputs.
Cross-fit exclusion	For every out-of-fold training row, that row’s ID is absent from the fitting lineage of the state that produced its transformed input or prediction.
Score coherence	score(model, e) equals model(e.input).map(p => Scored(e.target, p, e.meta)).
Deterministic fitting	Given equal plan, data fingerprint, seed, backend, and deterministic numeric mode, fitting is observationally repeatable.
Metric partition law	Evaluating concatenated partitions equals merging their accumulators and finishing once.
Metric permutation law	Reordering observations does not change an exact metric result. Numerical modes state their tolerance where exact equality is impossible.
Space validity	Every value emitted by Space[A] is a valid A; candidate generation cannot produce a partial config.
Space seed law	Equal space, strategy, and seed yield the same ordered candidates.
Codec round trip	Encoding and decoding preserve audit and prediction behavior.

alder-laws publishes Discipline rule sets:

PipeTests
TransformTests
FeatureMapTests
LearnerTests
MetricTests
SpaceTests
ArtifactCodecTests

The cross-fitting laws use an instrumented dataset and encoder that record every visible RowId. This tests the actual fitting protocol rather than relying on an implementation’s declaration that it is leakage-safe.

Algorithm implementations add their own laws.

⸻

13. Implementation example one: StandardScaler

Public shape

opaque type Standardized[A] = A
object Standardized:
  private[alder] def wrap[A](
    value: A
  ): Standardized[A] =
    value
  private[alder] def unwrap[A](
    value: Standardized[A]
  ): A =
    value
  given [A: Coordinates]:
      Coordinates[Standardized[A]] =
    Coordinates[A].imap(wrap)(unwrap)

The brand prevents an algorithm requiring standardized observations from receiving raw observations.

enum ZeroVariance:
  case Reject
  case EmitZero
enum ScaleFitError:
  case NonFinite(
    row:        RowId,
    coordinate: String,
    value:      Double
  )
  case ConstantCoordinate(
    coordinate: String
  )
enum ScaleRunError:
  case NonFiniteInput(
    coordinate: String,
    value:      Double
  )
  case NonFiniteOutput(
    coordinate: String
  )
final class StandardScaler[
  F[_]: cats.Applicative,
  A: Coordinates
](
  zeroVariance: ZeroVariance
) extends Transform[F, A, Standardized[A]]:
  type FitError = ScaleFitError
  type RunError = ScaleRunError
  type Fitted   = Standardizer[A]
  def fit(
    data: NonEmptyData[Use.Train, A]
  )(using context: FitContext): EitherT[
    F,
    ScaleFitError,
    Trained[Standardizer[A]]
  ] =
    EitherT.fromEither:
      for
        moments <- Moments.compute(data)
        inverse <- moments.inverseStandardDeviation(
          zeroVariance
        )
        pipe = Standardizer(
          mean   = moments.mean,
          invStd = inverse
        )
        audit = Audit.standardScaler(
          data       = data,
          context    = context,
          coordinates = summon[Coordinates[A]],
          zeroVariance = zeroVariance
        )
      yield Trained.internal(pipe, audit)

The fitted pipe is small and immutable:

final class Standardizer[A] private[alder] (
  mean:   IArray[Double],
  invStd: IArray[Double]
)(using coordinates: Coordinates[A])
    extends Pipe[A, Standardized[A]]:
  type Error = ScaleRunError
  def apply(
    value: A
  ): Either[ScaleRunError, Standardized[A]] =
    for
      raw <- coordinates
        .readFinite(value)
        .leftMap(ScaleRunError.NonFiniteInput.apply)
      scaled = IArray.tabulate(raw.length): i =>
        (raw(i) - mean(i)) * invStd(i)
      _ <- Coordinates.requireFinite(scaled)
        .leftMap(ScaleRunError.NonFiniteOutput.apply)
      rebuilt <- coordinates.build(scaled)
    yield Standardized.wrap(rebuilt)

Moments.compute should use a stable online or pairwise algorithm. Its numeric policy belongs in FitContext and the audit.

Standard-scaler laws

For finite input data:

1. Every nonconstant fitted coordinate has training mean approximately zero.
2. Every nonconstant fitted coordinate has population or sample variance exactly according to the documented policy.
3. Under EmitZero, a constant coordinate always emits zero.
4. Under Reject, fitting a constant coordinate returns ConstantCoordinate.
5. Coordinate names and order are preserved.
6. Finite input never silently produces a nonfinite successful output.
7. Lifting the scaler into a FeatureMap obeys input-only replay and preserves target, metadata, and row IDs.

⸻

14. Implementation example two: RidgeRegression

Configuration and backend boundary

final case class RidgeConfig(
  penalty:      NonNegative,
  fitIntercept: Boolean,
  tolerance:    Positive
)

The solver is a value, not an engine string:

trait RidgeBackend[F[_]]:
  def solve(
    design:    DesignMatrix,
    target:    DenseVector,
    config:    RidgeConfig
  ): EitherT[F, RidgeBackendError, RidgeSolution]
  def fingerprint: BackendFingerprint
  def numericMode: NumericMode

A JVM BLAS implementation, a pure reference implementation, and a native implementation can all implement this interface without changing the learner’s semantics.

Learner

enum RidgeFitError:
  case NonFiniteFeature(
    row:        RowId,
    coordinate: String,
    value:      Double
  )
  case NonFiniteTarget(
    row:   RowId,
    value: Double
  )
  case Backend(
    error: RidgeBackendError
  )
enum RidgeRunError:
  case NonFiniteFeature(
    coordinate: String,
    value:      Double
  )
  case NonFinitePrediction
final class RidgeRegression[
  F[_],
  X: Coordinates,
  M
](
  config:  RidgeConfig,
  backend: RidgeBackend[F]
) extends Learner[F, X, Double, M, Double]:
  type FitError = RidgeFitError
  type RunError = RidgeRunError
  type Model    = RidgeModel[X]
  def fit(
    data: NonEmptyData[
      Use.Train,
      Example[X, Double, M]
    ]
  )(using context: FitContext): EitherT[
    F,
    RidgeFitError,
    Trained[RidgeModel[X]]
  ] =
    for
      design <- EitherT.fromEither[F](
        DesignMatrix
          .fromExamples(data)
          .leftMap(RidgeFitError.NonFiniteFeature.apply)
      )
      target <- EitherT.fromEither[F](
        DenseVector
          .targets(data)
          .leftMap(RidgeFitError.NonFiniteTarget.apply)
      )
      solution <- backend
        .solve(design, target, config)
        .leftMap(RidgeFitError.Backend.apply)
    yield
      val model = RidgeModel[X](
        coefficients = solution.coefficients,
        intercept    = solution.intercept
      )
      Trained.internal(
        model,
        Audit.ridge(
          data    = data,
          context = context,
          config  = config,
          backend = backend
        )
      )
final class RidgeModel[X] private[alder] (
  coefficients: IArray[Double],
  intercept:    Double
)(using coordinates: Coordinates[X])
    extends Pipe[X, Double]:
  type Error = RidgeRunError
  def apply(
    input: X
  ): Either[RidgeRunError, Double] =
    for
      values <- coordinates
        .readFinite(input)
        .leftMap(RidgeRunError.NonFiniteFeature.apply)
      prediction = LinearAlgebra.dot(
        coefficients,
        values
      ) + intercept
      result <-
        Either.cond(
          prediction.isFinite,
          prediction,
          RidgeRunError.NonFinitePrediction
        )
    yield result

Ridge laws

For a backend declaring deterministic behavior:

1. Prediction equals the documented dot product plus intercept.
2. The coefficient count equals Coordinates[X].size.
3. Reordering training rows produces numerically equivalent coefficients.
4. The fitted solution’s normal-equation or KKT residual is within the requested solver tolerance.
5. With zero penalty and a full-rank design, the result agrees with ordinary least squares within the backend’s declared numerical tolerance.
6. Repeated fitting with the same plan, data, seed, and backend is observationally equivalent.
7. Scoring obeys score coherence and preserves target and metadata.
8. The model never returns a successful nonfinite prediction.

The solver law suite is separate from the learner law suite. A backend cannot claim a tolerance or determinism level it does not test.

⸻

15. The complete user experience

import alder.*
import alder.models.linear.*
import cats.effect.IO
final case class House(
  areaM2:    Double,
  bedrooms: Double,
  ageYears: Double
) derives Schema, Coordinates, CanEqual
val scaler =
  StandardScaler[IO, House](
    zeroVariance = ZeroVariance.EmitZero
  )
val ridge =
  RidgeRegression[
    IO,
    Standardized[House],
    Unit
  ](
    config = RidgeConfig(
      penalty      = NonNegative.const(0.1),
      fitIntercept = true,
      tolerance    = Positive.const(1e-8)
    ),
    backend = RidgeBackend.reference[IO]
  )
val workflow =
  scaler.andThen(ridge)
val split =
  Split.holdout(
    data         = houses,
    testFraction = TestFraction.const(0.2),
    seed         = Seed(42)
  )
val fitted
  : EitherT[
      IO,
      workflow.FitError,
      Trained[workflow.Model]
    ] =
  workflow.fit(split.train)

The workflow accepts raw House values for serving because it encapsulates both the standardizer and ridge model.

Evaluation uses the test role and the prediction type:

val report =
  fitted.subflatMap: model =>
    Evaluate.test(
      model  = model,
      data   = split.test,
      metric = RMSE[Unit]
    )

Programs that fail to compile

workflow.predict(house)
// Workflow is unfitted and has no predict operation.
workflow.fit(split.test)
// Found:    Data[Use.Test, ...]
// Required: NonEmptyData[Use.Train, ...]
ridge.fit(rawHouseTraining)
// Found:    Example[House, Double, Unit]
// Required: Example[Standardized[House], Double, Unit]
Evaluate.test(
  hardClassifier,
  classificationTest,
  LogLoss[Class, Unit]
)
// Hard classifier predicts Class.
// LogLoss requires Distribution[Class].
save(trainedModel, path)
// Does not compile unless ArtifactCodec[Model] is available.
explain(trainedModel, house)
// Does not compile unless Explain[Model, House] is available.
WeightedRidgeRegression[IO, House, Unit](config)
// No given WeightOf[Unit].

Those failures are not ornamental. Each identifies a real category of misuse at the call site where it was introduced.

⸻

16. What compilation proves—and what it does not

A successful compilation can establish that:

* fitting receives data marked for training;
* test data are not accepted by fit or tune APIs;
* the algorithm is fitted before prediction;
* preprocessing output matches learner input;
* missingness has been handled when complete coordinates are required;
* prediction shape matches metric shape;
* metadata capabilities required by an operation exist;
* configuration values satisfy their represented domains;
* requested optional model capabilities exist;
* expected failure channels are handled or propagated.

Compilation does not establish that:

* the training data are representative;
* labels are correct;
* the chosen split reflects deployment conditions;
* the model generalizes;
* a metric is suitable for the business decision;
* an optimization algorithm converges;
* a model is fair or causally valid;
* a nondeterministic GPU backend will reproduce bits;
* a custom unlawful implementation tells the truth.

Alder’s governing principle is:

The compiler rejects category errors, laws test semantic obligations, and runtime validation handles data-dependent facts. No layer pretends to do another layer’s job.

⸻

17. Module structure

alder-kernel
  Data roles, Example, Scored, Pipe, Transform,
  FeatureMap, Learner, Workflow, audit vocabulary
alder-laws
  Discipline and ScalaCheck law definitions
alder-testkit
  Generators, numerical equality, leakage tracing,
  compile-negative documentation tests
alder-data
  In-memory data, typed CSV/JSON/Arrow adapters,
  splitting, row identity, schema derivation
alder-metrics
  Typed metrics and reproducible accumulators
alder-linear
  Coordinates, dense/sparse values, scalar reference algebra
alder-models-linear
  StandardScaler, ridge and logistic regression
alder-tune
  Space, resampling studies, grid/random/Bayesian interpreters
alder-codec
  Versioned artifact formats and codec laws
alder-jvm-blas
  JVM-specific numerical backends
alder-serving
  Resource-managed native, ONNX, and remote interpreters

alder-kernel should depend on cats-core, not Cats Effect. Effects enter fitting implementations, tuning, data streaming, and resourceful serving. The pure kernel, laws, and applicable reference implementations should cross-build for JVM, Scala.js, and Scala Native.

The build begins conservatively:

ThisBuild / scalaVersion := 3.7.4
scalacOptions ++= Seq(
  -deprecation,
  -feature,
  -unchecked,
  -Wunused:all,
  -Wvalue-discard,
  -Yexplicit-nulls,
  -language:strictEquality,
  -Werror
)

No universal tensor belongs in the kernel. No fake Field[A] abstraction should be placed over algorithms that are actually specialized BLAS computations on Float or Double.

⸻

18. What should not enter version 0.1

The first release should not contain a dynamic DataFrame DSL, AutoML, distributed execution, deep-learning graphs, model registries, dashboards, or twenty shallow algorithm wrappers.

It should contain:

* the role-typed dataset protocol;
* Pipe, Transform, FeatureMap, Learner, and Workflow;
* safe input-only and cross-fitted constructors;
* typed prediction values;
* splitting, resampling, evaluation, and Space;
* mandatory audit data;
* codec and capability typeclasses;
* the complete law kit;
* StandardScaler;
* RidgeRegression;
* one deterministic reference numerical backend.

That is enough to demonstrate the architecture without freezing it around a prematurely broad compatibility surface.

The essential insight is not a clever type. It is that training and serving are related protocols, not the same function, and the framework must carry the safe training result forward explicitly. Once that distinction is made, phase separation, prediction typing, metadata typing, tuning, provenance, calibration, and leakage prevention fall into a compact and coherent design.

It should be a small, ordinary library dependency

A third-party author should depend on alder-kernel, not an umbrella artifact containing datasets, tuning, codecs, BLAS bindings, and built-in models.

Assuming Alder were eventually published under the Typelevel organization, their build would look like this:

ThisBuild / scalaVersion := 3.7.4
val alderVersion = 0.1.0 // illustrative; Alder is not currently published
libraryDependencies ++= Seq(
  org.typelevel %% alder-kernel     % alderVersion,
  org.typelevel %% alder-linear-api % alderVersion,
  org.typelevel %% alder-laws       % alderVersion % Test
)

The corresponding source imports would be similarly narrow:

import alder.kernel.{
  ComponentDescriptor,
  FitContext,
  NonEmptyData,
  Pipe,
  Trained,
  Transform,
  Use
}
import alder.linear.{
  Coordinates,
  Dense
}
import cats.data.EitherT

alder-linear-api would be optional. It is useful when the implementation wants Alder’s standard Dense[S], Sparse[S], Coordinates[A], and matrix interchange types. An implementation using its own input and output value types can depend on alder-kernel alone.

The root alder artifact, should one exist, would be an application convenience. Library authors should not use it.

⸻

Sparse PCA is normally a Transform

Although sparse PCA is colloquially a “learner,” it does not learn a target prediction. Its fitted artifact maps an input feature value to a latent representation:

X ──fit on training X──> fitted projection X => SparseComponents

In Alder terminology, that is:

Transform[F, X, Dense[LatentSpace]]

This is not pedantry. It tells the workflow system that:

* the algorithm does not inspect targets;
* it may safely be fitted as input-only preprocessing;
* its training outputs can be obtained by replaying the fitted projection;
* it may precede a supervised Learner;
* it cannot accidentally receive metadata or labels.

A supervised sparse-PCA variant that uses Y to learn its components would instead be a FeatureMap. It should normally use the cross-fitted constructor, because downstream training rows must not receive features learned using their own targets.

Only a component whose fitted artifact directly predicts the terminal target would implement Learner.

⸻

A third-party implementation

The third party can define its own configuration, errors, solver, and fitted model without extending an Alder base class beyond Transform and Pipe.

package com.acme.alder.sparsepca
import alder.kernel.*
import alder.linear.*
import cats.data.EitherT
final case class SparsePcaConfig(
  components:    PositiveInt,
  l1Penalty:     NonNegative,
  maxIterations: PositiveInt,
  tolerance:     Positive
)
enum SparsePcaFitError:
  case ComponentsExceedFeatures(
    requested: PositiveInt,
    available: Int
  )
  case NonFiniteFeature(
    row:        RowId,
    coordinate: String,
    value:      Double
  )
  case SolverFailure(
    message: String
  )
enum SparsePcaRunError:
  case NonFiniteFeature(
    coordinate: String,
    value:      Double
  )
  case NonFiniteProjection(
    component: Int
  )

The numerical implementation remains entirely under the third party’s control:

trait SparsePcaSolver[F[_]]:
  def solve[X](
    data:   NonEmptyData[Use.Train, X],
    config: SparsePcaConfig,
    seed:   Seed
  )(using Coordinates[X]): EitherT[
    F,
    SparsePcaFitError,
    SparsePcaSolution
  ]
  def fingerprint: BackendFingerprint
final case class SparsePcaSolution(
  center:   IArray[Double],
  loadings: IArray[IArray[Double]]
)

The fitted model is an ordinary immutable pipe. The S parameter is a semantic brand for the latent feature space; the number of components remains a validated runtime value.

final class SparsePcaModel[X, S] private[sparsepca] (
  center:   IArray[Double],
  loadings: IArray[IArray[Double]]
)(using coordinates: Coordinates[X])
    extends Pipe[X, Dense[S]]:
  type Error = SparsePcaRunError
  def apply(
    input: X
  ): Either[SparsePcaRunError, Dense[S]] =
    for
      values <- coordinates
        .readFinite(input)
        .leftMap: invalid =>
          SparsePcaRunError.NonFiniteFeature(
            coordinate = invalid.coordinate,
            value      = invalid.value
          )
      projected = project(
        values   = values,
        center   = center,
        loadings = loadings
      )
      _ <- projected.zipWithIndex.foldLeft(
        Right(()): Either[SparsePcaRunError, Unit]
      ):
        case (result @ Left(_), _) =>
          result
        case (Right(_), (value, index)) =>
          Either.cond(
            value.isFinite,
            (),
            SparsePcaRunError.NonFiniteProjection(index)
          )
    yield Dense.validated[S](projected)

The unfitted component implements the small Alder protocol:

final class SparsePca[
  F[_],
  X: Coordinates,
  S
](
  config: SparsePcaConfig,
  solver: SparsePcaSolver[F]
) extends Transform[F, X, Dense[S]]:
  type FitError = SparsePcaFitError
  type RunError = SparsePcaRunError
  type Fitted   = SparsePcaModel[X, S]
  def fit(
    data: NonEmptyData[Use.Train, X]
  )(using context: FitContext): EitherT[
    F,
    SparsePcaFitError,
    Trained[SparsePcaModel[X, S]]
  ] =
    solver
      .solve(
        data   = data,
        config = config,
        seed   = context.seed
      )
      .map: solution =>
        val model =
          SparsePcaModel[X, S](
            center   = solution.center,
            loadings = solution.loadings
          )
        context.complete(
          artifact = model,
          trainedOn = data,
          component = ComponentDescriptor(
            id = ComponentId(
              com.acme.alder.sparse-pca
            ),
            version = ComponentVersion(
              BuildInfo.version
            ),
            parameters = AuditValue.record(
              components ->
                AuditValue.integer(
                  config.components.value
                ),
              l1Penalty ->
                AuditValue.decimal(
                  config.l1Penalty.value
                ),
              maxIterations ->
                AuditValue.integer(
                  config.maxIterations.value
                ),
              tolerance ->
                AuditValue.decimal(
                  config.tolerance.value
                )
            ),
            backend = solver.fingerprint
          )
        )

There is no registration file, reflection, package scanning, global model catalogue, or magic engine name. The application constructs the implementation as a normal Scala value.

⸻

One correction to the original kernel design

The earlier sketch used an internal constructor such as:

Trained.internal(model, audit)

That would prevent genuine third-party implementations. The external extension API needs a public but constrained factory:

trait FitContext:
  def seed: Seed
  def complete[A, X](
    artifact: A,
    trainedOn: NonEmptyData[Use.Train, X],
    component: ComponentDescriptor
  ): Trained[A]

Trained itself can retain a private constructor. The factory ensures that the framework—not the plugin—supplies and records:

* the current stage path;
* the training-data fingerprint;
* the root and derived seeds;
* numeric mode;
* parent workflow lineage;
* schema fingerprint;
* child audits.

The plugin supplies only its identity, version, validated configuration, and backend fingerprint. It cannot accidentally omit the framework-level provenance.

Likewise, training data must expose a small public read-only API. It cannot be an opaque value that only Alder’s built-in models can inspect:

trait Data[+U <: Use, +A]:
  def size: Long
  def fingerprint: DataFingerprint
  def foldRows[B](
    initial: B
  )(
    step: (B, RowId, A) => B
  ): B

alder-linear-api can provide conveniences such as:

DesignMatrix.from(data)
DenseVector.targets(data)

but a third party may fold the rows directly into its own matrix representation.

⸻

Consumer usage

The third party could publish two artifacts:

com.acme:alder-sparse-pca-core_3
com.acme:alder-sparse-pca-ejml_3

The first contains the Alder integration, model, configuration, and solver interface. The second supplies one optional numerical backend.

An application selects the implementation explicitly:

libraryDependencies ++= Seq(
  com.acme %% alder-sparse-pca-core % 1.2.0,
  com.acme %% alder-sparse-pca-ejml % 1.2.0
)

Then:

import alder.models.linear.LogisticRegression
import com.acme.alder.sparsepca.*
sealed trait DocumentComponents
val sparsePca =
  SparsePca[
    IO,
    DocumentFeatures,
    DocumentComponents
  ](
    config = SparsePcaConfig(
      components    = PositiveInt.const(64),
      l1Penalty     = NonNegative.const(0.05),
      maxIterations = PositiveInt.const(500),
      tolerance     = Positive.const(1e-7)
    ),
    solver = EjmlSparsePcaSolver[IO]()
  )
val workflow =
  sparsePca.andThen(
    LogisticRegression[
      IO,
      Dense[DocumentComponents],
      Category,
      DocumentMeta
    ](
      config  = logisticConfig,
      backend = logisticBackend
    )
  )

The semantic brand makes this fail:

sealed trait ImageComponents
val imageClassifier:
  Learner[
    IO,
    Dense[ImageComponents],
    Category,
    DocumentMeta,
    Distribution[Category]
  ] = ???
sparsePca.andThen(imageClassifier)

Dense[DocumentComponents] is not Dense[ImageComponents], even when both happen to contain 64 doubles.

⸻

Dependency weight

The intended runtime graph is:

alder-kernel
└── cats-core
alder-linear-api
└── alder-kernel
com.acme:alder-sparse-pca-core
├── alder-kernel
└── alder-linear-api        optional
com.acme:alder-sparse-pca-ejml
├── alder-sparse-pca-core
└── EJML                    selected by the plugin author

Test-only:

alder-laws
├── Discipline
└── ScalaCheck

The following must not be dependencies of alder-kernel or alder-linear-api:

* Cats Effect;
* FS2;
* Arrow;
* Breeze;
* EJML;
* netlib-java;
* ONNX Runtime;
* JSON or YAML libraries;
* database drivers;
* tuning implementations;
* built-in model packages;
* native libraries.

Cats Core is the one deliberate foundational dependency. Trying to make the kernel dependency-free would likely cause Alder to reinvent EitherT, Eq, monoids, nonempty structures, syntax, and law machinery, while making Typelevel interoperability worse. One stable Cats dependency is preferable to a bespoke miniature Cats hidden inside an ML framework.

Alder itself should not decide whether sparse PCA uses EJML, BLAS, a native solver, CUDA, or pure Scala. That weight belongs to the implementation artifact that chose it.

⸻

Optional capabilities remain optional

Sparse PCA may expose component inspection without adding methods to every Pipe:

trait PrincipalComponents[A]:
  type Component
  def components(
    artifact: A
  ): IArray[Component]

The plugin supplies evidence:

given [X, S]:
    PrincipalComponents[SparsePcaModel[X, S]] with
  type Component = IArray[Double]
  def components(
    artifact: SparsePcaModel[X, S]
  ): IArray[IArray[Double]] =
    artifact.componentLoadings

It can separately provide:

given ArtifactCodec[SparsePcaModel[X, S]]
given Explain[SparsePcaModel[X, S], X]
given InverseTransform[SparsePcaModel[X, S]]

Not implementing one of those capabilities adds no dependency and creates no runtime “unsupported operation.”

⸻

Law testing is paid only during development

The implementation imports alder-laws in test scope:

class SparsePcaSuite
    extends munit.DisciplineSuite:
  checkAll(
    SparsePca,
    TransformTests(
      transform = sparsePca,
      inputs    = documentFeatureGenerator
    ).all
  )

It receives the standard laws for:

* deterministic fitting under a fixed seed and backend;
* input-only training replay;
* row preservation when lifted into a workflow;
* composition;
* audit completeness;
* finite successful output;
* codec behavior, when a codec is supplied.

Sparse PCA should also publish algorithm-specific laws. Those laws should compare the learned subspace or reconstruction behavior, not naively compare raw component matrices. PCA-like solutions can be equivalent under component sign changes, and some variants can also be equivalent under permutations. A law suite that treats equivalent bases as different would itself be lying.

alder-laws, Discipline, and ScalaCheck never enter the user’s runtime dependency graph.

⸻

The governing dependency rule

A third-party component pays for:

the protocol
+ the numeric representation it actually uses
+ the solver it explicitly selects

It does not pay for the rest of Alder.

A small JAR is not sufficient if its extension interface changes constantly, so Transform, FeatureMap, Learner, Pipe, FitContext, and the audit-construction protocol should also be treated as Alder’s most compatibility-sensitive API. New functionality should generally arrive through extension methods and capability typeclasses rather than by adding abstract methods to those traits.

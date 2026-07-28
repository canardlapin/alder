package alder.kernel

/** Framework-owned fitting context. A final class, never a trait: third
  * parties consume it and can never implement it, so it can gain compatible
  * methods without breaking plugins (D11).
  *
  * Seeds and stage paths derive from the root seed and stable stage ordinals,
  * so parenthesization of a composition cannot change them.
  */
final class FitContext private (
    val seed: Seed,
    val stagePath: StagePath,
    val plan: PlanFingerprint,
    val schema: SchemaFingerprint,
    val numericMode: NumericMode
):

  private[alder] def forChild(ordinal: Int): FitContext =
    new FitContext(
      seed.child(plan, ordinal),
      stagePath.child(ordinal),
      plan,
      schema,
      numericMode
    )

  /** The one way a leaf component turns an artifact into a [[Trained]] value.
    * The framework supplies stage path, data fingerprint, seed, numeric mode,
    * and plan/schema fingerprints; the plugin supplies only its
    * [[ComponentDescriptor]].
    */
  def complete[A, X](
      artifact: A,
      trainedOn: NonEmptyData[Use.Fit, X],
      component: ComponentDescriptor
  ): Trained[A] =
    new Trained(
      artifact,
      new Audit(
        plan = plan,
        data = trainedOn.fingerprint,
        schema = schema,
        seed = seed,
        backend = component.backend,
        numerics = numericMode,
        preparation =
          PreparationLineage.leaf(stagePath, PreparationScopeTag.Reusable),
        component = component,
        children = Vector.empty,
        refit = trainedOn.refit,
        shape = AuditShape.Leaf
      )
    )

  /** Lawful leaf finalizer for target-blind transforms.
    *
    * Audits the fitted pipe, replays it over the exact fitting rows, and
    * constructs [[Prepared]] internally so external plugins never need the
    * package-private Prepared constructor or replay factory.
    */
  def completeTransform[U <: Use.Fit, E, X, Z, P <: Pipe[X, E, Z]](
      artifact: P,
      trainedOn: NonEmptyData[U, X],
      component: ComponentDescriptor
  ): Either[Failure[E], Prepared[Preparation.Reusable, U, P, Z]] =
    val trained = complete(artifact, trainedOn, component)
    Prepared.replayed(
      trained,
      trainedOn,
      PreparationLineage.leaf(stagePath, PreparationScopeTag.Reusable)
    )

  /** Framework-internal: audit for a composed artifact, with child audits. */
  private[alder] def composite[A](
      artifact: A,
      trainedOn: NonEmptyData[Use.Fit, ?],
      component: ComponentDescriptor,
      preparation: PreparationLineage,
      children: Vector[Audit],
      shape: AuditShape = AuditShape.Composite
  ): Trained[A] =
    new Trained(
      artifact,
      new Audit(
        plan = plan,
        data = trainedOn.fingerprint,
        schema = schema,
        seed = seed,
        backend = component.backend,
        numerics = numericMode,
        preparation = preparation,
        component = component,
        children = children,
        refit = trainedOn.refit,
        shape = shape
      )
    )

object FitContext:
  /** Root context for a fitting run. */
  def root(
      seed: Seed,
      plan: PlanFingerprint,
      schema: SchemaFingerprint,
      numericMode: NumericMode
  ): FitContext =
    new FitContext(seed, StagePath.root, plan, schema, numericMode)

/** Descriptors for Alder's own composition combinators. */
private[alder] object AlderComponents:
  private val backend =
    BackendFingerprint("alder", "0.1.0-SNAPSHOT", AuditValue.record())

  private def descriptor(kind: String): ComponentDescriptor =
    ComponentDescriptor(
      id = ComponentId(kind),
      version = ComponentVersion("0.1.0-SNAPSHOT"),
      parameters = AuditValue.record(),
      backend = backend
    )

  val composeTransform: ComponentDescriptor =
    descriptor("alder.compose.transform")
  val composeFeatureMap: ComponentDescriptor =
    descriptor("alder.compose.feature-map")
  val mapFeatureOutput: ComponentDescriptor =
    descriptor("alder.map.feature-output")

  def mapFeatureOutputNamed(
      name: String,
      version: String
  ): ComponentDescriptor =
    ComponentDescriptor(
      id = ComponentId("alder.map.feature-output"),
      version = ComponentVersion("0.1.0-SNAPSHOT"),
      parameters = AuditValue.record(
        "function" -> AuditValue.text(name),
        "functionVersion" -> AuditValue.text(version),
        "identity" -> AuditValue.text("named")
      ),
      backend = backend
    )

  def mapFeatureOutputAnonymous: ComponentDescriptor =
    ComponentDescriptor(
      id = ComponentId("alder.map.feature-output"),
      version = ComponentVersion("0.1.0-SNAPSHOT"),
      parameters = AuditValue.record(
        "identity" -> AuditValue.text("application-defined")
      ),
      backend = backend
    )

  val composeFoldEncoder: ComponentDescriptor =
    descriptor("alder.compose.fold-encoder")
  val learnedWith: ComponentDescriptor =
    descriptor("alder.compose.learnedWith")

  def crossFitted(
      resampler: ProtocolFingerprint,
      foldCount: Int
  ): ComponentDescriptor =
    ComponentDescriptor(
      id = ComponentId("alder.prepare.cross-fitted"),
      version = ComponentVersion("0.1.0-SNAPSHOT"),
      parameters = AuditValue.record(
        "resamplerPolicy" -> AuditValue.text(resampler.policy.toString),
        "resamplerDigest" -> AuditValue.text(resampler.digest),
        "foldCount" -> AuditValue.integer(foldCount.toLong)
      ),
      backend = backend
    )

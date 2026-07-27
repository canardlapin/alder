package alder.laws

import alder.kernel.*
import cats.kernel.Eq
import org.scalacheck.Prop
import org.typelevel.discipline.Laws

final case class FingerprintSnapshot(
    policy: FingerprintPolicy,
    digest: String
)

final case class CrossFitFoldSnapshot(
    index: Int,
    analysis: FingerprintSnapshot,
    assessment: FingerprintSnapshot,
    fittedState: LineageSnapshot
)

final case class CrossFitSnapshot(
    resamplerPolicy: FingerprintPolicy,
    resamplerDigest: String,
    seed: Seed,
    assignment: FingerprintSnapshot,
    folds: Vector[CrossFitFoldSnapshot],
    serving: LineageSnapshot
)

final case class LineageSnapshot(
    stage: StagePath,
    scope: PreparationScopeTag,
    children: Vector[LineageSnapshot],
    crossFit: Option[CrossFitSnapshot]
)

final case class ObservedSourceSnapshot(
    role: ObservedSourceRole,
    fingerprint: FingerprintSnapshot
)

final case class RefitSnapshot(
    sources: Vector[ObservedSourceSnapshot],
    receipt: EvaluationReceiptId,
    selection: Option[SelectionReceiptId],
    claim: RefitEvaluationClaim
)

final case class AuditSnapshot(
    plan: PlanFingerprint,
    data: FingerprintSnapshot,
    schema: SchemaFingerprint,
    seed: Seed,
    numerics: NumericMode,
    preparation: LineageSnapshot,
    componentId: ComponentId,
    componentVersion: ComponentVersion,
    parameters: AuditValue,
    backendId: String,
    backendVersion: String,
    backendDetails: AuditValue,
    children: Vector[AuditSnapshot],
    refit: Option[RefitSnapshot]
)

object AuditSnapshot:
  private val equality: Eq[AuditSnapshot] = Eq.fromUniversalEquals

  def equivalent(left: AuditSnapshot, right: AuditSnapshot): Boolean =
    equality.eqv(left, right)

  def apply(audit: Audit): AuditSnapshot =
    AuditSnapshot(
      plan = audit.plan,
      data = fingerprint(audit.data),
      schema = audit.schema,
      seed = audit.seed,
      numerics = audit.numerics,
      preparation = lineage(audit.preparation),
      componentId = audit.component.id,
      componentVersion = audit.component.version,
      parameters = audit.component.parameters,
      backendId = audit.backend.id,
      backendVersion = audit.backend.version,
      backendDetails = audit.backend.details,
      children = audit.children.map(apply),
      refit = audit.refit.map { value =>
        RefitSnapshot(
          sources = value.sources.map(source =>
            ObservedSourceSnapshot(
              source.role,
              fingerprint(source.fingerprint)
            )
          ),
          receipt = value.receipt,
          selection = value.selectionReceipt,
          claim = value.claim
        )
      }
    )

  private def fingerprint(value: DataFingerprint): FingerprintSnapshot =
    FingerprintSnapshot(value.policy, value.digest)

  private def lineage(value: PreparationLineage): LineageSnapshot =
    LineageSnapshot(
      stage = value.stage,
      scope = value.scope,
      children = value.children.map(lineage),
      crossFit = value.crossFit.map { receipt =>
        CrossFitSnapshot(
          resamplerPolicy = receipt.resampler.policy,
          resamplerDigest = receipt.resampler.digest,
          seed = receipt.seed,
          assignment = fingerprint(receipt.assignment),
          folds = receipt.folds.map { fold =>
            CrossFitFoldSnapshot(
              index = fold.index,
              analysis = fingerprint(fold.analysis),
              assessment = fingerprint(fold.assessment),
              fittedState = lineage(fold.fittedState)
            )
          },
          serving = lineage(receipt.serving)
        )
      }
    )

private object LawRows:
  def collect[U <: Use, A](
      data: NonEmptyData[U, A]
  ): Vector[(RowId, A)] =
    data.data.foldRows(Vector.empty[(RowId, A)])((rows, id, value) =>
      rows :+ (id, value)
    )

  def same[A](
      left: Vector[(RowId, A)],
      right: Vector[(RowId, A)],
      eqA: Eq[A]
  ): Boolean =
    left.length == right.length &&
      left.zip(right).forall { (leftRow, rightRow) =>
        leftRow._1 == rightRow._1 && eqA.eqv(leftRow._2, rightRow._2)
      }

/** Repeatable successful-fit fixture for the laws of a Transform. */
trait TransformLaws[
    U <: Use.Fit,
    X,
    Z,
    FitE,
    RunE,
    P <: Pipe[X, RunE, Z]
]:
  def original: NonEmptyData[U, X]
  def fitOnce
      : Either[
        Failure[FitE],
        Prepared[Preparation.Reusable, U, P, Z]
      ]

final class TransformTests[
    U <: Use.Fit,
    X,
    Z,
    FitE,
    RunE,
    P <: Pipe[X, RunE, Z]
](
    laws: TransformLaws[U, X, Z, FitE, RunE, P]
) extends Laws:
  def all(using
      eqZ: Eq[Z],
      eqRun: Eq[Either[Failure[RunE], Z]]
  ): RuleSet =
    new DefaultRuleSet(
      "transform",
      None,
      "prepared rows are fitted-pipe replay with identical RowIds" ->
        Prop(replayAndIdentity(eqRun)),
      "equal fit inputs produce equal behavior and audit" ->
        Prop(deterministic(eqZ, eqRun))
    )

  private def replayAndIdentity(
      eqRun: Eq[Either[Failure[RunE], Z]]
  ): Boolean =
    laws.fitOnce match
      case Left(_) => false
      case Right(prepared) =>
        val originalRows = LawRows.collect(laws.original)
        val preparedRows = LawRows.collect(prepared.rows)
        originalRows.length == preparedRows.length &&
        originalRows.zip(preparedRows).forall { (original, transformed) =>
          original._1 == transformed._1 &&
          eqRun.eqv(
            prepared.fitted.artifact.run(original._2),
            Right(transformed._2)
          )
        }

  private def deterministic(
      eqZ: Eq[Z],
      eqRun: Eq[Either[Failure[RunE], Z]]
  ): Boolean =
    (laws.fitOnce, laws.fitOnce) match
      case (Right(first), Right(second)) =>
        val firstRows = LawRows.collect(first.rows)
        val secondRows = LawRows.collect(second.rows)
        LawRows.same(firstRows, secondRows, eqZ) &&
        AuditSnapshot.equivalent(
          AuditSnapshot(first.fitted.audit),
          AuditSnapshot(second.fitted.audit)
        ) &&
        LawRows.collect(laws.original).forall { (_, input) =>
          eqRun.eqv(
            first.fitted.artifact.run(input),
            second.fitted.artifact.run(input)
          )
        }
      case _ => false

/** Repeatable successful-fit fixture for FeatureMap protocol laws. */
trait FeatureMapLaws[
    S <: Preparation.LearnerReady,
    U <: Use.Fit,
    X,
    Y,
    M,
    Z,
    FitE,
    RunE,
    P <: Pipe[X, RunE, Z]
]:
  def original: NonEmptyData[U, Example[X, Y, M]]
  def servingInputs: Vector[X]
  def fitOnce
      : Either[
        Failure[FitE],
        Prepared[S, U, P, Example[Z, Y, M]]
      ]

final class FeatureMapTests[
    S <: Preparation.LearnerReady,
    U <: Use.Fit,
    X,
    Y,
    M,
    Z,
    FitE,
    RunE,
    P <: Pipe[X, RunE, Z]
](
    laws: FeatureMapLaws[S, U, X, Y, M, Z, FitE, RunE, P]
) extends Laws:
  def all(using
      eqZ: Eq[Z],
      eqY: Eq[Y],
      eqM: Eq[M],
      eqRun: Eq[Either[Failure[RunE], Z]]
  ): RuleSet =
    new DefaultRuleSet(
      "featureMap",
      None,
      "RowIds, targets, and metadata are preserved" ->
        Prop(preservesSupervisedFields(eqY, eqM)),
      "equal fit inputs produce equal prepared rows and audit" ->
        Prop(deterministic(eqZ, eqY, eqM, eqRun))
    )

  private def preservesSupervisedFields(
      eqY: Eq[Y],
      eqM: Eq[M]
  ): Boolean =
    laws.fitOnce match
      case Left(_) => false
      case Right(prepared) =>
        val originalRows = LawRows.collect(laws.original)
        val preparedRows = LawRows.collect(prepared.rows)
        originalRows.length == preparedRows.length &&
        originalRows.zip(preparedRows).forall { (original, transformed) =>
          original._1 == transformed._1 &&
          eqY.eqv(original._2.target, transformed._2.target) &&
          eqM.eqv(original._2.meta, transformed._2.meta)
        }

  private def deterministic(
      eqZ: Eq[Z],
      eqY: Eq[Y],
      eqM: Eq[M],
      eqRun: Eq[Either[Failure[RunE], Z]]
  ): Boolean =
    (laws.fitOnce, laws.fitOnce) match
      case (Right(first), Right(second)) =>
        val firstRows = LawRows.collect(first.rows)
        val secondRows = LawRows.collect(second.rows)
        firstRows.length == secondRows.length &&
        firstRows.zip(secondRows).forall { (left, right) =>
          left._1 == right._1 &&
          eqZ.eqv(left._2.input, right._2.input) &&
          eqY.eqv(left._2.target, right._2.target) &&
          eqM.eqv(left._2.meta, right._2.meta)
        } &&
        AuditSnapshot.equivalent(
          AuditSnapshot(first.fitted.audit),
          AuditSnapshot(second.fitted.audit)
        ) &&
        laws.servingInputs.forall(input =>
          eqRun.eqv(
            first.fitted.artifact.run(input),
            second.fitted.artifact.run(input)
          )
        )
      case _ => false

/** Instrumented direct own-target exclusion law for cross-fitted outputs. */
final class CrossFitLeakageTests[E, Z](
    preparedRows: () => Either[Failure[E], Vector[(RowId, Z)]],
    visibleRows: Z => Set[RowId]
) extends Laws:
  def all: RuleSet =
    new DefaultRuleSet(
      "featureMap.crossFitted",
      None,
      "the state producing a row did not observe that RowId" ->
        Prop(
          preparedRows() match
            case Left(_) => false
            case Right(rows) =>
              rows.forall { (id, value) =>
                !visibleRows(value).contains(id)
              }
        )
    )

/** Repeatable successful-fit fixture for terminal Learner laws. */
trait LearnerLaws[X, FitE, RunE, P, Model <: Pipe[X, RunE, P]]:
  def fitOnce: Either[Failure[FitE], Trained[Model]]
  def inputs: Vector[X]

final class LearnerTests[X, FitE, RunE, P, Model <: Pipe[X, RunE, P]](
    laws: LearnerLaws[X, FitE, RunE, P, Model]
) extends Laws:
  def all(using eqRun: Eq[Either[Failure[RunE], P]]): RuleSet =
    new DefaultRuleSet(
      "learner",
      None,
      "equal fit inputs produce equal predictions and audit" ->
        Prop(
          (laws.fitOnce, laws.fitOnce) match
            case (Right(first), Right(second)) =>
              AuditSnapshot.equivalent(
                AuditSnapshot(first.audit),
                AuditSnapshot(second.audit)
              ) &&
              laws.inputs.forall(input =>
                eqRun.eqv(
                  first.artifact.run(input),
                  second.artifact.run(input)
                )
              )
            case _ => false
        )
    )

/** Serialization laws for a fitted Pipe artifact. */
final class ArtifactCodecTests[
    X,
    E,
    P,
    A <: Pipe[X, E, P]
](
    codec: ArtifactCodec[A],
    trained: Trained[A],
    inputs: Vector[X]
) extends Laws:
  def all(using eqRun: Eq[Either[Failure[E], P]]): RuleSet =
    new DefaultRuleSet(
      "artifactCodec",
      None,
      "decode(encode(model)) preserves predictions and audit" ->
        Prop(
          codec.encode(trained).flatMap(codec.decode) match
            case Left(_) => false
            case Right(decoded) =>
              AuditSnapshot.equivalent(
                AuditSnapshot(trained.audit),
                AuditSnapshot(decoded.audit)
              ) &&
              inputs.forall(input =>
                eqRun.eqv(
                  trained.artifact.run(input),
                  decoded.artifact.run(input)
                )
              )
        )
    )

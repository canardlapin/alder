package alder.application

import alder.kernel.*
import alder.metrics.*

private[application] object ReceiptHash:
  private val offset = 0xcbf29ce484222325L
  private val prime = 0x100000001b3L

  def evaluation(
      plan: PlanFingerprint,
      prediction: PredictionReceiptId,
      descriptor: MetricDescriptor,
      scored: DataFingerprint
  ): EvaluationReceiptId =
    EvaluationReceiptId(
      digest(
        Vector(
          "alder.evaluation-receipt-v1",
          fingerprintPolicy(plan.policy),
          plan.digest,
          prediction.render,
          metricDescriptor(descriptor),
          fingerprintPolicy(scored.policy),
          scored.digest
        )
      )
    )

  def selection(
      plan: PlanFingerprint,
      evaluation: EvaluationReceiptId,
      descriptor: MetricDescriptor,
      score: AuditValue,
      direction: ObjectiveDirection,
      policy: SelectionPolicy,
      candidate: Audit
  ): SelectionReceiptId =
    SelectionReceiptId(
      digest(
        Vector(
          "alder.selection-receipt-v1",
          fingerprintPolicy(plan.policy),
          plan.digest,
          evaluation.render,
          metricDescriptor(descriptor),
          auditValue(score),
          objectiveDirection(direction),
          selectionPolicy(policy),
          trainedAudit(candidate)
        )
      )
    )

  def trainedAudit(audit: Audit): String =
    framed(
      Vector(
        "candidate-audit-v1",
        fingerprintPolicy(audit.plan.policy),
        audit.plan.digest,
        fingerprintPolicy(audit.data.policy),
        audit.data.digest,
        fingerprintPolicy(audit.schema.policy),
        audit.schema.digest,
        audit.seed.value.toString,
        audit.backend.id,
        audit.backend.version,
        auditValue(audit.backend.details),
        audit.component.id.render,
        audit.component.version.render,
        auditValue(audit.component.parameters),
        audit.numerics match
          case NumericMode.Deterministic => "deterministic"
          case NumericMode.FastMath      => "fast-math"
          case NumericMode.NonDeterministic(description) =>
            s"non-deterministic:$description"
      )
    )

  def scoredData(
      predicted: DataFingerprint,
      prediction: DataFingerprint,
      descriptor: MetricDescriptor
  ): DataFingerprint =
    new DataFingerprint(
      FingerprintPolicy.Summary(
        "alder.scored-evaluation-fnv1a64-v1"
      ),
      digest(
        Vector(
          "alder.scored-evaluation-v1",
          fingerprintPolicy(predicted.policy),
          predicted.digest,
          fingerprintPolicy(prediction.policy),
          prediction.digest,
          metricDescriptor(descriptor)
        )
      )
    )

  def phaseSeed(
      root: Seed,
      plan: PlanFingerprint,
      phase: ExperimentPhase
  ): Seed =
    val initial = hash(
      framed(
        Vector(
          "alder.experiment-phase-seed-v1",
          root.value.toString,
          fingerprintPolicy(plan.policy),
          plan.digest,
          phaseTag(phase)
        )
      )
    )
    Seed(splitmixFinalizer(initial))

  private def metricDescriptor(value: MetricDescriptor): String =
    val objective =
      value.objective match
        case None => "reporting"
        case Some(details) =>
          framed(
            Vector(
              "objective",
              objectiveDirection(details.direction),
              details.scoreEncodingVersion
            )
          )
    framed(
      Vector(
        value.id.render,
        value.version.render,
        auditValue(value.parameters),
        numericPolicy(value.numericPolicy),
        objective
      )
    )

  private def auditValue(value: AuditValue): String =
    value match
      case AuditValue.Integer(number) =>
        framed(Vector("integer", number.toString))
      case AuditValue.Decimal(number) =>
        framed(
          Vector(
            "decimal-binary64",
            java.lang.Double
              .doubleToRawLongBits(number)
              .toHexString
          )
        )
      case AuditValue.Text(text) =>
        framed(Vector("text", text))
      case AuditValue.Bool(boolean) =>
        framed(Vector("bool", boolean.toString))
      case AuditValue.Sequence(values) =>
        framed("sequence" +: values.map(auditValue))
      case AuditValue.Record(fields) =>
        framed(
          "record" +: fields.flatMap { (name, field) =>
            Vector(name, auditValue(field))
          }
        )

  private def fingerprintPolicy(value: FingerprintPolicy): String =
    value match
      case FingerprintPolicy.ContentDigest(algorithm) =>
        framed(Vector("content-digest", algorithm))
      case FingerprintPolicy.SourceIdentity(uri, version) =>
        framed(Vector("source-identity", uri, version))
      case FingerprintPolicy.Summary(policyId) =>
        framed(Vector("summary", policyId))

  private def numericPolicy(value: MetricNumericPolicy): String =
    value match
      case MetricNumericPolicy.Reproducible => "reproducible"

  private def objectiveDirection(value: ObjectiveDirection): String =
    value match
      case ObjectiveDirection.Minimize => "minimize"
      case ObjectiveDirection.Maximize => "maximize"

  private def selectionPolicy(value: SelectionPolicy): String =
    value match
      case SelectionPolicy.SingleCandidate => "single-candidate"

  private def phaseTag(value: ExperimentPhase): String =
    value match
      case ExperimentPhase.Split         => "split"
      case ExperimentPhase.CandidateFit  => "candidate-fit"
      case ExperimentPhase.Validation    => "validation-protocol"
      case ExperimentPhase.SelectedRefit => "selected-refit"
      case ExperimentPhase.Test          => "test-protocol"
      case ExperimentPhase.DeploymentRefit =>
        "deployment-refit"

  private def digest(values: Vector[String]): String =
    hex(hash(framed(values)))

  private def framed(values: Vector[String]): String =
    values.map(value => s"${value.length}:$value").mkString

  private def hash(value: String): Long =
    var result = offset
    var index = 0
    while index < value.length do
      val codeUnit = value.charAt(index).toLong
      result = (result ^ ((codeUnit >>> 8) & 0xffL)) * prime
      result = (result ^ (codeUnit & 0xffL)) * prime
      index += 1
    result

  private def hex(value: Long): String =
    val digits = "0123456789abcdef"
    val builder = new StringBuilder(16)
    var shift = 60
    while shift >= 0 do
      builder.append(
        digits.charAt(((value >>> shift) & 0x0fL).toInt)
      )
      shift -= 4
    builder.result()

  private def splitmixFinalizer(input: Long): Long =
    var value = input
    value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L
    value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL
    value ^ (value >>> 31)

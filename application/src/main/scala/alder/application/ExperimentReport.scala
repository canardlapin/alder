package alder.application

import alder.data.*
import alder.kernel.*
import alder.metrics.*

/** Application-facing identity of the experiment route summarized by a
  * [[ExperimentReport]].
  */
enum ExperimentReportRoute derives CanEqual:
  case Validation
  case TrainValidationTest
  case PrecommittedHoldout

/** Route-specific partition sizes. The cases retain which held-out roles
  * existed instead of encoding absent roles with sentinel counts.
  */
enum ExperimentPartitions derives CanEqual:
  case Validation(train: Long, validation: Long)
  case TrainValidationTest(train: Long, validation: Long, test: Long)
  case PrecommittedHoldout(train: Long, test: Long)

  def route: ExperimentReportRoute =
    this match
      case Validation(_, _) => ExperimentReportRoute.Validation
      case TrainValidationTest(_, _, _) =>
        ExperimentReportRoute.TrainValidationTest
      case PrecommittedHoldout(_, _) =>
        ExperimentReportRoute.PrecommittedHoldout

/** One fitted component projected from an audit node. The descriptor and its
  * exact backend fingerprint remain structured values.
  */
final class FittedComponent private[application] (
    val descriptor: ComponentDescriptor
):
  def backend: BackendFingerprint = descriptor.backend

/** Stable, read-only projection of successful experiment evidence.
  *
  * This is not a replacement audit. Lifecycle results continue to retain the
  * exact audit, evaluation receipt, predictions, score, and fitted model.
  */
final class ExperimentReport[S] private[application] (
    val partitions: ExperimentPartitions,
    val metric: MetricDescriptor,
    val score: S,
    val candidate: FittedComponent,
    val components: Vector[FittedComponent],
    val plan: PlanFingerprint,
    val seed: Seed
):
  def route: ExperimentReportRoute = partitions.route

object ExperimentReport:
  private[application] def validation[A, S](
      split: ValidationSplit[A],
      metric: MetricDescriptor,
      score: S,
      audit: Audit,
      seed: Seed
  ): ExperimentReport[S] =
    from(
      ExperimentPartitions.Validation(
        train = split.train.size,
        validation = split.validation.size
      ),
      metric,
      score,
      audit,
      seed
    )

  private[application] def trainValidationTest[A, S](
      split: TrainValidationTestSplit[A],
      metric: MetricDescriptor,
      score: S,
      audit: Audit,
      seed: Seed
  ): ExperimentReport[S] =
    from(
      ExperimentPartitions.TrainValidationTest(
        train = split.train.size,
        validation = split.validation.size,
        test = split.test.size
      ),
      metric,
      score,
      audit,
      seed
    )

  private[application] def precommitted[A, S](
      split: Holdout[A],
      metric: MetricDescriptor,
      score: S,
      audit: Audit,
      seed: Seed
  ): ExperimentReport[S] =
    from(
      ExperimentPartitions.PrecommittedHoldout(
        train = split.train.size,
        test = split.test.size
      ),
      metric,
      score,
      audit,
      seed
    )

  private def from[S](
      partitions: ExperimentPartitions,
      metric: MetricDescriptor,
      score: S,
      audit: Audit,
      seed: Seed
  ): ExperimentReport[S] =
    new ExperimentReport(
      partitions,
      metric,
      score,
      component(audit),
      leafAudits(audit).map(component),
      audit.plan,
      seed
    )

  private def component(audit: Audit): FittedComponent =
    new FittedComponent(audit.component)

  private def leafAudits(audit: Audit): Vector[Audit] =
    if audit.children.isEmpty then Vector(audit)
    else audit.children.flatMap(leafAudits)

  /** Renders a report at an application edge. The score renderer is explicit
    * because Alder does not erase an arbitrary score type to `String`.
    */
  extension [S](report: ExperimentReport[S])
    def render(renderScore: S => String): String =
      val partitionLines =
        report.partitions match
          case ExperimentPartitions.Validation(train, validation) =>
            Vector(
              s"Training rows: $train",
              s"Validation rows: $validation"
            )
          case ExperimentPartitions.TrainValidationTest(
                train,
                validation,
                test
              ) =>
            Vector(
              s"Training rows: $train",
              s"Validation rows: $validation",
              s"Test rows: $test"
            )
          case ExperimentPartitions.PrecommittedHoldout(train, test) =>
            Vector(s"Training rows: $train", s"Test rows: $test")
      val componentIds =
        report.components.map(_.descriptor.id.render).mkString(" -> ")
      val backends =
        report.components
          .map(component => s"${component.backend.id}@${component.backend.version}")
          .mkString(", ")
      (Vector(s"Route: ${renderRoute(report.route)}") ++
        partitionLines ++
        Vector(
          s"Metric: ${report.metric.id.render}",
          s"Score: ${renderScore(report.score)}",
          s"Candidate: ${report.candidate.descriptor.id.render}",
          s"Components: $componentIds",
          s"Backends: $backends",
          s"Run: ${report.plan.render}",
          s"Seed: ${report.seed.value}"
        )).mkString("\n")

  private def renderRoute(route: ExperimentReportRoute): String =
    route match
      case ExperimentReportRoute.Validation => "validation"
      case ExperimentReportRoute.TrainValidationTest =>
        "train-validation-test"
      case ExperimentReportRoute.PrecommittedHoldout =>
        "precommitted-holdout"

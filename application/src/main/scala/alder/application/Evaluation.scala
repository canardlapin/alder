package alder.application

import alder.data.*
import alder.kernel.*
import alder.metrics.*

/** Prediction-stage or metric-stage failure of scored evaluation. */
enum ScoredEvaluationError[+RunE]:
  case Prediction(error: EvaluationError[RunE])
  case Metric(error: MetricError)

/** Role-typed evidence that both prediction and metric finishing completed. */
final class EvaluationReceipt[
    U <: Use.Evaluation
] private[application] (
    val id: EvaluationReceiptId,
    val prediction: PredictionReceiptId,
    val sources: Vector[ObservedSource],
    val role: EvaluationRole,
    val metric: MetricDescriptor,
    val scored: DataFingerprint,
    private[alder] val priorSelection: Option[SelectionReceiptId],
    private[alder] val authority: PromotionAuthority[U]
)

/** Held-out observations, their typed score, and the evidence required by
  * later lifecycle transitions.
  */
final class ScoredEvaluation[
    U <: Use.Evaluation,
    X,
    Y,
    M,
    P,
    S,
    Mt <: Metric[Scored[Y, P, M], S]
] private[application] (
    val scored: NonEmptyData[U, Scored[Y, P, M]],
    val score: S,
    val metric: Mt,
    val receipt: EvaluationReceipt[U],
    val allObserved: AllObserved[U, Example[X, Y, M]],
    val plan: PlanFingerprint
)

object Evaluation:
  /** Predicts every held-out example and finishes one declared metric.
    *
    * Truth, metadata, and RowId are retained in the scored data. A metric
    * failure returns no evaluation receipt.
    */
  def scored[
      U <: Use.Evaluation,
      X,
      Y,
      M,
      RunE,
      P,
      S,
      Mt <: Metric[Scored[Y, P, M], S],
      ModelPipe <: Pipe[X, RunE, P]
  ](
      trained: Trained[ModelPipe],
      sources: EvaluationSources[U, Example[X, Y, M]],
      metric: Mt
  ): Either[
    ScoredEvaluationError[RunE],
    ScoredEvaluation[U, X, Y, M, P, S, Mt]
  ] =
    Prediction
      .runBy(trained, sources)(_.input)
      .left
      .map(ScoredEvaluationError.Prediction(_))
      .flatMap { predicted =>
        val scoredRows =
          predicted.predicted.data.foldRows(
            Vector.empty[(RowId, Scored[Y, P, M])]
          ) { (rows, id, value) =>
            rows :+
              (id ->
                Scored(
                  value.observation.target,
                  value.prediction,
                  value.observation.meta
                ))
          }
        val scoredFingerprint = ReceiptHash.scoredData(
          predicted.predicted.fingerprint,
          predicted.predictions.fingerprint,
          metric.descriptor
        )
        val scoredData =
          new NonEmptyData[U, Scored[Y, P, M]](
            new RowVectorData(scoredRows, scoredFingerprint)
          )
        val accumulated =
          scoredData.data.foldRows(metric.accumulator.empty) {
            (current, _, value) =>
              metric.accumulator.combine(
                current,
                metric.observe(value)
              )
          }
        metric
          .finish(accumulated)
          .left
          .map(ScoredEvaluationError.Metric(_))
          .map { score =>
            val id = ReceiptHash.evaluation(
              trained.audit.plan,
              predicted.receipt.id,
              metric.descriptor,
              scoredFingerprint
            )
            val receipt = new EvaluationReceipt[U](
              id,
              predicted.receipt.id,
              predicted.receipt.sources,
              predicted.receipt.role,
              metric.descriptor,
              scoredFingerprint,
              predicted.receipt.priorSelection,
              predicted.authority
            )
            new ScoredEvaluation(
              scoredData,
              score,
              metric,
              receipt,
              predicted.allObserved,
              trained.audit.plan
            )
          }
      }

package alder.metrics

import cats.kernel.CommutativeMonoid

enum MetricError derives CanEqual:
  case Empty
  case NonFiniteTruth(value: Double)
  case NonFinitePrediction(value: Double)
  case NonFiniteResidual(truth: Double, prediction: Double)
  case NonFiniteSquaredError(value: Double)
  case NonFiniteWeight(value: Double)
  case NegativeWeight(value: Double)
  case NonFiniteWeightedValue(value: Double, weight: Double)
  case ZeroTotalWeight
  case NonFiniteResult

/** A streaming metric whose accumulator can be combined in any partition
  * shape. Numerical metrics use Alder's exact superaccumulator by default.
  */
trait Metric[-A, +S]:
  type Acc
  given accumulator: CommutativeMonoid[Acc]

  def observe(value: A): Acc
  def finish(accumulated: Acc): Either[MetricError, S]

  final def accumulate(values: IterableOnce[A]): Acc =
    values.iterator.foldLeft(accumulator.empty) { (accumulated, value) =>
      accumulator.combine(accumulated, observe(value))
    }

  final def evaluate(values: IterableOnce[A]): Either[MetricError, S] =
    finish(accumulate(values))

opaque type RootMeanSquaredError = Double

object RootMeanSquaredError:
  private[metrics] def apply(value: Double): RootMeanSquaredError = value

  extension (value: RootMeanSquaredError) def value: Double = value

  given CanEqual[RootMeanSquaredError, RootMeanSquaredError] =
    CanEqual.derived

opaque type Accuracy = Double

object Accuracy:
  private[metrics] def apply(value: Double): Accuracy = value

  extension (value: Accuracy) def value: Double = value

  given CanEqual[Accuracy, Accuracy] = CanEqual.derived

private[metrics] sealed trait MetricProblem derives CanEqual:
  private[metrics] def rank: Int
  private[metrics] def firstBits: Long
  private[metrics] def secondBits: Option[Long]

private[metrics] object MetricProblem:
  private final case class Truth(firstBits: Long) extends MetricProblem:
    val rank: Int = 1
    val secondBits: Option[Long] = None

  private final case class Prediction(firstBits: Long)
      extends MetricProblem:
    val rank: Int = 2
    val secondBits: Option[Long] = None

  private final case class Residual(
      firstBits: Long,
      predictionBits: Long
  ) extends MetricProblem:
    val rank: Int = 3
    val secondBits: Option[Long] = Some(predictionBits)

  private final case class Squared(firstBits: Long) extends MetricProblem:
    val rank: Int = 4
    val secondBits: Option[Long] = None

  private final case class Weight(firstBits: Long) extends MetricProblem:
    val rank: Int = 5
    val secondBits: Option[Long] = None

  private final case class NegativeWeight(firstBits: Long)
      extends MetricProblem:
    val rank: Int = 6
    val secondBits: Option[Long] = None

  private final case class Weighted(
      firstBits: Long,
      weightBits: Long
  ) extends MetricProblem:
    val rank: Int = 7
    val secondBits: Option[Long] = Some(weightBits)

  def truth(value: Double): MetricProblem =
    Truth(bits(value))

  def prediction(value: Double): MetricProblem =
    Prediction(bits(value))

  def residual(truth: Double, prediction: Double): MetricProblem =
    Residual(bits(truth), bits(prediction))

  def squared(value: Double): MetricProblem =
    Squared(bits(value))

  def weight(value: Double): MetricProblem =
    Weight(bits(value))

  def negativeWeight(value: Double): MetricProblem =
    NegativeWeight(bits(value))

  def weighted(value: Double, weight: Double): MetricProblem =
    Weighted(bits(value), bits(weight))

  def toError(problem: MetricProblem): MetricError =
    problem match
      case Truth(value) =>
        MetricError.NonFiniteTruth(fromBits(value))
      case Prediction(value) =>
        MetricError.NonFinitePrediction(fromBits(value))
      case Residual(truth, prediction) =>
        MetricError.NonFiniteResidual(
          fromBits(truth),
          fromBits(prediction)
        )
      case Squared(value) =>
        MetricError.NonFiniteSquaredError(fromBits(value))
      case Weight(value) =>
        MetricError.NonFiniteWeight(fromBits(value))
      case NegativeWeight(value) =>
        MetricError.NegativeWeight(fromBits(value))
      case Weighted(value, weight) =>
        MetricError.NonFiniteWeightedValue(
          fromBits(value),
          fromBits(weight)
        )

  given Ordering[MetricProblem] with
    def compare(left: MetricProblem, right: MetricProblem): Int =
      val kindOrder = left.rank.compare(right.rank)
      if kindOrder != 0 then kindOrder
      else
        val firstOrder = compareBits(left.firstBits, right.firstBits)
        if firstOrder != 0 then firstOrder
        else compareOptionalBits(left.secondBits, right.secondBits)

  private def bits(value: Double): Long =
    java.lang.Double.doubleToRawLongBits(value)

  private def fromBits(value: Long): Double =
    java.lang.Double.longBitsToDouble(value)

  private def compareBits(left: Long, right: Long): Int =
    java.lang.Long.compare(left ^ Long.MinValue, right ^ Long.MinValue)

  private def compareOptionalBits(
      left: Option[Long],
      right: Option[Long]
  ): Int =
    (left, right) match
      case (None, None)               => 0
      case (None, Some(_))            => -1
      case (Some(_), None)            => 1
      case (Some(left), Some(right)) => compareBits(left, right)

private[metrics] object MetricProblems:
  val empty: Set[MetricProblem] = Set.empty

  def first(
      problems: Set[MetricProblem]
  ): Option[MetricError] =
    problems.minOption.map(MetricProblem.toError)

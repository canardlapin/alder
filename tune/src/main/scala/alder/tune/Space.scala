package alder.tune

import alder.kernel.Seed
import cats.Applicative

/** Invalid bounds for a numeric search space. */
enum NumericSpaceError derives CanEqual:
  case NonPositiveDouble(value: Double)
  case NonPositiveInt(value: Int)
  case ReversedBounds(minimum: Double, maximum: Double)
  case ReversedIntBounds(minimum: Int, maximum: Int)

/** A finite `Double` strictly greater than zero. */
opaque type PositiveDouble = Double

object PositiveDouble:
  /** Validates a finite, strictly positive value. */
  def create(value: Double): Either[NumericSpaceError, PositiveDouble] =
    if value.isFinite && value > 0.0 then Right(value)
    else Left(NumericSpaceError.NonPositiveDouble(value))

  extension (value: PositiveDouble)
    def toDouble: Double = value

  private[tune] def fromValidated(value: Double): PositiveDouble = value

  given CanEqual[PositiveDouble, PositiveDouble] = CanEqual.derived

/** An `Int` strictly greater than zero. */
opaque type PositiveInt = Int

object PositiveInt:
  /** Validates a strictly positive value. */
  def create(value: Int): Either[NumericSpaceError, PositiveInt] =
    if value > 0 then Right(value)
    else Left(NumericSpaceError.NonPositiveInt(value))

  val one: PositiveInt = 1

  extension (value: PositiveInt)
    def toInt: Int = value

  private[tune] def fromValidated(value: Int): PositiveInt = value

  given CanEqual[PositiveInt, PositiveInt] = CanEqual.derived

/** A typed, compositional search space.
  *
  * Its `Applicative` instance forms Cartesian products, allowing related
  * parameters to be assembled into a domain configuration without maps or
  * stringly typed parameter names.
  */
sealed trait Space[+A]:
  /** Transforms every candidate while preserving the underlying search shape. */
  final def map[B](f: A => B): Space[B] =
    Space.Mapped(this, f)

object Space:
  private final case class Constant[A](value: A) extends Space[A]
  private final case class Choice[A](values: Vector[A]) extends Space[A]
  private final case class IntegerRange(
      minimum: PositiveInt,
      maximum: PositiveInt
  ) extends Space[PositiveInt]
  private final case class LogUniform(
      minimum: PositiveDouble,
      maximum: PositiveDouble
  ) extends Space[PositiveDouble]
  private final case class Product[A, B](
      left: Space[A],
      right: Space[B]
  ) extends Space[(A, B)]
  private final case class Mapped[A, B](
      source: Space[A],
      f: A => B
  ) extends Space[B]

  /** A search space containing exactly one value. */
  def constant[A](value: A): Space[A] = Constant(value)

  /** A non-empty finite set of candidates in declaration order. */
  def choice[A](head: A, tail: A*): Space[A] =
    Choice(head +: tail.toVector)

  /** Inclusive integer range with validated positive bounds. */
  def intRange(
      minimum: PositiveInt,
      maximum: PositiveInt
  ): Either[NumericSpaceError, Space[PositiveInt]] =
    if minimum.toInt <= maximum.toInt then
      Right(IntegerRange(minimum, maximum))
    else
      Left(
        NumericSpaceError.ReversedIntBounds(
          minimum.toInt,
          maximum.toInt
        )
      )

  /** Positive continuous interval sampled uniformly in log space. */
  def logUniform(
      minimum: PositiveDouble,
      maximum: PositiveDouble
  ): Either[NumericSpaceError, Space[PositiveDouble]] =
    if minimum.toDouble <= maximum.toDouble then
      Right(LogUniform(minimum, maximum))
    else
      Left(
        NumericSpaceError.ReversedBounds(
          minimum.toDouble,
          maximum.toDouble
        )
      )

  given Applicative[Space] with
    def pure[A](value: A): Space[A] = constant(value)

    def ap[A, B](ff: Space[A => B])(fa: Space[A]): Space[B] =
      Product(ff, fa).map { (f, value) => f(value) }

  private[tune] def grid[A](
      space: Space[A],
      continuousPoints: PositiveInt
  ): Vector[A] =
    space match
      case Constant(value) => Vector(value)
      case Choice(values)  => values
      case IntegerRange(minimum, maximum) =>
        val size = maximum.toInt - minimum.toInt + 1
        Vector.tabulate(size)(index =>
          PositiveInt.fromValidated(minimum.toInt + index)
        )
      case LogUniform(minimum, maximum) =>
        val count = continuousPoints.toInt
        if count == 1 || minimum == maximum then Vector(minimum)
        else
          val logMinimum = math.log(minimum.toDouble)
          val step =
            (math.log(maximum.toDouble) - logMinimum) /
              (count - 1).toDouble
          Vector.tabulate(count) { index =>
            PositiveDouble.fromValidated(
              math.exp(logMinimum + index.toDouble * step)
            )
          }
      case Product(left, right) =>
        for
          leftValue <- grid(left, continuousPoints)
          rightValue <- grid(right, continuousPoints)
        yield (leftValue, rightValue)
      case Mapped(source, f) =>
        grid(source, continuousPoints).map(f)

  private[tune] def draw[A](
      space: Space[A],
      random: StableRandom
  ): (StableRandom, A) =
    space match
      case Constant(value) => (random, value)
      case Choice(values) =>
        val (next, index) = random.nextInt(values.length)
        (next, values(index))
      case IntegerRange(minimum, maximum) =>
        val width = maximum.toInt - minimum.toInt + 1
        val (next, offset) = random.nextInt(width)
        val value =
          PositiveInt.fromValidated(minimum.toInt + offset)
        (next, value)
      case LogUniform(minimum, maximum) =>
        val (next, unit) = random.nextUnitDouble
        val logMinimum = math.log(minimum.toDouble)
        val sampled =
          math.exp(
            logMinimum +
              unit * (math.log(maximum.toDouble) - logMinimum)
          )
        val value = PositiveDouble.fromValidated(sampled)
        (next, value)
      case Product(left, right) =>
        val (afterLeft, leftValue) = draw(left, random)
        val (afterRight, rightValue) = draw(right, afterLeft)
        (afterRight, (leftValue, rightValue))
      case Mapped(source, f) =>
        val (next, value) = draw(source, random)
        (next, f(value))

/** Grid discretization policy for continuous dimensions. */
final case class GridStrategy(
    continuousPoints: PositiveInt
)

object Grid:
  /** Enumerates the deterministic Cartesian grid for a space. */
  def candidates[A](
      space: Space[A],
      strategy: GridStrategy
  ): Vector[A] =
    Space.grid(space, strategy.continuousPoints)

object RandomSearch:
  /** Draws a reproducible candidate sequence from a space.
    *
    * The generator is platform-independent and seeded explicitly.
    */
  def candidates[A](
      space: Space[A],
      trials: PositiveInt,
      seed: Seed
  ): Vector[A] =
    val output = Vector.newBuilder[A]
    var random = StableRandom(seed.value)
    var index = 0
    while index < trials.toInt do
      val (next, value) = Space.draw(space, random)
      output += value
      random = next
      index += 1
    output.result()

private[tune] final class StableRandom private (private val state: Long):
  def nextLong: (StableRandom, Long) =
    val nextState = state + 0x9e3779b97f4a7c15L
    var mixed = nextState
    mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L
    mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL
    val value = mixed ^ (mixed >>> 31)
    (new StableRandom(nextState), value)

  def nextInt(bound: Int): (StableRandom, Int) =
    val (next, value) = nextLong
    val nonNegative = value >>> 1
    (next, (nonNegative % bound.toLong).toInt)

  def nextUnitDouble: (StableRandom, Double) =
    val (next, value) = nextLong
    val unit = (value >>> 11).toDouble * 1.1102230246251565e-16
    (next, unit)

private[tune] object StableRandom:
  def apply(seed: Long): StableRandom = new StableRandom(seed)

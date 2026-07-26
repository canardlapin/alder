package alder.kernel

/** Stage identity within a normalized logical plan. Allocation is plan-shaped,
  * never runtime-nesting-shaped, so `(a andThen b) andThen c` and
  * `a andThen (b andThen c)` produce the same paths (D2).
  */
final case class StagePath(segments: Vector[Int]) derives CanEqual:
  def child(ordinal: Int): StagePath = StagePath(segments :+ ordinal)

  /** The public way for a leaf pipe to construct a failure at run time, using
    * the stage identity it received at fit time.
    */
  def failure[E](cause: E): Failure[E] = Failure(this, cause)

  def render: String =
    if segments.isEmpty then "/" else segments.mkString("/", "/", "")

object StagePath:
  val root: StagePath = StagePath(Vector.empty)

/** A failed pipe application: the union member that caused it plus the stage
  * that produced it. The union is the cause type; Failure carries provenance —
  * two stages sharing an error type stay distinguishable (D2).
  */
final case class Failure[+E] private[alder] (stage: StagePath, cause: E):
  def map[E2](f: E => E2): Failure[E2] = Failure(stage, f(cause))
  def widen[E2 >: E]: Failure[E2] = this

/** An immutable, pure, already-fitted, locally executable computation with an
  * explicit error channel. Error composition is by union: errors accumulate
  * left-to-right and unions are associative and commutative, so the cause set
  * is parenthesization-invariant.
  */
trait Pipe[-A, +E, +B]:
  self =>

  def run(value: A): Either[Failure[E], B]

  final def andThen[E2, C](next: Pipe[B, E2, C]): Pipe[A, E | E2, C] =
    Pipe.Chain(self, next)

  final def map[C](f: B => C): Pipe[A, E, C] =
    Pipe.Mapped(self, f)

  final def mapError[E2](f: E => E2): Pipe[A, E2, B] =
    Pipe.MapError(self, f)

object Pipe:
  /** A total (never-failing) pipe. */
  def total[A, B](f: A => B): Pipe[A, Nothing, B] = Total(f)

  def identity[A]: Pipe[A, Nothing, A] = total(a => a)

  final case class Total[A, B](f: A => B) extends Pipe[A, Nothing, B]:
    def run(value: A): Either[Failure[Nothing], B] = Right(f(value))

  /** Sequential composition. First failure wins; its stage path and cause pass
    * through unchanged.
    */
  final case class Chain[
      A,
      E1,
      B,
      E2,
      C,
      P1 <: Pipe[A, E1, B],
      P2 <: Pipe[B, E2, C]
  ](
      first: P1,
      second: P2
  ) extends Pipe[A, E1 | E2, C]:
    def run(value: A): Either[Failure[E1 | E2], C] =
      first.run(value) match
        case Left(failure) => Left(failure.widen[E1 | E2])
        case Right(b)      => second.run(b)

  final case class Mapped[A, E, B, C](
      inner: Pipe[A, E, B],
      f: B => C
  ) extends Pipe[A, E, C]:
    def run(value: A): Either[Failure[E], C] = inner.run(value).map(f)

  final case class MapError[A, E, E2, B](
      inner: Pipe[A, E, B],
      f: E => E2
  ) extends Pipe[A, E2, B]:
    def run(value: A): Either[Failure[E2], B] =
      inner.run(value) match
        case Left(failure) => Left(failure.map(f))
        case Right(b)      => Right(b)

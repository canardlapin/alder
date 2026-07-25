package alder.kernel

/** A training observation. `M` is real application metadata, never a string
  * map; requirements on it are typeclasses (WeightOf, GroupOf, TimeOf) visible
  * in signatures.
  */
final case class Example[+X, +Y, +M](input: X, target: Y, meta: M)

/** A scored observation: ground truth paired with a model's prediction. */
final case class Scored[+Y, +P, +M](truth: Y, prediction: P, meta: M)

/** Metadata capability: observation weight. */
trait WeightOf[M]:
  def apply(meta: M): Double

/** Metadata capability: grouping key for grouped resampling. */
trait GroupOf[M]:
  type Key
  def apply(meta: M): Key

/** Metadata capability: observation time for rolling-origin resampling. */
trait TimeOf[M]:
  type Instant
  def apply(meta: M): Instant

package alder.kernel

/** Capabilities are typeclass evidence, never runtime tags: missing evidence
  * means the operation does not compile. There is no runtime "unsupported".
  */

enum CodecError derives CanEqual:
  case Malformed(description: String)
  case UnsupportedVersion(found: String, supported: Vector[String])

/** Round-trip serialization of a trained artifact. Laws: decode(encode(m))
  * predicts identically for all valid inputs; the audit round-trips without
  * semantic loss. Java serialization is not an artifact format.
  */
trait ArtifactCodec[A]:
  def encode(trained: Trained[A]): Either[CodecError, Array[Byte]]
  def decode(bytes: Array[Byte]): Either[CodecError, Trained[A]]

enum ExplainError derives CanEqual:
  case NotExplainable(description: String)

/** Attribution of a prediction to its input. */
trait Explain[A, -X]:
  type Attribution
  def apply(trained: Trained[A], input: X): Either[ExplainError, Attribution]

/** Incremental update capability for learners that support it. */
trait Incremental[L]:
  type Update
  def update(learner: L, update: Update): L

package alder.kernel

/** Capabilities are typeclass evidence, never runtime tags: missing evidence
  * means the operation does not compile. There is no runtime "unsupported".
  */

enum CodecError derives CanEqual:
  case Malformed(description: String)
  case UnsupportedVersion(found: String, supported: Vector[String])
  case InvalidFormat(description: String)
  case FormatMismatch(expected: String, found: String)

final class ArtifactFormat private[alder] (
    val id: String,
    val version: Int
):
  def render: String = s"$id@$version"

object ArtifactFormat:
  def create(
      id: String,
      version: Int
  ): Either[CodecError, ArtifactFormat] =
    val validId =
      id.nonEmpty &&
        id.forall(character =>
          character.isLetterOrDigit ||
            character == '.' ||
            character == '_' ||
            character == '-'
        )
    if !validId then
      Left(
        CodecError.InvalidFormat(
          "format id must contain only letters, digits, '.', '_', or '-'"
        )
      )
    else if version <= 0 then
      Left(
        CodecError.InvalidFormat(
          s"format version must be positive, got $version"
        )
      )
    else Right(new ArtifactFormat(id, version))

  private[alder] def framework(
      id: String,
      version: Int
  ): ArtifactFormat =
    new ArtifactFormat(id, version)

/** Versioned artifact-payload codec. The framework owns the immutable envelope
  * and complete Audit serialization; plugins encode only their artifact value.
  * This split makes mandatory provenance impossible to omit and lets composed
  * codecs derive structurally.
  */
trait ArtifactCodec[A]:
  def format: ArtifactFormat

  def encodeArtifact(value: A): Either[CodecError, IArray[Byte]]

  def decodeArtifact(bytes: IArray[Byte]): Either[CodecError, A]

  final def encode(
      trained: Trained[A]
  ): Either[CodecError, IArray[Byte]] =
    encodeArtifact(trained.artifact).map { artifact =>
      val writer = new BinaryWriter
      writer.string("alder-artifact")
      writer.string(format.id)
      writer.int(format.version)
      writer.payload(AuditBinaryCodec.encode(trained.audit))
      writer.payload(IArray.from(artifact))
      writer.result()
    }

  final def decode(
      bytes: IArray[Byte]
  ): Either[CodecError, Trained[A]] =
    val reader = new BinaryReader(bytes)
    val decoded =
      for
        magic <- reader.string
        _ <-
          if magic == "alder-artifact" then Right(())
          else
            Left(
              CodecError.Malformed(
                s"unexpected artifact magic '$magic'"
              )
            )
        id <- reader.string
        version <- reader.int
        found = s"$id@$version"
        _ <-
          if id != format.id then
            Left(
              CodecError.FormatMismatch(format.render, found)
            )
          else if version != format.version then
            Left(
              CodecError.UnsupportedVersion(
                found,
                Vector(format.render)
              )
            )
          else Right(())
        auditBytes <- reader.payload
        artifactBytes <- reader.payload
        audit <- AuditBinaryCodec.decode(auditBytes)
        artifact <- decodeArtifact(IArray.from(artifactBytes))
      yield new Trained(artifact, audit)
    decoded.flatMap(reader.finish)

object ArtifactCodec:
  def apply[A](using codec: ArtifactCodec[A]): ArtifactCodec[A] = codec

enum ExplainError derives CanEqual:
  case NotExplainable(description: String)

/** Attribution of a prediction to its input. */
trait Explain[A, -X]:
  type Attribution
  def apply(trained: Trained[A], input: X): Either[ExplainError, Attribution]

/** Indexed linear coefficients for a fitted numeric model. */
trait Coefficients[A]:
  def coefficientCount(trained: Trained[A]): Int
  def coefficient(trained: Trained[A], index: Int): Double
  def coefficients(trained: Trained[A]): IArray[Double]
  def intercept(trained: Trained[A]): Double

/** Latent / component scores produced by a fitted decomposition. */
trait LatentScores[A, -X]:
  type Scores
  def scores(trained: Trained[A], input: X): Either[ExplainError, Scores]

/** Loadings for a fitted linear decomposition. */
trait Loadings[A]:
  def loadings(trained: Trained[A]): IArray[IArray[Double]]

/** Class probability vector for a fitted classifier. */
trait ClassProbabilities[A, -X, C]:
  def probabilities(
      trained: Trained[A],
      input: X
  ): Either[ExplainError, Map[C, Double]]

/** Feature importance scores for a fitted model. */
trait FeatureImportance[A]:
  def importances(trained: Trained[A]): IArray[Double]

/** Principal-component projection for a fitted decomposition. */
trait PrincipalComponents[A]:
  def componentCount(trained: Trained[A]): Int
  def components(trained: Trained[A]): IArray[IArray[Double]]

/** Inverse transform of a fitted preprocessing or decomposition artifact. */
trait InverseTransform[A, -Z, X]:
  def inverse(trained: Trained[A], value: Z): Either[ExplainError, X]

/** Incremental update capability for learners that support it. */
trait Incremental[L]:
  type Update
  def update(learner: L, update: Update): L

object Coefficients:
  def apply[A](using evidence: Coefficients[A]): Coefficients[A] = evidence

object Explain:
  def apply[A, X](using evidence: Explain[A, X]): Explain[A, X] = evidence

/** Ordinary prediction and audit accessors for fitted artifacts.
  *
  * Algorithm-specific inspection uses capability traits such as
  * [[Coefficients]] or [[Explain]] against the terminal model.
  */
extension [A](trained: Trained[A])
  /** The exact fitted artifact without navigating composition wrappers. */
  def terminal: A = trained.artifact

extension [X, E, P](trained: Trained[? <: Pipe[X, E, P]])
  /** Predicts one input through the fitted pipe. */
  def predict(input: X): Either[Failure[E], P] =
    trained.artifact.run(input)

  /** Predicts every row, retaining RowIds. */
  def predictAll[U <: Use](
      data: Data[U, X]
  ): Either[Failure[E], Vector[(RowId, P)]] =
    val builder = Vector.newBuilder[(RowId, P)]
    val failed =
      data.foldRows[Option[Failure[E]]](None) {
        case (Some(failure), _, _) => Some(failure)
        case (None, id, value) =>
          trained.artifact.run(value) match
            case Left(failure) => Some(failure)
            case Right(prediction) =>
              builder += ((id, prediction))
              None
      }
    failed match
      case Some(failure) => Left(failure)
      case None          => Right(builder.result())

package alder.codec

import alder.kernel.*

/** Constructors and structural derivations for versioned artifact codecs.
  * Import `ArtifactCodecs.given` to derive a codec for an exact `Pipe.Chain`
  * type from codecs for both concrete stages.
  */
object ArtifactCodecs:
  /** Builds a codec for one exact artifact payload format.
    *
    * Alder defensively copies payload bytes at both boundaries. Envelope
    * validation, including format identity and audit decoding, remains the
    * responsibility of `ArtifactCodec.encode` and `ArtifactCodec.decode`.
    */
  def versioned[A](
      artifactFormat: ArtifactFormat
  )(
      encodePayload: A => Either[CodecError, IArray[Byte]],
      decodePayload: IArray[Byte] => Either[CodecError, A]
  ): ArtifactCodec[A] =
    new ArtifactCodec[A]:
      val format: ArtifactFormat = artifactFormat

      def encodeArtifact(
          value: A
      ): Either[CodecError, IArray[Byte]] =
        encodePayload(value).map(IArray.from)

      def decodeArtifact(
          bytes: IArray[Byte]
      ): Either[CodecError, A] =
        decodePayload(IArray.from(bytes))

  /** Derives a structural codec for two concrete stages in a `Pipe.Chain`.
    *
    * Both stage codecs retain their exact types and formats. Decoding rejects
    * trailing or truncated stage payloads.
    */
  def chain[
      A,
      E1,
      B,
      E2,
      C,
      P1 <: Pipe[A, E1, B],
      P2 <: Pipe[B, E2, C]
  ](
      first: ArtifactCodec[P1],
      second: ArtifactCodec[P2]
  ): ArtifactCodec[Pipe.Chain[A, E1, B, E2, C, P1, P2]] =
    new ArtifactCodec[
      Pipe.Chain[A, E1, B, E2, C, P1, P2]
    ]:
      val format: ArtifactFormat =
        ArtifactFormat.framework(
          s"alder.pipe.chain[${first.format.render},${second.format.render}]",
          1
        )

      def encodeArtifact(
          value: Pipe.Chain[A, E1, B, E2, C, P1, P2]
      ): Either[CodecError, IArray[Byte]] =
        for
          firstBytes <- first.encodeArtifact(value.first)
          secondBytes <- second.encodeArtifact(value.second)
        yield
          val writer = new BinaryWriter
          writer.payload(IArray.from(firstBytes))
          writer.payload(IArray.from(secondBytes))
          writer.result()

      def decodeArtifact(
          bytes: IArray[Byte]
      ): Either[
        CodecError,
        Pipe.Chain[A, E1, B, E2, C, P1, P2]
      ] =
        val reader = new BinaryReader(bytes)
        val decoded =
          for
            firstBytes <- reader.payload
            secondBytes <- reader.payload
            firstPipe <- first.decodeArtifact(firstBytes)
            secondPipe <- second.decodeArtifact(secondBytes)
          yield Pipe.Chain(firstPipe, secondPipe)
        decoded.flatMap(reader.finish)

  given chainCodec[
      A,
      E1,
      B,
      E2,
      C,
      P1 <: Pipe[A, E1, B],
      P2 <: Pipe[B, E2, C]
  ](using
      first: ArtifactCodec[P1],
      second: ArtifactCodec[P2]
  ): ArtifactCodec[
    Pipe.Chain[A, E1, B, E2, C, P1, P2]
  ] =
    chain(first, second)

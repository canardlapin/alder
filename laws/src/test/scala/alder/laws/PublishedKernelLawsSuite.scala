package alder.laws

import alder.kernel.*
import cats.Id
import cats.kernel.Eq
import munit.DisciplineSuite

class PublishedKernelLawsSuite extends DisciplineSuite:
  private def format(id: String, version: Int): ArtifactFormat =
    ArtifactFormat.create(id, version) match
      case Left(error)  => fail(s"invalid test format: $error")
      case Right(value) => value

  private def encodeDouble(value: Double): IArray[Byte] =
    val bits = java.lang.Double.doubleToRawLongBits(value)
    IArray.tabulate(8)(index =>
      (bits >>> (56 - index * 8)).toByte
    )

  private def decodeDouble(
      bytes: IArray[Byte]
  ): Either[CodecError, Double] =
    if bytes.length != 8 then
      Left(CodecError.Malformed("unexpected double payload"))
    else
      var bits = 0L
      var index = 0
      while index < bytes.length do
        bits = (bits << 8) | (bytes(index).toLong & 0xffL)
        index += 1
      Right(java.lang.Double.longBitsToDouble(bits))

  private def rootContext: FitContext =
    FitContext.root(
      seed = Seed(31L),
      plan = PlanFingerprint("published-kernel-laws"),
      schema = SchemaFingerprint("double"),
      numericMode = NumericMode.Deterministic
    )

  private val transformData = TestData.train(1.0, 2.0, 3.0, 6.0)
  private val transform = MeanShift[Id]()

  private given doubleEq: Eq[Double] = Eq.fromUniversalEquals
  private given stringEq: Eq[String] = Eq.fromUniversalEquals
  private given toyRunEq
      : Eq[Either[Failure[ToyRunError], Double]] =
    Eq.fromUniversalEquals
  private given totalRunEq
      : Eq[Either[Failure[Nothing], Double]] =
    Eq.fromUniversalEquals

  private val transformLaws =
    new TransformLaws[
      Use.Train,
      Double,
      Double,
      ToyFitError,
      ToyRunError,
      ShiftPipe
    ]:
      def original: NonEmptyData[Use.Train, Double] = transformData
      def fitOnce
          : Either[
            Failure[ToyFitError],
            Prepared[
              Preparation.Reusable,
              Use.Train,
              ShiftPipe,
              Double
            ]
          ] =
        transform.fit(transformData)(using rootContext).value

  checkAll("MeanShift Transform", new TransformTests(transformLaws).all)

  private val exampleData = TestData.examples(
    (1.0, 10.0, "a"),
    (2.0, 20.0, "b"),
    (6.0, 30.0, "c")
  )
  private val featureMap = FeatureMap.inputOnly[
    Id,
    Double,
    Double,
    String,
    Double,
    MeanShift[Id]
  ](MeanShift[Id]())

  private val featureMapLaws =
    new FeatureMapLaws[
      Preparation.Reusable,
      Use.Train,
      Double,
      Double,
      String,
      Double,
      ToyFitError | PreparationError,
      ToyRunError,
      ShiftPipe
    ]:
      def original
          : NonEmptyData[
            Use.Train,
            Example[Double, Double, String]
          ] = exampleData
      def servingInputs: Vector[Double] = Vector(-1.0, 0.0, 4.0)
      def fitOnce
          : Either[
            Failure[ToyFitError | PreparationError],
            Prepared[
              Preparation.Reusable,
              Use.Train,
              ShiftPipe,
              Example[Double, Double, String]
            ]
          ] =
        featureMap.fit(exampleData)(using rootContext).value

  checkAll(
    "inputOnly FeatureMap",
    new FeatureMapTests(featureMapLaws).all
  )

  private val learner = new SummaryLearner
  private val learnerLaws =
    new LearnerLaws[
      Double,
      Nothing,
      Nothing,
      Double,
      SummaryPipe
    ]:
      def fitOnce: Either[Failure[Nothing], Trained[SummaryPipe]] =
        learner.fit(exampleData)(using rootContext).value
      def inputs: Vector[Double] = Vector(-1.0, 0.0, 4.0)

  checkAll("Summary Learner", new LearnerTests(learnerLaws).all)

  private val trainedShift: Trained[ShiftPipe] =
    transform.fit(transformData)(using rootContext).value match
      case Left(failure) =>
        fail(s"unexpected codec fixture failure: $failure")
      case Right(prepared) => prepared.fitted

  private val codec = new ArtifactCodec[ShiftPipe]:
    val format: ArtifactFormat = PublishedKernelLawsSuite.this
      .format("alder.test.shift", 1)

    def encodeArtifact(
        value: ShiftPipe
    ): Either[CodecError, IArray[Byte]] =
      Right(encodeDouble(value.shift))

    def decodeArtifact(
        bytes: IArray[Byte]
    ): Either[CodecError, ShiftPipe] =
      decodeDouble(bytes).map(shift =>
        new ShiftPipe(
          shift,
          StagePath.root
        )
      )

  checkAll(
    "ShiftPipe ArtifactCodec",
    new ArtifactCodecTests[
      Double,
      ToyRunError,
      Double,
      ShiftPipe
    ](
      codec,
      trainedShift,
      Vector(-1.0, 0.0, 4.0)
    ).all
  )

  test("artifact envelopes reject an unsupported codec version") {
    val versionTwo = new ArtifactCodec[ShiftPipe]:
      val format: ArtifactFormat =
        PublishedKernelLawsSuite.this
          .format("alder.test.shift", 2)
      def encodeArtifact(
          value: ShiftPipe
      ): Either[CodecError, IArray[Byte]] =
        codec.encodeArtifact(value)
      def decodeArtifact(
          bytes: IArray[Byte]
      ): Either[CodecError, ShiftPipe] =
        codec.decodeArtifact(bytes)

    val bytes = codec.encode(trainedShift) match
      case Left(error)  => fail(s"unexpected encode error: $error")
      case Right(value) => value
    versionTwo.decode(bytes) match
      case Left(CodecError.UnsupportedVersion(_, supported)) =>
        assertEquals(supported, Vector("alder.test.shift@2"))
      case other =>
        fail(s"expected unsupported-version failure, got $other")
  }

  test("artifact formats reject blank ids and non-positive versions") {
    assert(
      ArtifactFormat.create("   ", 1).isLeft
    )
    assert(
      ArtifactFormat.create("alder.test,ambiguous", 1).isLeft
    )
    assert(
      ArtifactFormat.create("alder.test", 0).isLeft
    )
  }

  test("artifact envelopes take ownership of plugin payload bytes") {
    var pluginArray = Array.emptyByteArray
    val aliasingCodec = new ArtifactCodec[ShiftPipe]:
      val format: ArtifactFormat =
        PublishedKernelLawsSuite.this
          .format("alder.test.aliasing-shift", 1)
      def encodeArtifact(
          value: ShiftPipe
      ): Either[CodecError, IArray[Byte]] =
        val immutable = encodeDouble(value.shift)
        pluginArray =
          Array.tabulate(immutable.length)(immutable(_))
        Right(IArray.unsafeFromArray(pluginArray))
      def decodeArtifact(
          bytes: IArray[Byte]
      ): Either[CodecError, ShiftPipe] =
        decodeDouble(bytes).map(shift =>
          new ShiftPipe(shift, StagePath.root)
        )

    val encoded = aliasingCodec.encode(trainedShift) match
      case Left(error)  => fail(s"unexpected encode error: $error")
      case Right(value) => value
    var index = 0
    while index < pluginArray.length do
      pluginArray(index) = 0.toByte
      index += 1
    val decoded = aliasingCodec.decode(encoded) match
      case Left(error)  => fail(s"unexpected decode error: $error")
      case Right(value) => value
    assertEquals(
      decoded.artifact.run(4.0),
      trainedShift.artifact.run(4.0)
    )
  }

  private final case class Visibility(
      fittedOn: Set[RowId]
  )

  checkAll(
    "Cross-fit direct exclusion",
    new CrossFitLeakageTests[Nothing, Visibility](
      () =>
        Right[
          Failure[Nothing],
          Vector[(RowId, Visibility)]
        ](
          Vector(
            RowId(0L) -> Visibility(Set(RowId(1L), RowId(2L))),
            RowId(1L) -> Visibility(Set(RowId(0L), RowId(2L))),
            RowId(2L) -> Visibility(Set(RowId(0L), RowId(1L)))
          )
        ),
      _.fittedOn
    ).all
  )

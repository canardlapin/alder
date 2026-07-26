package alder.laws

import alder.kernel.*
import cats.Id
import cats.kernel.Eq
import munit.DisciplineSuite

class PublishedKernelLawsSuite extends DisciplineSuite:
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
    def encode(
        trained: Trained[ShiftPipe]
    ): Either[CodecError, IArray[Byte]] =
      val _ = trained
      Right(IArray(1.toByte))

    def decode(
        bytes: IArray[Byte]
    ): Either[CodecError, Trained[ShiftPipe]] =
      if bytes.length == 1 && bytes(0) == 1.toByte then Right(trainedShift)
      else Left(CodecError.Malformed("unexpected test payload"))

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

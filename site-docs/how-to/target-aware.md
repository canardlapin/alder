# Use target-aware features without leakage

A fitted feature that reads targets cannot prepare a training row from a state
that saw that row's target. In Alder, implement a `FoldEncoder`, provide a
`CompleteResampler`, and let `Blueprint.crossFit` construct the out-of-fold
training values.

## Implement the encoder contract

This deliberately small external-style encoder learns the mean target of its
fitting partition. `FitContext.complete` records the exact data and component
identity used for each fitted state.

```scala mdoc
import alder.application.Blueprint
import alder.data.*
import alder.kernel.*
import alder.preprocess.{StandardScaler, Standardized, ZeroVariance}
import cats.Id
import cats.data.EitherT

final case class EncodedMean(value: Double) derives Coordinates, Schema

final class MeanTargetEncoder
    extends FoldEncoder[Id, String, Double, Unit, EncodedMean]:
  type State = Double
  type FitError = Nothing
  type RunError = Nothing

  private val descriptor = ComponentDescriptor(
    ComponentId("com.example.mean-target"),
    ComponentVersion("1"),
    AuditValue.record(),
    BackendFingerprint("example", "1", AuditValue.record())
  )

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[String, Double, Unit]]
  )(using context: FitContext): FitResult[Id, Nothing, Trained[Double]] =
    val (sum, count) = data.data.foldRows((0.0, 0L)) {
      case ((total, size), _, example) =>
        (total + example.target, size + 1L)
    }
    EitherT.rightT(context.complete(sum / count.toDouble, data, descriptor))

  def encode(
      state: Double,
      input: String
  ): Either[Failure[Nothing], EncodedMean] =
    val _ = input
    Right(EncodedMean(state))
```

`FoldEncoder.andThen` is the lawful place for fitted target-blind
postprocessing. Alder fits both the encoder and the postprocessor inside every
analysis fold; it does not pool out-of-fold values and fit one global stage.

The terminal learner below is intentionally mechanical. It makes the complete
composition executable without introducing a numerical backend.

```scala mdoc
final class FirstCoordinateLearner[S]
    extends Learner[Id, Dense[S], Double, Unit, Double]:
  type FitError = Nothing
  type RunError = Nothing
  type Model = Pipe[Dense[S], Nothing, Double]

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[Dense[S], Double, Unit]]
  )(using context: FitContext): FitResult[Id, Nothing, Trained[Model]] =
    val model: Model = Pipe.total(_.apply(0))
    val descriptor = ComponentDescriptor(
      ComponentId("com.example.first-coordinate"),
      ComponentVersion("1"),
      AuditValue.record(),
      BackendFingerprint("example", "1", AuditValue.record())
    )
    EitherT.rightT(context.complete(model, data, descriptor))

val targetAwareWorkflow =
  for
    scaler <- StandardScaler
      .sync[EncodedMean](ZeroVariance.EmitZero)
      .left.map(_.toString)
    folds <- KFold[Example[String, Double, Unit]](3, shuffle = false)
      .left.map(_.toString)
  yield
    Blueprint
      .supervised[String, Double]
      .crossFit(new MeanTargetEncoder().andThen(scaler), folds)
      .learn(new FirstCoordinateLearner[Standardized[EncodedMean]])

targetAwareWorkflow.isRight
```

## Observe the out-of-fold exclusion

Plugin tests should make the leakage property visible. Alder's testkit encoder
records the row IDs visible to each fitted fold state; the capture learner
retains the values it received. With six rows and three folds, each encoded row
comes from a state fitted on four rows, and its own row ID is absent.

```scala mdoc
import alder.testkit.{TestData, VisibilityEncoder, VisibilityValue}

final class CapturedValues(val seen: Vector[VisibilityValue])
    extends Pipe[VisibilityValue, Nothing, Double]:
  def run(value: VisibilityValue): Either[Failure[Nothing], Double] =
    Right(value.input)

final class CaptureLearner
    extends Learner[Id, VisibilityValue, Double, String, Double]:
  type FitError = Nothing
  type RunError = Nothing
  type Model = CapturedValues

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[VisibilityValue, Double, String]]
  )(using context: FitContext): FitResult[Id, Nothing, Trained[Model]] =
    val values = data.data.foldRows(Vector.empty[VisibilityValue]) {
      case (seen, _, example) => seen :+ example.input
    }
    val model = new CapturedValues(values)
    val descriptor = ComponentDescriptor(
      ComponentId("com.example.capture"),
      ComponentVersion("1"),
      AuditValue.record(),
      BackendFingerprint("example", "1", AuditValue.record())
    )
    EitherT.rightT(context.complete(model, data, descriptor))

val visibilityEvidence =
  for
    data <- TestData
      .indexed[Use.Train, Example[Double, Double, String]](
        Vector.tabulate(6)(i => Example(i.toDouble, i.toDouble, s"m$i")),
        DataFingerprint.external("visibility-guide")
      )
      .toRight("empty data")
    folds <- KFold[Example[Double, Double, String]](3, shuffle = false)
      .left.map(_.toString)
    evidence <-
      val workflow =
        Blueprint
          .apply[Id, Double, Double, String]
          .crossFit(new VisibilityEncoder, folds)
          .learn(new CaptureLearner)
      given FitContext = FitContext.root(
        Seed(7L),
        PlanFingerprint.external("visibility-guide-v1"),
        SchemaFingerprint("double"),
        NumericMode.Deterministic
      )
      workflow.learner.fit(data).toEither
        .left.map(_.toString)
        .flatMap(workflow.learner.terminalModel(_).left.map(_.toString))
        .map { terminal =>
          val rowIds = TestData.rowsOf(data).map(_._1)
          val fittedSizes = terminal.artifact.seen.map(_.fittedOn.size).distinct
          val excludesSelf = rowIds.zip(terminal.artifact.seen).forall {
            case (rowId, value) => !value.fittedOn.contains(rowId)
          }
          (fittedSizes, excludesSelf)
        }
  yield evidence

visibilityEvidence
```

## Keep the learner boundary final

After cross-fitting, `LearnerReady` rows may feed only a terminal learner. This
does not compile:

```scala mdoc:fail
import alder.application.Blueprint
import alder.kernel.*
import cats.Id

def illegal[
    FM <: FeatureMap[Id, Double, Double, Unit, Double],
    T <: Transform[Id, Double, Double]
](ready: Blueprint.LearnerReady[Id, Double, Double, Unit, Double, FM], t: T) =
  ready.via(t)
```

A plain `Resampler` is insufficient as well: cross-fitting requires exact
assessment coverage from `CompleteResampler`. Those restrictions preserve one
out-of-fold value per row and keep the preparation scope visible.

package alder.data

import alder.kernel.*
import cats.Monad
import cats.data.EitherT

/** Serving pipe backed by the encoder state fitted on all available rows. */
final class EncoderServingPipe[X, E, Z] private[data] (
    runEncoder: X => Either[Failure[E], Z]
) extends Pipe[X, E, Z]:
  def run(input: X): Either[Failure[E], Z] = runEncoder(input)

private final class EncodedFold[Z, Y, M](
    val rows: Vector[(RowId, Example[Z, Y, M])],
    val audit: Audit,
    val lineage: CrossFitFoldLineage
)

extension (companion: FeatureMap.type)
  /** Construct leakage-safe OOF preparation from an exactly-once resampler.
    * The extension lives in alder-data so alder-kernel remains independent of
    * the resampling implementation (D19).
    */
  def crossFitted[
      F[_],
      X,
      Y,
      M,
      Z,
      E <: FoldEncoder[F, X, Y, M, Z]
  ](
      encoder: E,
      resampler: CompleteResampler[Example[X, Y, M]]
  )(using Monad[F]): CrossFittedFeatureMap[F, X, Y, M, Z, E] =
    val _ = companion
    new CrossFittedFeatureMap(encoder, resampler)

final class CrossFittedFeatureMap[
    F[_],
    X,
    Y,
    M,
    Z,
    E <: FoldEncoder[F, X, Y, M, Z]
](
    val encoder: E,
    val resampler: CompleteResampler[Example[X, Y, M]]
)(using Monad[F])
    extends FeatureMap[F, X, Y, M, Z]:
  type Scope = Preparation.LearnerReady
  type FitError = DataError | encoder.FitError | encoder.RunError
  type RunError = encoder.RunError
  type Fitted = EncoderServingPipe[X, encoder.RunError, Z]

  def fit[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]]
  )(using context: FitContext): FitResult[
    F,
    FitError,
    Prepared[Scope, U, Fitted, Example[Z, Y, M]]
  ] =
    for
      plan <- EitherT.fromEither[F](
        resampler
          .split(data, context.seed)
          .left
          .map(error => context.stagePath.failure[FitError](error))
      )
      encoded <- encodeFolds(plan)
      prepared <- EitherT.fromEither[F](
        assemble(data, plan, encoded.flatMap(_.rows))
      )
      serving <- encoder
        .fit(data)(using context.forChild(plan.foldCount))
        .widenFailure[FitError]
    yield
      val receipt = new CrossFitLineage(
        resampler = plan.resampler,
        seed = context.seed,
        assignment = plan.assignment,
        folds = encoded.map(_.lineage),
        serving = serving.audit.preparation,
        tessera = plan.tessera
      )
      val lineage =
        PreparationLineage.crossFitted(context.stagePath, receipt)
      val pipe: Fitted =
        new EncoderServingPipe(input =>
          encoder
            .encode(serving.artifact, input)
            .left
            .map(_.widen[encoder.RunError])
        )
      val trained = context.composite(
        artifact = pipe,
        trainedOn = data,
        component =
          AlderComponents.crossFitted(plan.resampler, plan.foldCount),
        preparation = lineage,
        children = encoded.map(_.audit) :+ serving.audit
      )
      new Prepared(trained, prepared, lineage)

  private def encodeFolds[U <: Use.Fit](
      plan: ResamplingPlan[U, Example[X, Y, M]]
  )(using context: FitContext): FitResult[
    F,
    FitError,
    Vector[EncodedFold[Z, Y, M]]
  ] =
    val initial: FitResult[F, FitError, Vector[EncodedFold[Z, Y, M]]] =
      EitherT.rightT(Vector.empty)
    plan.folds.foldLeft(initial) { (result, fold) =>
      result.flatMap { accepted =>
        encoder
          .fit(fold.analysis)(using context.forChild(fold.index))
          .widenFailure[FitError]
          .flatMap { state =>
            EitherT.fromEither[F](
              encodeAssessment(fold.assessment, state.artifact).map { rows =>
                val lineage = new CrossFitFoldLineage(
                  index = fold.index,
                  analysis = fold.analysis.fingerprint,
                  assessment = fold.assessment.fingerprint,
                  fittedState = state.audit.preparation
                )
                accepted :+ new EncodedFold(rows, state.audit, lineage)
              }
            )
          }
      }
    }

  private def encodeAssessment[U <: Use.Fit](
      assessment: NonEmptyData[U, Example[X, Y, M]],
      state: encoder.State
  ): Either[Failure[FitError], Vector[(RowId, Example[Z, Y, M])]] =
    assessment.data.foldRows[
      Either[Failure[FitError], Vector[(RowId, Example[Z, Y, M])]]
    ](Right(Vector.empty)) {
      case (Left(failure), _, _) => Left(failure)
      case (Right(rows), id, example) =>
        encoder
          .encode(state, example.input)
          .left
          .map(_.widen[FitError])
          .map(value =>
            rows :+ (id, Example(value, example.target, example.meta))
          )
    }

  private def assemble[U <: Use.Fit](
      data: NonEmptyData[U, Example[X, Y, M]],
      plan: ResamplingPlan[U, Example[X, Y, M]],
      encoded: Vector[(RowId, Example[Z, Y, M])]
  )(using context: FitContext): Either[
    Failure[FitError],
    NonEmptyData[U, Example[Z, Y, M]]
  ] =
    val byId = encoded.foldLeft(
      Option(Map.empty[RowId, Example[Z, Y, M]])
    ) {
      case (None, _) => None
      case (Some(rows), (id, example)) =>
        if rows.contains(id) then None
        else Some(rows.updated(id, example))
    }
    val ordered = byId.flatMap { available =>
      val originals = DataRows.collect(data.data)
      if available.size != originals.length then None
      else
        originals.foldLeft(
          Option(Vector.empty[(RowId, Example[Z, Y, M])])
        ) {
          case (None, _) => None
          case (Some(rows), (id, _)) =>
            available.get(id).map(example => rows :+ (id, example))
        }
    }
    ordered match
      case None =>
        Left(
          context.stagePath.failure[FitError](
            DataError.InvalidResamplingAssignment
          )
        )
      case Some(rows) =>
        val fingerprint = Fingerprints.derived(
          data.fingerprint,
          "cross-fitted",
          plan.resampler.digest,
          plan.assignment.digest
        )
        Right(
          new NonEmptyData(
            new InMemoryData[U, Example[Z, Y, M]](rows, fingerprint),
            data.refit
          )
        )

package alder.application

import alder.data.*
import alder.kernel.*
import alder.metrics.*

class ExperimentFailureRenderingSuite extends munit.FunSuite:
  private def fraction(numerator: Long, denominator: Long): Fraction =
    Fraction(numerator, denominator) match
      case Left(error)  => fail(s"unexpected Fraction error: $error")
      case Right(valid) => valid

  private val splitPolicy =
    SplitPolicy.Validation(
      SplitAmount.Proportion(fraction(1L, 4L))
    )

  private def split(error: DataError): ExperimentFailure[Nothing, Nothing] =
    ExperimentFailure.Split(SplitPhase.Partition, error)

  private def data(error: RefitError): ExperimentFailure[Nothing, Nothing] =
    ExperimentFailure.Data(DataPhase.Source, error)

  private def metric(
      error: MetricError
  ): ExperimentFailure[Nothing, Nothing] =
    ExperimentFailure.Metric(EvaluationPhase.Validation, error)

  test("built-in experiment failures have stable human rendering") {
    val dataErrors = Vector(
      DataError.EmptyData,
      DataError.InvalidRows(0L),
      DataError.InvalidFraction(0L, 4L),
      DataError.InvalidThreeWayFractionSum(
        fraction(1L, 2L),
        fraction(3L, 4L)
      ),
      DataError.InvalidRankText(
        RankTextField.FingerprintDigest,
        RankTextError.UnpairedSurrogate(3)
      ),
      DataError.InvalidRankText(
        RankTextField.SourceIdentityUri,
        RankTextError.Utf8LengthExceedsU32(4294967296L)
      ),
      DataError.DuplicateSourceRow(RowId(7L)),
      DataError.EmptySplitRole(SplitRole.Validation, 3L, splitPolicy),
      DataError.ExhaustiveSplit(3L, splitPolicy),
      DataError.InvalidHoldoutSize(4, 3L),
      DataError.InvalidFoldCount(1),
      DataError.TooManyFolds(5, 4L),
      DataError.TooFewGroups(4, 3),
      DataError.InvalidResamplingAssignment,
      DataError.Resample4sPopulationTooLarge(Int.MaxValue.toLong + 1L),
      DataError.Resample4sPopulationSizeMismatch(3, 4L),
      DataError.Resample4sSeedMismatch(1L, 2L),
      DataError.Resample4sPopulationFingerprintMismatch,
      DataError.InvalidResample4sPopulationFingerprint(
        FingerprintPolicy.Summary("invalid"),
        "population"
      ),
      DataError.InvalidRollingWindow(0, 1, 1),
      DataError.NoRollingFolds(3L, 4)
    ).map(error => split(error).render)

    val content = new DataFingerprint(
      FingerprintPolicy.ContentDigest("sha256"),
      "content"
    )
    val source = new DataFingerprint(
      FingerprintPolicy.SourceIdentity("dataset", "v1"),
      "source"
    )
    val summary = DataFingerprint.external("summary")
    val refitErrors = Vector(
      RefitError.EmptyEvaluationSource(EvaluationRole.Validation),
      RefitError.DuplicateObservedRow(RowId(8L)),
      RefitError.MissingPriorRefitAudit,
      RefitError.PriorRefitWasNotSelected,
      RefitError.PriorSourcesMustEndInValidation(
        Vector(ObservedSourceRole.Train, ObservedSourceRole.Test)
      ),
      RefitError.PriorFingerprintMismatch(content, source)
    ).map(error => data(error).render)

    val metricErrors = Vector(
      MetricError.Empty,
      MetricError.NonFiniteTruth(Double.NaN),
      MetricError.NonFinitePrediction(Double.PositiveInfinity),
      MetricError.NonFiniteResidual(1.0, Double.NaN),
      MetricError.NonFiniteSquaredError(Double.PositiveInfinity),
      MetricError.NonFiniteWeight(Double.NaN),
      MetricError.NegativeWeight(-1.0),
      MetricError.NonFiniteWeightedValue(Double.NaN, 2.0),
      MetricError.ZeroTotalWeight,
      MetricError.NonFiniteResult
    ).map(error => metric(error).render)

    val selection  = SelectionReceiptId("selection")
    val evaluation = EvaluationReceiptId("evaluation")
    val applicationRefitErrors = Vector(
      ApplicationRefitError.SelectionReceiptMismatch(selection),
      ApplicationRefitError.EvaluationReceiptMismatch(evaluation),
      ApplicationRefitError.SelectionReceiptAlreadyUsed(selection),
      ApplicationRefitError.EvaluationReceiptAlreadyUsed(evaluation)
    ).map(error =>
      ExperimentFailure
        .Refit(RefitPhase.SelectedPromotion, error)
        .render
    )

    val predictionErrors = Vector(
      ExperimentFailure
        .Predict(
          EvaluationPhase.Validation,
          EvaluationError.FitSourceMismatch(content, source)
        )
        .render,
      ExperimentFailure
        .Predict(
          EvaluationPhase.Test,
          EvaluationError.FitAuditMismatch(
            Some(RefitEvidence(evaluation, Some(selection))),
            Some(RefitEvidence(evaluation, None))
          )
        )
        .render,
      ExperimentFailure
        .Predict(
          EvaluationPhase.PrecommittedTest,
          EvaluationError.FitSourceMismatch(summary, content)
        )
        .render
    )

    val remaining = Vector(
      ExperimentFailure
        .Definition(ExperimentDefinitionError.EmptySource)
        .render,
      ExperimentFailure
        .Select(
          SelectionPhase.Select,
          SelectionError.ReportingMetricCannotSelect
        )
        .render
    )

    val rendered =
      dataErrors ++ refitErrors ++ metricErrors ++ applicationRefitErrors ++
        predictionErrors ++ remaining
    assert(rendered.forall(_.nonEmpty))
    assert(rendered.forall(!_.contains("DataFingerprint@")), clues(rendered))
    assert(rendered.forall(!_.contains("RefitEvidence@")), clues(rendered))
    assert(rendered.exists(_.contains("source-identity:dataset@v1:source")))
    assert(
      rendered.exists(
        _.contains("selection receipt selection was already used")
      )
    )
    assert(rendered.exists(_.contains("reporting-only metric")))
  }

  test("prediction rendering retains a custom leaf cause and stage") {
    val failure: ExperimentFailure[Nothing, String] =
      ExperimentFailure.Predict(
        EvaluationPhase.Validation,
        EvaluationError.PredictionFailed(
          StagePath(Vector(3)).failure("unseen-level")
        )
      )

    assertEquals(
      failure.render,
      "experiment prediction phase Validation failed at /3: unseen-level"
    )
  }

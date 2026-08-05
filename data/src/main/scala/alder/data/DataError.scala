package alder.data

import alder.kernel.FingerprintPolicy

/** Checked construction, splitting, and resampling failures. */
enum DataError derives CanEqual:
  case EmptyData
  case InvalidRows(value: Long)
  case InvalidFraction(numerator: Long, denominator: Long)
  case InvalidThreeWayFractionSum(
      validation: Fraction,
      test: Fraction
  )
  case InvalidRankText(field: RankTextField, reason: RankTextError)
  case DuplicateSourceRow(id: alder.kernel.RowId)
  case EmptySplitRole(
      role: SplitRole,
      availableRows: Long,
      policy: SplitPolicy
  )
  case ExhaustiveSplit(
      availableRows: Long,
      policy: SplitPolicy
  )
  case InvalidHoldoutSize(requested: Int, available: Long)
  case InvalidFoldCount(requested: Int)
  case TooManyFolds(requested: Int, availableRows: Long)
  case TooFewGroups(requestedFolds: Int, availableGroups: Int)
  case InvalidResamplingAssignment
  case Resample4sPopulationTooLarge(availableRows: Long)
  case Resample4sPopulationSizeMismatch(expected: Int, actual: Long)
  case Resample4sSeedMismatch(expected: Long, actual: Long)
  case Resample4sPopulationFingerprintMismatch
  case InvalidResample4sPopulationFingerprint(
      policy: FingerprintPolicy,
      digest: String
  )
  case InvalidRollingWindow(
      initialSize: Int,
      assessmentSize: Int,
      stepSize: Int
  )
  case NoRollingFolds(availableRows: Long, initialSize: Int)

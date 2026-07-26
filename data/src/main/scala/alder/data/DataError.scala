package alder.data

import alder.kernel.FingerprintPolicy

enum DataError derives CanEqual:
  case EmptyData
  case InvalidHoldoutSize(requested: Int, available: Long)
  case InvalidFoldCount(requested: Int)
  case TooManyFolds(requested: Int, availableRows: Long)
  case TooFewGroups(requestedFolds: Int, availableGroups: Int)
  case InvalidResamplingAssignment
  case TesseraPopulationTooLarge(availableRows: Long)
  case TesseraPopulationSizeMismatch(expected: Int, actual: Long)
  case TesseraSeedMismatch(expected: Long, actual: Long)
  case TesseraPopulationFingerprintMismatch
  case InvalidTesseraPopulationFingerprint(
      policy: FingerprintPolicy,
      digest: String
  )
  case InvalidRollingWindow(
      initialSize: Int,
      assessmentSize: Int,
      stepSize: Int
  )
  case NoRollingFolds(availableRows: Long, initialSize: Int)

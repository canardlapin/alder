package alder.data

enum DataError derives CanEqual:
  case EmptyData
  case InvalidHoldoutSize(requested: Int, available: Long)
  case InvalidFoldCount(requested: Int)
  case TooManyFolds(requested: Int, availableRows: Long)
  case TooFewGroups(requestedFolds: Int, availableGroups: Int)
  case InvalidResamplingAssignment
  case InvalidRollingWindow(
      initialSize: Int,
      assessmentSize: Int,
      stepSize: Int
  )
  case NoRollingFolds(availableRows: Long, initialSize: Int)

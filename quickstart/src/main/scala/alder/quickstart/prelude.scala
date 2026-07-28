package alder.quickstart

/** Curated prelude for the ordinary supervised workflow.
  *
  * This is not a re-export of every Alder type. Escape to
  * `alder.application`, `alder.data`, `alder.preprocess`, or
  * `alder.models.linear` when you need the full surface.
  */
export alder.application.{
  Blueprint,
  Experiment,
  ExperimentFailure,
  SingleCandidate
}
export alder.data.{
  Coordinates,
  Dense,
  FeatureView,
  Schema,
  ValidationSpec
}
export alder.kernel.{Example, Seed, Trained}
export alder.preprocess.{Standardized, ZeroVariance}
export alder.tune.Search

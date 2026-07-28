package alder.quickstart

import alder.data.*
import alder.kernel.*

/** Identified supervised datasets with `Unit` metadata for the ordinary path. */
object Supervised:

  /** Builds unsplit examples from `(input, target)` pairs.
    *
    * Callers never write `Unit`. The identity is application-managed, not a
    * content digest.
    */
  def fromPairs[X, Y](
      pairs: Vector[(X, Y)],
      identity: String
  ): Data[Use.Unsplit, Example[X, Y, Unit]] =
    InMemoryData.unsplit(
      pairs.map { case (input, target) => Example(input, target, ()) },
      identity
    )

  /** Builds unsplit examples from `(input, target)` pairs with an explicit
    * fingerprint.
    */
  def fromPairs[X, Y](
      pairs: Vector[(X, Y)],
      fingerprint: DataFingerprint
  ): Data[Use.Unsplit, Example[X, Y, Unit]] =
    InMemoryData.unsplit(
      pairs.map { case (input, target) => Example(input, target, ()) },
      fingerprint
    )

  /** Builds unsplit examples while preserving caller-supplied metadata. */
  def fromExamples[X, Y, M](
      examples: Vector[Example[X, Y, M]],
      identity: String
  ): Data[Use.Unsplit, Example[X, Y, M]] =
    InMemoryData.unsplit(examples, identity)

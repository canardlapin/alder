package alder.quickstart

import alder.data.{DataError, Fraction, Rows, ValidationSpec}

/** Checked validation split sizes without a seed. */
object Validation:

  /** Hold out an exact row count for validation. */
  def rows(value: Long): Either[DataError, ValidationSpec] =
    ValidationSpec.rows(value)

  /** Hold out an exact reduced fraction for validation. */
  def fraction(
      numerator: Long,
      denominator: Long
  ): Either[DataError, ValidationSpec] =
    ValidationSpec.fraction(numerator, denominator)

  /** Hold out a previously validated row count. */
  def rows(value: Rows): ValidationSpec =
    ValidationSpec(value)

  /** Hold out a previously validated fraction. */
  def fraction(value: Fraction): ValidationSpec =
    ValidationSpec(value)

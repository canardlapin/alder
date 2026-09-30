package alder.data

import alder.kernel.*

enum FixedSelectionError derives CanEqual:
  case EmptyRoot
  case DuplicateRootId(id: Long)
  case EmptySelection(role: SplitRole)
  case DuplicateSelectionId(role: SplitRole, id: Long)
  case UnknownId(role: SplitRole, id: Long)
  case RoleOverlap(id: Long)
  case MissingCoverage(ids: Vector[Long])

enum FixedCoverage derives CanEqual:
  case Exhaustive, DeclaredSubset

final case class FixedRoleReceipt(role: SplitRole, ids: Vector[Long], fingerprint: DataFingerprint)

/** Metadata derivation from declared source identity, never payload verification. */
final case class FixedSelectionReceipt(
    declaredSource: DataFingerprint,
    rootFingerprint: DataFingerprint,
    rootIds: Vector[Long],
    coverage: FixedCoverage,
    roles: Vector[FixedRoleReceipt],
    excludedIds: Vector[Long]
)

final class FixedValidation[A] private[data] (
    val train: NonEmptyData[Use.Train, A],
    val validation: NonEmptyData[Use.Validation, A],
    val receipt: FixedSelectionReceipt
)

final class FixedHoldout[A] private[data] (
    val train: NonEmptyData[Use.Train, A],
    val test: NonEmptyData[Use.Test, A],
    val receipt: FixedSelectionReceipt
)

final class FixedTrainValidationTest[A] private[data] (
    val train: NonEmptyData[Use.Train, A],
    val validation: NonEmptyData[Use.Validation, A],
    val test: NonEmptyData[Use.Test, A],
    val receipt: FixedSelectionReceipt
)

/** Owns immutable row membership and order. Generic A is not deeply frozen.
  * Fitting role assignment is explicit and never chosen through a generic U.
  */
final class IdentifiedRows[A] private (
    private val rows: Vector[(RowId, A)],
    val declaredSource: DataFingerprint,
    val fingerprint: DataFingerprint
):
  val ids: Vector[Long] = rows.map(_._1.value)
  private val byId = rows.map(row => row._1.value -> row).toMap

  def training(ids: Vector[Long]): Either[FixedSelectionError, NonEmptyData[Use.Train, A]] =
    select(SplitRole.Train, ids).map(selected => nonEmpty[Use.Train](SplitRole.Train, selected))

  def fixedValidation(train: Vector[Long], validation: Vector[Long], coverage: FixedCoverage): Either[FixedSelectionError, FixedValidation[A]] =
    for
      tr <- select(SplitRole.Train, train)
      va <- select(SplitRole.Validation, validation)
      receipt <- joint(Vector(SplitRole.Train -> tr, SplitRole.Validation -> va), coverage)
    yield new FixedValidation(nonEmpty[Use.Train](SplitRole.Train, tr), nonEmpty[Use.Validation](SplitRole.Validation, va), receipt)

  def fixedHoldout(train: Vector[Long], test: Vector[Long], coverage: FixedCoverage): Either[FixedSelectionError, FixedHoldout[A]] =
    for
      tr <- select(SplitRole.Train, train)
      te <- select(SplitRole.Test, test)
      receipt <- joint(Vector(SplitRole.Train -> tr, SplitRole.Test -> te), coverage)
    yield new FixedHoldout(nonEmpty[Use.Train](SplitRole.Train, tr), nonEmpty[Use.Test](SplitRole.Test, te), receipt)

  def fixedTrainValidationTest(train: Vector[Long], validation: Vector[Long], test: Vector[Long], coverage: FixedCoverage): Either[FixedSelectionError, FixedTrainValidationTest[A]] =
    for
      tr <- select(SplitRole.Train, train)
      va <- select(SplitRole.Validation, validation)
      te <- select(SplitRole.Test, test)
      receipt <- joint(Vector(SplitRole.Train -> tr, SplitRole.Validation -> va, SplitRole.Test -> te), coverage)
    yield new FixedTrainValidationTest(nonEmpty[Use.Train](SplitRole.Train, tr), nonEmpty[Use.Validation](SplitRole.Validation, va), nonEmpty[Use.Test](SplitRole.Test, te), receipt)

  private def select(role: SplitRole, requested: Vector[Long]): Either[FixedSelectionError, Vector[(RowId, A)]] =
    if requested.isEmpty then Left(FixedSelectionError.EmptySelection(role))
    else
      val seen = scala.collection.mutable.HashSet.empty[Long]
      val out = Vector.newBuilder[(RowId, A)]
      var i = 0
      while i < requested.size do
        val id = requested(i)
        if !seen.add(id) then return Left(FixedSelectionError.DuplicateSelectionId(role, id))
        byId.get(id) match
          case None => return Left(FixedSelectionError.UnknownId(role, id))
          case Some(row) => out += row
        i += 1
      Right(out.result())

  private def selectedFingerprint(role: SplitRole, selected: Vector[(RowId, A)]): DataFingerprint =
    val digest = Fingerprints.partition(fingerprint, s"alder.fixed-selection-v1:$role", selected).digest
    new DataFingerprint(FingerprintPolicy.Summary("alder.fixed-selection-declared-v1"), digest)

  private def nonEmpty[U <: Use](role: SplitRole, selected: Vector[(RowId, A)]): NonEmptyData[U, A] =
    new NonEmptyData(new InMemoryData[U, A](selected, selectedFingerprint(role, selected)))

  private def joint(parts: Vector[(SplitRole, Vector[(RowId, A)])], coverage: FixedCoverage): Either[FixedSelectionError, FixedSelectionReceipt] =
    val seen = scala.collection.mutable.HashSet.empty[Long]
    var p = 0
    while p < parts.size do
      var r = 0
      while r < parts(p)._2.size do
        val id = parts(p)._2(r)._1.value
        if !seen.add(id) then return Left(FixedSelectionError.RoleOverlap(id))
        r += 1
      p += 1
    val excluded = ids.filterNot(seen.contains)
    if coverage == FixedCoverage.Exhaustive && excluded.nonEmpty then Left(FixedSelectionError.MissingCoverage(excluded))
    else Right(FixedSelectionReceipt(declaredSource, fingerprint, ids, coverage,
      parts.map((role, selected) => FixedRoleReceipt(role, selected.map(_._1.value), selectedFingerprint(role, selected))), excluded))

object IdentifiedRows:
  def fromRows[A](rows: Vector[(Long, A)], declaredSource: DataFingerprint): Either[FixedSelectionError, IdentifiedRows[A]] =
    if rows.isEmpty then Left(FixedSelectionError.EmptyRoot)
    else
      val seen = scala.collection.mutable.HashSet.empty[Long]
      val out = Vector.newBuilder[(RowId, A)]
      var i = 0
      while i < rows.size do
        val (id, value) = rows(i)
        if !seen.add(id) then return Left(FixedSelectionError.DuplicateRootId(id))
        out += RowId(id) -> value
        i += 1
      val typed = out.result()
      val digest = Fingerprints.partition(declaredSource, "alder.identified-root-v1", typed).digest
      val fp = new DataFingerprint(FingerprintPolicy.Summary("alder.identified-root-declared-v1"), digest)
      Right(new IdentifiedRows(typed, declaredSource, fp))

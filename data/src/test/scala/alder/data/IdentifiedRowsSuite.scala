package alder.data

import alder.kernel.*
import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

class IdentifiedRowsSuite extends FunSuite:
  private def right[E,A](value: Either[E,A]): A = value.fold(e => fail(e.toString), identity)
  private val source = DataFingerprint.external("native-v1")
  private def fingerprintKey(value: DataFingerprint) = (value.policy, value.digest)
  private def root = right(IdentifiedRows.fromRows(Vector(10L -> "a", 30L -> "b", 20L -> "c", 40L -> "d"), source))
  private def rows[U <: Use,A](data: NonEmptyData[U,A]) = data.data.foldRows(Vector.empty[(Long,A)])((acc,id,value) => acc :+ (id.value -> value))

  test("training preserves exact supplied IDs and requested order"):
    val data = right(root.training(Vector(20L, 10L)))
    assertEquals(rows(data), Vector(20L -> "c", 10L -> "a"))
    assertNotEquals(fingerprintKey(data.fingerprint), fingerprintKey(right(root.training(Vector(10L, 20L))).fingerprint))
    assertEquals(fingerprintKey(data.fingerprint), fingerprintKey(right(root.training(Vector(20L, 10L))).fingerprint))

  test("source policy revision root membership and order affect selected identity"):
    val data = right(root.training(Vector(20L)))
    val revised = right(IdentifiedRows.fromRows(Vector(10L -> "a", 30L -> "b", 20L -> "c", 40L -> "d"), DataFingerprint.external("native-v2")))
    val reordered = right(IdentifiedRows.fromRows(Vector(20L -> "c", 10L -> "a", 30L -> "b", 40L -> "d"), source))
    val subset = right(IdentifiedRows.fromRows(Vector(20L -> "c", 10L -> "a"), source))
    assertNotEquals(fingerprintKey(data.fingerprint), fingerprintKey(right(revised.training(Vector(20L))).fingerprint))
    assertNotEquals(fingerprintKey(data.fingerprint), fingerprintKey(right(reordered.training(Vector(20L))).fingerprint))
    assertNotEquals(fingerprintKey(data.fingerprint), fingerprintKey(right(subset.training(Vector(20L))).fingerprint))
    assert(data.fingerprint.policy.isInstanceOf[FingerprintPolicy.Summary])

  test("empty duplicate and unknown membership are refused"):
    assertEquals(IdentifiedRows.fromRows(Vector.empty[(Long,String)], source), Left(FixedSelectionError.EmptyRoot))
    assertEquals(IdentifiedRows.fromRows(Vector(1L -> "a", 1L -> "b"), source), Left(FixedSelectionError.DuplicateRootId(1L)))
    assertEquals(root.training(Vector.empty), Left(FixedSelectionError.EmptySelection(SplitRole.Train)))
    assertEquals(root.training(Vector(10L,10L)), Left(FixedSelectionError.DuplicateSelectionId(SplitRole.Train,10L)))
    assertEquals(root.training(Vector(99L)), Left(FixedSelectionError.UnknownId(SplitRole.Train,99L)))

  test("joint roles preserve order and make overlap and coverage explicit"):
    val split = right(root.fixedTrainValidationTest(Vector(30L,10L),Vector(20L),Vector(40L),FixedCoverage.Exhaustive))
    assertEquals(rows(split.train), Vector(30L -> "b",10L -> "a"))
    assertEquals(rows(split.validation),Vector(20L -> "c"))
    assertEquals(rows(split.test),Vector(40L -> "d"))
    assertEquals(split.receipt.excludedIds,Vector.empty[Long])
    assertEquals(root.fixedHoldout(Vector(10L),Vector(10L),FixedCoverage.DeclaredSubset),Left(FixedSelectionError.RoleOverlap(10L)))
    assertEquals(root.fixedValidation(Vector(10L),Vector(20L),FixedCoverage.Exhaustive),Left(FixedSelectionError.MissingCoverage(Vector(30L,40L))))
    val partial = right(root.fixedHoldout(Vector(10L),Vector(20L),FixedCoverage.DeclaredSubset))
    assertEquals(partial.receipt.excludedIds,Vector(30L,40L))
    assertNotEquals(fingerprintKey(partial.train.fingerprint), fingerprintKey(partial.test.fingerprint))

  test("training cannot be promoted directly to refit or test"):
    val errors = typeCheckErrors("""
import alder.data.*
import alder.kernel.*
def invalid[A](root: IdentifiedRows[A]): NonEmptyData[Use.Test,A] = root.training(Vector(1L)).toOption.get
""")
    assert(errors.nonEmpty)

  test("deferred fixed resampler binds both declared population and ordered IDs"):
    given resample4s.core.DigestAlgorithm = resample4s.core.DigestAlgorithm.fnv1a64
    val selected = right(root.training(Vector(10L,30L,20L,40L)))
    val labels = right(resample4s.core.Labels.retained(IArray(0,1,0,1)))
    val design = right(resample4s.designs.FixedPartitions.once(labels))
    val bound = right(Resample4sResampler.fromDesignForPopulation[String](design,selected.fingerprint,Vector(10L,30L,20L,40L)))
    assert(bound.split(selected,Seed(99L)).isRight)
    assert(bound.split(selected,Seed(100L)).isRight)
    val reordered = right(root.training(Vector(30L,10L,20L,40L)))
    assertEquals(bound.split(reordered,Seed(99L)),Left(DataError.Resample4sPopulationFingerprintMismatch))
    val forgedOrder = right(DataRows.nonEmpty[Use.Train,String](Vector(RowId(30L) -> "b",RowId(10L) -> "a",RowId(20L) -> "c",RowId(40L) -> "d"),selected.fingerprint))
    assertEquals(bound.split(forgedOrder,Seed(99L)),Left(DataError.Resample4sPopulationFingerprintMismatch))
    val foreignIds = right(DataRows.nonEmpty[Use.Train,String](Vector(RowId(10L) -> "a",RowId(30L) -> "b",RowId(20L) -> "c",RowId(99L) -> "d"),selected.fingerprint))
    assertEquals(bound.split(foreignIds,Seed(99L)),Left(DataError.Resample4sPopulationFingerprintMismatch))
    val revised = right(IdentifiedRows.fromRows(Vector(10L -> "a",30L -> "b",20L -> "c",40L -> "d"),DataFingerprint.external("native-v2")))
    assertEquals(bound.split(right(revised.training(Vector(10L,30L,20L,40L))),Seed(99L)),Left(DataError.Resample4sPopulationFingerprintMismatch))

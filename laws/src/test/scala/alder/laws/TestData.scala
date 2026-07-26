package alder.laws

import alder.kernel.*

/** In-memory training data for law and protocol tests. */
object TestData:
  def train(values: Double*): NonEmptyData[Use.Train, Double] =
    val rows = values.toVector.zipWithIndex.map { (value, index) =>
      (RowId(index.toLong), value)
    }
    new NonEmptyData(
      RowVectorData(
        rows,
        new DataFingerprint(
          FingerprintPolicy.Summary("test"),
          values.mkString(",")
        )
      )
    )

  def examples(
      values: (Double, Double, String)*
  ): NonEmptyData[Use.Train, Example[Double, Double, String]] =
    val rows = values.toVector.zipWithIndex.map { (value, index) =>
      (
        RowId(index.toLong),
        Example(value._1, value._2, value._3)
      )
    }
    new NonEmptyData(
      RowVectorData(
        rows,
        new DataFingerprint(
          FingerprintPolicy.Summary("test-examples"),
          values.mkString(",")
        )
      )
    )

  def rowsOf[U <: Use, A](data: NonEmptyData[U, A]): Vector[(Long, A)] =
    data.data.foldRows(Vector.empty[(Long, A)]) { (acc, id, value) =>
      acc :+ (id.value, value)
    }

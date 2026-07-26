package alder.kernel

import scala.collection.mutable.ArrayBuffer

private[alder] final class BinaryWriter:
  private val bytes = ArrayBuffer.empty[Byte]

  def byte(value: Int): Unit =
    val _ = bytes += value.toByte

  def bool(value: Boolean): Unit =
    byte(if value then 1 else 0)

  def int(value: Int): Unit =
    byte(value >>> 24)
    byte(value >>> 16)
    byte(value >>> 8)
    byte(value)

  def long(value: Long): Unit =
    int((value >>> 32).toInt)
    int(value.toInt)

  def double(value: Double): Unit =
    long(java.lang.Double.doubleToRawLongBits(value))

  def string(value: String): Unit =
    int(value.length)
    var index = 0
    while index < value.length do
      int(value.charAt(index).toInt)
      index += 1

  def payload(value: IArray[Byte]): Unit =
    int(value.length)
    var index = 0
    while index < value.length do
      byte(value(index).toInt)
      index += 1

  def option[A](value: Option[A])(write: A => Unit): Unit =
    value match
      case None =>
        bool(false)
      case Some(present) =>
        bool(true)
        write(present)

  def vector[A](values: Vector[A])(write: A => Unit): Unit =
    int(values.length)
    values.foreach(write)

  def result(): IArray[Byte] =
    IArray.unsafeFromArray(bytes.toArray)

private[alder] final class BinaryReader(
    source: IArray[Byte]
):
  private val bytes = IArray.from(source)
  private var offset = 0

  def byte: Either[CodecError, Int] =
    if offset >= bytes.length then
      Left(CodecError.Malformed("unexpected end of payload"))
    else
      val value = bytes(offset).toInt & 0xff
      offset += 1
      Right(value)

  def bool: Either[CodecError, Boolean] =
    byte.flatMap {
      case 0 => Right(false)
      case 1 => Right(true)
      case value =>
        Left(CodecError.Malformed(s"invalid boolean tag $value"))
    }

  def int: Either[CodecError, Int] =
    for
      first <- byte
      second <- byte
      third <- byte
      fourth <- byte
    yield
      (first << 24) |
        (second << 16) |
        (third << 8) |
        fourth

  def long: Either[CodecError, Long] =
    for
      high <- int
      low <- int
    yield
      (high.toLong << 32) |
        (low.toLong & 0xffffffffL)

  def double: Either[CodecError, Double] =
    long.map(java.lang.Double.longBitsToDouble)

  def string: Either[CodecError, String] =
    int.flatMap { length =>
      if length < 0 || length > remaining / 4 then
        Left(CodecError.Malformed(s"invalid string length $length"))
      else
        val builder = new scala.collection.mutable.StringBuilder
        var index = 0
        var result: Either[CodecError, Unit] = Right(())
        while index < length && result.isRight do
          result = int.flatMap { code =>
            if code < Char.MinValue.toInt || code > Char.MaxValue.toInt
            then
              Left(
                CodecError.Malformed(
                  s"invalid UTF-16 code unit $code"
                )
              )
            else
              val _ = builder.append(code.toChar)
              Right(())
          }
          index += 1
        result.map(_ => builder.result())
    }

  def payload: Either[CodecError, IArray[Byte]] =
    int.flatMap { length =>
      if length < 0 || length > remaining then
        Left(CodecError.Malformed(s"invalid payload length $length"))
      else
        val output = new Array[Byte](length)
        var index = 0
        while index < length do
          output(index) = bytes(offset + index)
          index += 1
        offset += length
        Right(IArray.unsafeFromArray(output))
    }

  def option[A](
      read: => Either[CodecError, A]
  ): Either[CodecError, Option[A]] =
    bool.flatMap {
      case false => Right(None)
      case true  => read.map(Some(_))
    }

  def vector[A](
      read: => Either[CodecError, A]
  ): Either[CodecError, Vector[A]] =
    int.flatMap { length =>
      if length < 0 then
        Left(CodecError.Malformed(s"invalid vector length $length"))
      else
        val builder = Vector.newBuilder[A]
        var index = 0
        var result: Either[CodecError, Unit] = Right(())
        while index < length && result.isRight do
          result = read.map { value =>
            builder += value
            ()
          }
          index += 1
        result.map(_ => builder.result())
    }

  def tag(name: String, maximum: Int): Either[CodecError, Int] =
    byte.flatMap { value =>
      if value <= maximum then Right(value)
      else Left(CodecError.Malformed(s"invalid $name tag $value"))
    }

  def enumeration[A](
      name: String,
      values: Vector[A]
  ): Either[CodecError, A] =
    byte.flatMap { value =>
      values.lift(value) match
        case Some(result) => Right(result)
        case None =>
          Left(CodecError.Malformed(s"invalid $name tag $value"))
    }

  def finish[A](value: A): Either[CodecError, A] =
    if remaining == 0 then Right(value)
    else
      Left(
        CodecError.Malformed(
          s"$remaining trailing bytes after the payload"
        )
      )

  private def remaining: Int = bytes.length - offset

private[kernel] object AuditBinaryCodec:
  def encode(audit: Audit): IArray[Byte] =
    val writer = new BinaryWriter
    writeAudit(writer, audit)
    writer.result()

  def decode(bytes: IArray[Byte]): Either[CodecError, Audit] =
    val reader = new BinaryReader(bytes)
    readAudit(reader).flatMap(reader.finish)

  private def writeAudit(writer: BinaryWriter, audit: Audit): Unit =
    writer.string(audit.plan.render)
    writeDataFingerprint(writer, audit.data)
    writeSchemaFingerprint(writer, audit.schema)
    writer.long(audit.seed.value)
    writeBackendFingerprint(writer, audit.backend)
    writeNumericMode(writer, audit.numerics)
    writePreparation(writer, audit.preparation)
    writeComponent(writer, audit.component)
    writer.vector(audit.children)(writeAudit(writer, _))
    writer.option(audit.refit)(writeRefit(writer, _))
    writeAuditShape(writer, audit.shape)

  private def readAudit(
      reader: BinaryReader
  ): Either[CodecError, Audit] =
    for
      plan <- reader.string.map(PlanFingerprint(_))
      data <- readDataFingerprint(reader)
      schema <- readSchemaFingerprint(reader)
      seed <- reader.long.map(Seed(_))
      backend <- readBackendFingerprint(reader)
      numericMode <- readNumericMode(reader)
      preparation <- readPreparation(reader)
      component <- readComponent(reader)
      children <- reader.vector(readAudit(reader))
      refit <- reader.option(readRefit(reader))
      shape <- readAuditShape(reader)
    yield
      new Audit(
        plan,
        data,
        schema,
        seed,
        backend,
        numericMode,
        preparation,
        component,
        children,
        refit,
        shape
      )

  private def writeAuditValue(
      writer: BinaryWriter,
      value: AuditValue
  ): Unit =
    value match
      case AuditValue.Integer(number) =>
        writer.byte(0)
        writer.long(number)
      case AuditValue.Decimal(number) =>
        writer.byte(1)
        writer.double(number)
      case AuditValue.Text(text) =>
        writer.byte(2)
        writer.string(text)
      case AuditValue.Bool(boolean) =>
        writer.byte(3)
        writer.bool(boolean)
      case AuditValue.Sequence(values) =>
        writer.byte(4)
        writer.vector(values)(writeAuditValue(writer, _))
      case AuditValue.Record(fields) =>
        writer.byte(5)
        writer.vector(fields) { (name, field) =>
          writer.string(name)
          writeAuditValue(writer, field)
        }

  private def readAuditValue(
      reader: BinaryReader
  ): Either[CodecError, AuditValue] =
    reader.tag("audit value", 5).flatMap {
      case 0 => reader.long.map(AuditValue.Integer(_))
      case 1 => reader.double.map(AuditValue.Decimal(_))
      case 2 => reader.string.map(AuditValue.Text(_))
      case 3 => reader.bool.map(AuditValue.Bool(_))
      case 4 =>
        reader
          .vector(readAuditValue(reader))
          .map(AuditValue.Sequence(_))
      case 5 =>
        reader
          .vector(
            for
              name <- reader.string
              value <- readAuditValue(reader)
            yield (name, value)
          )
          .map(AuditValue.Record(_))
      case value =>
        Left(
          CodecError.Malformed(s"invalid audit value tag $value")
        )
    }

  private def writePolicy(
      writer: BinaryWriter,
      policy: FingerprintPolicy
  ): Unit =
    policy match
      case FingerprintPolicy.ContentDigest(algorithm) =>
        writer.byte(0)
        writer.string(algorithm)
      case FingerprintPolicy.SourceIdentity(uri, version) =>
        writer.byte(1)
        writer.string(uri)
        writer.string(version)
      case FingerprintPolicy.Summary(policyId) =>
        writer.byte(2)
        writer.string(policyId)

  private def readPolicy(
      reader: BinaryReader
  ): Either[CodecError, FingerprintPolicy] =
    reader.tag("fingerprint policy", 2).flatMap {
      case 0 =>
        reader.string.map(FingerprintPolicy.ContentDigest(_))
      case 1 =>
        for
          uri <- reader.string
          version <- reader.string
        yield FingerprintPolicy.SourceIdentity(uri, version)
      case 2 =>
        reader.string.map(FingerprintPolicy.Summary(_))
      case value =>
        Left(
          CodecError.Malformed(
            s"invalid fingerprint policy tag $value"
          )
        )
    }

  private def writeDataFingerprint(
      writer: BinaryWriter,
      fingerprint: DataFingerprint
  ): Unit =
    writePolicy(writer, fingerprint.policy)
    writer.string(fingerprint.digest)

  private def readDataFingerprint(
      reader: BinaryReader
  ): Either[CodecError, DataFingerprint] =
    for
      policy <- readPolicy(reader)
      digest <- reader.string
    yield new DataFingerprint(policy, digest)

  private def writeProtocolFingerprint(
      writer: BinaryWriter,
      fingerprint: ProtocolFingerprint
  ): Unit =
    writePolicy(writer, fingerprint.policy)
    writer.string(fingerprint.digest)

  private def readProtocolFingerprint(
      reader: BinaryReader
  ): Either[CodecError, ProtocolFingerprint] =
    for
      policy <- readPolicy(reader)
      digest <- reader.string
    yield new ProtocolFingerprint(policy, digest)

  private def writeSchemaFingerprint(
      writer: BinaryWriter,
      fingerprint: SchemaFingerprint
  ): Unit =
    writePolicy(writer, fingerprint.policy)
    writer.string(fingerprint.digest)

  private def readSchemaFingerprint(
      reader: BinaryReader
  ): Either[CodecError, SchemaFingerprint] =
    for
      policy <- readPolicy(reader)
      digest <- reader.string
    yield SchemaFingerprint(policy, digest)

  private def writeBackendFingerprint(
      writer: BinaryWriter,
      fingerprint: BackendFingerprint
  ): Unit =
    writer.string(fingerprint.id)
    writer.string(fingerprint.version)
    writeAuditValue(writer, fingerprint.details)

  private def readBackendFingerprint(
      reader: BinaryReader
  ): Either[CodecError, BackendFingerprint] =
    for
      id <- reader.string
      version <- reader.string
      details <- readAuditValue(reader)
    yield BackendFingerprint(id, version, details)

  private def writeNumericMode(
      writer: BinaryWriter,
      mode: NumericMode
  ): Unit =
    mode match
      case NumericMode.Deterministic =>
        writer.byte(0)
      case NumericMode.FastMath =>
        writer.byte(1)
      case NumericMode.NonDeterministic(description) =>
        writer.byte(2)
        writer.string(description)

  private def readNumericMode(
      reader: BinaryReader
  ): Either[CodecError, NumericMode] =
    reader.tag("numeric mode", 2).flatMap {
      case 0 => Right(NumericMode.Deterministic)
      case 1 => Right(NumericMode.FastMath)
      case 2 =>
        reader.string.map(NumericMode.NonDeterministic(_))
      case value =>
        Left(
          CodecError.Malformed(s"invalid numeric mode tag $value")
        )
    }

  private def writeStage(
      writer: BinaryWriter,
      stage: StagePath
  ): Unit =
    writer.vector(stage.segments)(writer.int)

  private def readStage(
      reader: BinaryReader
  ): Either[CodecError, StagePath] =
    reader.vector(reader.int).map(StagePath(_))

  private def writeScope(
      writer: BinaryWriter,
      scope: PreparationScopeTag
  ): Unit =
    scope match
      case PreparationScopeTag.Reusable     => writer.byte(0)
      case PreparationScopeTag.LearnerReady => writer.byte(1)

  private def readScope(
      reader: BinaryReader
  ): Either[CodecError, PreparationScopeTag] =
    reader.enumeration(
      "preparation scope",
      Vector(
        PreparationScopeTag.Reusable,
        PreparationScopeTag.LearnerReady
      )
    )

  private def writePreparationShape(
      writer: BinaryWriter,
      shape: PreparationLineageShape
  ): Unit =
    shape match
      case PreparationLineageShape.Leaf        => writer.byte(0)
      case PreparationLineageShape.Sequence    => writer.byte(1)
      case PreparationLineageShape.CrossFitted => writer.byte(2)

  private def readPreparationShape(
      reader: BinaryReader
  ): Either[CodecError, PreparationLineageShape] =
    reader.enumeration(
      "preparation shape",
      Vector(
        PreparationLineageShape.Leaf,
        PreparationLineageShape.Sequence,
        PreparationLineageShape.CrossFitted
      )
    )

  private def writePreparation(
      writer: BinaryWriter,
      preparation: PreparationLineage
  ): Unit =
    writeStage(writer, preparation.stage)
    writeScope(writer, preparation.scope)
    writer.vector(preparation.children)(writePreparation(writer, _))
    writer.option(preparation.crossFit)(writeCrossFit(writer, _))
    writePreparationShape(writer, preparation.shape)

  private def readPreparation(
      reader: BinaryReader
  ): Either[CodecError, PreparationLineage] =
    for
      stage <- readStage(reader)
      scope <- readScope(reader)
      children <- reader.vector(readPreparation(reader))
      crossFit <- reader.option(readCrossFit(reader))
      shape <- readPreparationShape(reader)
    yield new PreparationLineage(stage, scope, children, crossFit, shape)

  private def writeFold(
      writer: BinaryWriter,
      fold: CrossFitFoldLineage
  ): Unit =
    writer.int(fold.index)
    writeDataFingerprint(writer, fold.analysis)
    writeDataFingerprint(writer, fold.assessment)
    writePreparation(writer, fold.fittedState)

  private def readFold(
      reader: BinaryReader
  ): Either[CodecError, CrossFitFoldLineage] =
    for
      index <- reader.int
      analysis <- readDataFingerprint(reader)
      assessment <- readDataFingerprint(reader)
      fittedState <- readPreparation(reader)
    yield
      new CrossFitFoldLineage(
        index,
        analysis,
        assessment,
        fittedState
      )

  private def writeTessera(
      writer: BinaryWriter,
      receipt: TesseraPlanReceipt
  ): Unit =
    writer.string(receipt.designAlgorithm)
    writer.string(receipt.digestAlgorithm)
    writeProtocolFingerprint(writer, receipt.design)
    writeDataFingerprint(writer, receipt.population)
    writer.option(receipt.labels)(writeDataFingerprint(writer, _))
    writer.long(receipt.planSeed.value)
    writeDataFingerprint(writer, receipt.assignment)

  private def readTessera(
      reader: BinaryReader
  ): Either[CodecError, TesseraPlanReceipt] =
    for
      designAlgorithm <- reader.string
      digestAlgorithm <- reader.string
      design <- readProtocolFingerprint(reader)
      population <- readDataFingerprint(reader)
      labels <- reader.option(readDataFingerprint(reader))
      planSeed <- reader.long.map(Seed(_))
      assignment <- readDataFingerprint(reader)
    yield
      new TesseraPlanReceipt(
        designAlgorithm,
        digestAlgorithm,
        design,
        population,
        labels,
        planSeed,
        assignment
      )

  private def writeCrossFit(
      writer: BinaryWriter,
      crossFit: CrossFitLineage
  ): Unit =
    writeProtocolFingerprint(writer, crossFit.resampler)
    writer.long(crossFit.seed.value)
    writeDataFingerprint(writer, crossFit.assignment)
    writer.vector(crossFit.folds)(writeFold(writer, _))
    writePreparation(writer, crossFit.serving)
    writer.option(crossFit.tessera)(writeTessera(writer, _))

  private def readCrossFit(
      reader: BinaryReader
  ): Either[CodecError, CrossFitLineage] =
    for
      resampler <- readProtocolFingerprint(reader)
      seed <- reader.long.map(Seed(_))
      assignment <- readDataFingerprint(reader)
      folds <- reader.vector(readFold(reader))
      serving <- readPreparation(reader)
      tessera <- reader.option(readTessera(reader))
    yield
      new CrossFitLineage(
        resampler,
        seed,
        assignment,
        folds,
        serving,
        tessera
      )

  private def writeComponent(
      writer: BinaryWriter,
      component: ComponentDescriptor
  ): Unit =
    writer.string(component.id.render)
    writer.string(component.version.render)
    writeAuditValue(writer, component.parameters)
    writeBackendFingerprint(writer, component.backend)

  private def readComponent(
      reader: BinaryReader
  ): Either[CodecError, ComponentDescriptor] =
    for
      id <- reader.string
      version <- reader.string
      parameters <- readAuditValue(reader)
      backend <- readBackendFingerprint(reader)
    yield
      ComponentDescriptor(
        ComponentId(id),
        ComponentVersion(version),
        parameters,
        backend
      )

  private def writeObservedRole(
      writer: BinaryWriter,
      role: ObservedSourceRole
  ): Unit =
    role match
      case ObservedSourceRole.Train      => writer.byte(0)
      case ObservedSourceRole.Validation => writer.byte(1)
      case ObservedSourceRole.Test       => writer.byte(2)

  private def readObservedRole(
      reader: BinaryReader
  ): Either[CodecError, ObservedSourceRole] =
    reader.enumeration(
      "observed source role",
      Vector(
        ObservedSourceRole.Train,
        ObservedSourceRole.Validation,
        ObservedSourceRole.Test
      )
    )

  private def writeObservedSource(
      writer: BinaryWriter,
      source: ObservedSource
  ): Unit =
    writeObservedRole(writer, source.role)
    writeDataFingerprint(writer, source.fingerprint)

  private def readObservedSource(
      reader: BinaryReader
  ): Either[CodecError, ObservedSource] =
    for
      role <- readObservedRole(reader)
      fingerprint <- readDataFingerprint(reader)
    yield ObservedSource(role, fingerprint)

  private def writeRefitClaim(
      writer: BinaryWriter,
      claim: RefitEvaluationClaim
  ): Unit =
    claim match
      case RefitEvaluationClaim
            .ArtifactNotEvaluatedOnAuthorizingValidation =>
        writer.byte(0)
      case RefitEvaluationClaim
            .ArtifactNotEvaluatedOnAuthorizingTest =>
        writer.byte(1)

  private def readRefitClaim(
      reader: BinaryReader
  ): Either[CodecError, RefitEvaluationClaim] =
    reader.enumeration(
      "refit claim",
      Vector(
        RefitEvaluationClaim
          .ArtifactNotEvaluatedOnAuthorizingValidation,
        RefitEvaluationClaim
          .ArtifactNotEvaluatedOnAuthorizingTest
      )
    )

  private def writeRefit(
      writer: BinaryWriter,
      refit: RefitAudit
  ): Unit =
    writer.vector(refit.sources)(writeObservedSource(writer, _))
    writer.string(refit.receipt.render)
    writeRefitClaim(writer, refit.claim)

  private def readRefit(
      reader: BinaryReader
  ): Either[CodecError, RefitAudit] =
    for
      sources <- reader.vector(readObservedSource(reader))
      receipt <- reader.string.map(EvaluationReceiptId(_))
      claim <- readRefitClaim(reader)
    yield new RefitAudit(sources, receipt, claim)

  private def writeAuditShape(
      writer: BinaryWriter,
      shape: AuditShape
  ): Unit =
    shape match
      case AuditShape.Leaf                => writer.byte(0)
      case AuditShape.Composite           => writer.byte(1)
      case AuditShape.TransformSequence   => writer.byte(2)
      case AuditShape.FeatureMapSequence  => writer.byte(3)
      case AuditShape.FoldEncoderSequence => writer.byte(4)
      case AuditShape.WorkflowSequence    => writer.byte(5)

  private def readAuditShape(
      reader: BinaryReader
  ): Either[CodecError, AuditShape] =
    reader.enumeration(
      "audit shape",
      Vector(
        AuditShape.Leaf,
        AuditShape.Composite,
        AuditShape.TransformSequence,
        AuditShape.FeatureMapSequence,
        AuditShape.FoldEncoderSequence,
        AuditShape.WorkflowSequence
      )
    )

import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

// Canonical spec: PRD.json. Scala 3.3.8 LTS is the publication baseline (D1);
// pure modules cross-build JVM + JS + Native (D16).
ThisBuild / organization := "io.github.canardlapin"
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / licenses     := Seq(License.Apache2)

val catsV            = "2.13.0"
val munitV           = "1.3.4"
val disciplineMunitV = "2.0.0"
val scalacheckV      = "1.18.1"

// Development composite for the zero-runtime-dependency resampling protocol.
// Stable Alder releases pin a published tessera-core version instead.
lazy val tesseraBuild      = file("../tessera").toURI
lazy val tesseraCoreJVM    = ProjectRef(tesseraBuild, "coreJVM")
lazy val tesseraCoreJS     = ProjectRef(tesseraBuild, "coreJS")
lazy val tesseraCoreNative = ProjectRef(tesseraBuild, "coreNative")
lazy val tesseraDesignsJVM = ProjectRef(tesseraBuild, "designsJVM")
lazy val tesseraDesignsJS  = ProjectRef(tesseraBuild, "designsJS")
lazy val tesseraDesignsNative =
  ProjectRef(tesseraBuild, "designsNative")

// Development composites for the two independent ridge implementations.
// ridge-gale remains non-publishable until Gale has a released compatible
// version; source dependencies are deliberately a development-only bridge.
lazy val galeBuild   = file("../gale").toURI
lazy val galeCoreJVM = ProjectRef(galeBuild, "coreJVM")
lazy val galeCoreJS  = ProjectRef(galeBuild, "coreJS")

lazy val linop4sBuild        = file("../linop4s").toURI
lazy val linop4sKrylovJVM    = ProjectRef(linop4sBuild, "krylovJVM")
lazy val linop4sKrylovJS     = ProjectRef(linop4sBuild, "krylovJS")
lazy val linop4sKrylovNative = ProjectRef(linop4sBuild, "krylovNative")

// Alder's own source-quality policy (PRD buildAndCompatibility.compilerFlags),
// never a consumer requirement.
lazy val strictSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Wunused:all",
    "-Wvalue-discard",
    "-Yexplicit-nulls",
    "-language:strictEquality",
    "-Werror"
  )
)

/** The compatibility-sensitive core protocol: Pipe, Failure, data roles,
  * Prepared + preparation scopes, Trained, Transform, FeatureMap, FoldEncoder,
  * Learner, FitContext, audit contracts, capability typeclasses.
  * Depends on cats-core only (D12 forbidden-dependency policy).
  */
lazy val kernel = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("kernel"))
  .settings(strictSettings)
  .settings(
    name := "alder-kernel",
    libraryDependencies += "org.typelevel" %%% "cats-core" % catsV
  )

lazy val kernelJVM    = kernel.jvm
lazy val kernelJS     = kernel.js
lazy val kernelNative = kernel.native

/** Published Discipline law suites. munit/discipline/scalacheck are declared at
  * compile scope because this module PUBLISHES suites; they enter consumers'
  * dependency graphs at Test scope only.
  */
lazy val laws = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("laws"))
  .dependsOn(kernel)
  .settings(strictSettings)
  .settings(
    name := "alder-laws",
    libraryDependencies ++= Seq(
      "org.scalameta"  %%% "munit"            % munitV,
      "org.typelevel"  %%% "discipline-munit" % disciplineMunitV,
      "org.typelevel"  %%% "cats-laws"        % catsV,
      "org.scalacheck" %%% "scalacheck"       % scalacheckV
    )
  )

lazy val lawsJVM    = laws.jvm
lazy val lawsJS     = laws.js
lazy val lawsNative = laws.native

/** Published generators and leakage-tracing fixtures for Alder law suites and
  * downstream plugin tests.
  */
lazy val testkit = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("testkit"))
  .dependsOn(kernel)
  .settings(strictSettings)
  .settings(
    name := "alder-testkit",
    libraryDependencies ++= Seq(
      "org.scalacheck" %%% "scalacheck" % scalacheckV,
      "org.scalameta"  %%% "munit"      % munitV % Test
    )
  )

lazy val testkitJVM    = testkit.jvm
lazy val testkitJS     = testkit.js
lazy val testkitNative = testkit.native

/** Immutable in-memory data, typed splitting, and resampling protocols.
  * Cross-fitting constructors live here beside CompleteResampler so the
  * dependency remains alder-data -> alder-kernel.
  */
lazy val data = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("data"))
  .dependsOn(kernel, testkit % "test->compile")
  .settings(strictSettings)
  .settings(
    name := "alder-data",
    libraryDependencies ++= Seq(
      "org.scalameta"  %%% "munit"      % munitV      % Test,
      "org.scalacheck" %%% "scalacheck"       % scalacheckV % Test
    )
  )

lazy val dataJVM =
  data.jvm.dependsOn(
    tesseraCoreJVM,
    tesseraDesignsJVM % "test->compile"
  )
lazy val dataJS =
  data.js.dependsOn(
    tesseraCoreJS,
    tesseraDesignsJS % "test->compile"
  )
lazy val dataNative =
  data.native.dependsOn(
    tesseraCoreNative,
    tesseraDesignsNative % "test->compile"
  )

/** Target-blind preprocessing with representation-branded outputs. */
lazy val preprocess = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("preprocess"))
  .dependsOn(kernel, data, testkit % "test->compile")
  .settings(strictSettings)
  .settings(
    name := "alder-preprocess",
    libraryDependencies ++= Seq(
      "org.scalameta"  %%% "munit"      % munitV      % Test,
      "org.scalacheck" %%% "scalacheck" % scalacheckV % Test
    )
  )

lazy val preprocessJVM    = preprocess.jvm
lazy val preprocessJS     = preprocess.js
lazy val preprocessNative = preprocess.native

/** Typed streaming metrics with reproducible mergeable accumulators. */
lazy val metrics = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("metrics"))
  .dependsOn(kernel)
  .settings(strictSettings)
  .settings(
    name := "alder-metrics",
    libraryDependencies ++= Seq(
      "org.typelevel"  %%% "cats-kernel" % catsV,
      "org.scalameta"  %%% "munit"       % munitV      % Test,
      "org.scalacheck" %%% "scalacheck"  % scalacheckV % Test
    )
  )

lazy val metricsJVM    = metrics.jvm
lazy val metricsJS     = metrics.js
lazy val metricsNative = metrics.native

/** Published Metric Discipline suites. The downstream law artifact preserves
  * the one-way kernel -> metrics -> metrics-laws dependency (D22).
  */
lazy val metricsLaws = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("metrics-laws"))
  .dependsOn(metrics, testkit)
  .settings(strictSettings)
  .settings(
    name := "alder-metrics-laws",
    libraryDependencies ++= Seq(
      "org.scalameta"  %%% "munit"            % munitV,
      "org.typelevel"  %%% "discipline-munit" % disciplineMunitV,
      "org.scalacheck" %%% "scalacheck"       % scalacheckV
    )
  )

lazy val metricsLawsJVM    = metricsLaws.jvm
lazy val metricsLawsJS     = metricsLaws.js
lazy val metricsLawsNative = metricsLaws.native

/** Backend-neutral linear-model contracts and typed ridge learners. */
lazy val modelsLinear = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("models-linear"))
  .dependsOn(kernel, data, testkit % "test->compile")
  .settings(strictSettings)
  .settings(
    name := "alder-models-linear",
    libraryDependencies += "org.scalameta" %%% "munit" % munitV % Test
  )

lazy val modelsLinearJVM    = modelsLinear.jvm
lazy val modelsLinearJS     = modelsLinear.js
lazy val modelsLinearNative = modelsLinear.native

/** Dense Gale ridge adapter. Publication is blocked on a released Gale. */
lazy val ridgeGale = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("ridge-gale"))
  .dependsOn(modelsLinear, testkit % "test->compile")
  .settings(strictSettings)
  .settings(
    name           := "alder-ridge-gale",
    publish / skip := true,
    libraryDependencies += "org.scalameta" %%% "munit" % munitV % Test
  )

lazy val ridgeGaleJVM =
  ridgeGale.jvm.dependsOn(
    galeCoreJVM,
    ridgeLinop4sJVM % "test->compile"
  )
lazy val ridgeGaleJS =
  ridgeGale.js.dependsOn(
    galeCoreJS,
    ridgeLinop4sJS % "test->compile"
  )

/** Matrix-free linop4s LSQR/CG ridge adapter. */
lazy val ridgeLinop4s =
  crossProject(JVMPlatform, JSPlatform, NativePlatform)
    .crossType(CrossType.Pure)
    .in(file("ridge-linop4s"))
    .dependsOn(modelsLinear, testkit % "test->compile")
    .settings(strictSettings)
    .settings(
      name := "alder-ridge-linop4s",
      libraryDependencies += "org.scalameta" %%% "munit" % munitV % Test
    )

lazy val ridgeLinop4sJVM =
  ridgeLinop4s.jvm.dependsOn(linop4sKrylovJVM)
lazy val ridgeLinop4sJS =
  ridgeLinop4s.js.dependsOn(linop4sKrylovJS)
lazy val ridgeLinop4sNative =
  ridgeLinop4s.native.dependsOn(linop4sKrylovNative)

lazy val root = project
  .in(file("."))
  .aggregate(
    kernelJVM,
    kernelJS,
    kernelNative,
    lawsJVM,
    lawsJS,
    lawsNative,
    testkitJVM,
    testkitJS,
    testkitNative,
    dataJVM,
    dataJS,
    dataNative,
    preprocessJVM,
    preprocessJS,
    preprocessNative,
    metricsJVM,
    metricsJS,
    metricsNative,
    metricsLawsJVM,
    metricsLawsJS,
    metricsLawsNative,
    modelsLinearJVM,
    modelsLinearJS,
    modelsLinearNative,
    ridgeGaleJVM,
    ridgeGaleJS,
    ridgeLinop4sJVM,
    ridgeLinop4sJS,
    ridgeLinop4sNative
  )
  .settings(
    name           := "alder",
    publish / skip := true
  )

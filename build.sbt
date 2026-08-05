import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

// Canonical spec: PRD.json. Scala 3.7.4 is the publication baseline (D1);
// pure modules cross-build JVM + JS + Native (D16).
ThisBuild / organization  := "io.github.canardlapin"
ThisBuild / scalaVersion  := "3.7.4"
ThisBuild / version       := "0.1.0-SNAPSHOT"
ThisBuild / licenses      := Seq(License.Apache2)
ThisBuild / versionScheme := Some("early-semver")

lazy val alderCompatibilityBaseline =
  settingKey[Option[String]](
    "Published Alder version used by MiMa and TASTy-MiMa."
  )

ThisBuild / alderCompatibilityBaseline :=
  sys.props
    .get("alder.compatibility.previous")
    .map(_.trim)
    .filter(_.nonEmpty)

// MiMa is a JVM classfile checker. Platform projections and non-publishable
// aggregate projects are explicit no-ops unless compatibilitySettings
// overrides them below.
ThisBuild / mimaPreviousArtifacts := Set.empty

// Statement coverage is a local diagnostic. Prefer JVM module reports over the
// aggregate because JS/Native do not contribute measurements under Scala 3.
ThisBuild / coverageExcludedPackages :=
  "<empty>;.*\\.js\\..*;.*\\.native\\..*"

val catsV            = "2.13.0"
val munitV           = "1.3.4"
val disciplineMunitV = "2.0.0"
val scalacheckV      = "1.18.1"
val resample4sV      = "0.1.0-SNAPSHOT"

// Development composite for the zero-runtime-dependency resampling protocol.
// Stable Alder releases pin a published resample4s-core version instead.
lazy val resample4sBuild      = file("../resample4s").toURI
lazy val resample4sCoreJVM    = ProjectRef(resample4sBuild, "coreJVM")
lazy val resample4sCoreJS     = ProjectRef(resample4sBuild, "coreJS")
lazy val resample4sCoreNative = ProjectRef(resample4sBuild, "coreNative")
lazy val resample4sDesignsJVM = ProjectRef(resample4sBuild, "designsJVM")
lazy val resample4sDesignsJS  = ProjectRef(resample4sBuild, "designsJS")
lazy val resample4sDesignsNative =
  ProjectRef(resample4sBuild, "designsNative")

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
  ),
  Compile / doc / scalacOptions ++= Seq(
    "-project-version",
    version.value,
    "-groups",
    "-snippet-compiler:compile",
    "-doc-root-content",
    (
      (ThisBuild / baseDirectory).value /
        "site" /
        "scaladoc-root.md"
    ).getAbsolutePath
  )
)

/** The first 0.1.0 release has no previous Alder artifact, so compatibility
  * tasks intentionally compare an empty set. After publishing 0.1.0, release
  * and CI invocations set `-Dalder.compatibility.previous=0.1.0`. Applying
  * these settings to JVM projections checks JVM binary compatibility and the
  * shared Scala 3 public surface. Pure cross-projects compile that same source
  * for Scala.js and Scala Native in the aggregate test gate.
  */
lazy val compatibilitySettings = Seq(
  mimaPreviousArtifacts :=
    alderCompatibilityBaseline.value
      .map(baseline =>
        (organization.value % moduleName.value % baseline)
          .cross(crossVersion.value)
      )
      .toSet,
  tastyMiMaPreviousArtifacts :=
    alderCompatibilityBaseline.value
      .map(baseline =>
        (organization.value % moduleName.value % baseline)
          .cross(crossVersion.value)
      )
      .toSet
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

lazy val kernelJVM    = kernel.jvm.settings(compatibilitySettings)
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

lazy val lawsJVM    = laws.jvm.settings(compatibilitySettings)
lazy val lawsJS     = laws.js
lazy val lawsNative = laws.native

/** External-package consumer fixtures that prove SPI usability outside
  * `package alder`. Not published.
  */
lazy val consumerFixture = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("consumer-fixture"))
  .dependsOn(kernel, laws % Test, testkit % "test->compile")
  .settings(strictSettings)
  .settings(
    name           := "alder-consumer-fixture",
    publish / skip := true,
    libraryDependencies += "org.scalameta" %%% "munit" % munitV % Test
  )

lazy val consumerFixtureJVM    = consumerFixture.jvm
lazy val consumerFixtureJS     = consumerFixture.js
lazy val consumerFixtureNative = consumerFixture.native

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

lazy val testkitJVM    = testkit.jvm.settings(compatibilitySettings)
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
  if (file("../resample4s").isDirectory)
    data.jvm
      .dependsOn(
        resample4sCoreJVM,
        resample4sDesignsJVM % "test->compile"
      )
      .settings(compatibilitySettings)
  else
    data.jvm
      .settings(
        libraryDependencies ++= Seq(
          "io.github.canardlapin" %% "resample4s-core" % resample4sV,
          "io.github.canardlapin" %% "resample4s-designs" % resample4sV % Test
        )
      )
      .settings(compatibilitySettings)
lazy val dataJS =
  if (file("../resample4s").isDirectory)
    data.js.dependsOn(
      resample4sCoreJS,
      resample4sDesignsJS % "test->compile"
    )
  else
    data.js.settings(
      libraryDependencies ++= Seq(
        "io.github.canardlapin" %%% "resample4s-core" % resample4sV,
        "io.github.canardlapin" %%% "resample4s-designs" % resample4sV % Test
      )
    )
lazy val dataNative =
  if (file("../resample4s").isDirectory)
    data.native.dependsOn(
      resample4sCoreNative,
      resample4sDesignsNative % "test->compile"
    )
  else
    data.native.settings(
      libraryDependencies ++= Seq(
        "io.github.canardlapin" %%% "resample4s-core" % resample4sV,
        "io.github.canardlapin" %%% "resample4s-designs" % resample4sV % Test
      )
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

lazy val preprocessJVM    = preprocess.jvm.settings(compatibilitySettings)
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

lazy val metricsJVM    = metrics.jvm.settings(compatibilitySettings)
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

lazy val metricsLawsJVM    = metricsLaws.jvm.settings(compatibilitySettings)
lazy val metricsLawsJS     = metricsLaws.js
lazy val metricsLawsNative = metricsLaws.native

/** Scored evaluation, explicit selection evidence, and receipt-gated refit.
  * This layer combines alder-data and alder-metrics without reversing either
  * dependency.
  */
lazy val application = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("application"))
  .dependsOn(
    kernel,
    data,
    metrics,
    testkit % "test->compile"
  )
  .settings(strictSettings)
  .settings(
    name := "alder-application",
    libraryDependencies ++= Seq(
      "org.scalameta"  %%% "munit"      % munitV      % Test,
      "org.scalacheck" %%% "scalacheck" % scalacheckV % Test
    )
  )

lazy val applicationJVM =
  application.jvm.settings(compatibilitySettings)
lazy val applicationJS     = application.js
lazy val applicationNative = application.native

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

lazy val modelsLinearJVM =
  modelsLinear.jvm.settings(compatibilitySettings)
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
  ridgeLinop4s.jvm
    .dependsOn(linop4sKrylovJVM)
    .settings(compatibilitySettings)
lazy val ridgeLinop4sJS =
  ridgeLinop4s.js.dependsOn(linop4sKrylovJS)
lazy val ridgeLinop4sNative =
  ridgeLinop4s.native.dependsOn(linop4sKrylovNative)

/** Curated batteries for the ordinary supervised getting-started path. */
lazy val quickstart = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("quickstart"))
  .dependsOn(
    application,
    preprocess,
    modelsLinear,
    ridgeLinop4s,
    tune,
    testkit % "test->compile"
  )
  .settings(strictSettings)
  .settings(
    name := "alder-quickstart",
    libraryDependencies += "org.scalameta" %%% "munit" % munitV % Test
  )

lazy val quickstartJVM =
  quickstart.jvm.settings(compatibilitySettings)
lazy val quickstartJS     = quickstart.js
lazy val quickstartNative = quickstart.native

/** Valid-by-construction search spaces, deterministic interpreters, and the
  * explicit model-erasure boundary used by tuning.
  */
lazy val tune = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("tune"))
  .dependsOn(kernel, data, metrics, testkit % "test->compile")
  .settings(strictSettings)
  .settings(
    name := "alder-tune",
    libraryDependencies += "org.scalameta" %%% "munit" % munitV % Test
  )

lazy val tuneJVM    = tune.jvm.settings(compatibilitySettings)
lazy val tuneJS     = tune.js
lazy val tuneNative = tune.native

/** Published Discipline laws for spaces, erasure, and study role boundaries. */
lazy val tuneLaws = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("tune-laws"))
  .dependsOn(tune, laws, testkit)
  .settings(strictSettings)
  .settings(
    name := "alder-tune-laws",
    libraryDependencies ++= Seq(
      "org.scalameta"  %%% "munit"            % munitV,
      "org.typelevel"  %%% "discipline-munit" % disciplineMunitV,
      "org.scalacheck" %%% "scalacheck"       % scalacheckV
    )
  )

lazy val tuneLawsJVM    = tuneLaws.jvm.settings(compatibilitySettings)
lazy val tuneLawsJS     = tuneLaws.js
lazy val tuneLawsNative = tuneLaws.native

/** Versioned artifact-codec constructors and structural codec derivation. */
lazy val codec = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("codec"))
  .dependsOn(
    kernel,
    laws % "test->compile",
    testkit % "test->compile"
  )
  .settings(strictSettings)
  .settings(
    name := "alder-codec",
    libraryDependencies += "org.scalameta" %%% "munit" % munitV % Test
  )

lazy val codecJVM    = codec.jvm.settings(compatibilitySettings)
lazy val codecJS     = codec.js
lazy val codecNative = codec.native

/** Local performance baselines. Not published; documents 100k-row budgets. */
lazy val benchmarks = project
  .in(file("benchmarks"))
  .dependsOn(
    dataJVM,
    preprocessJVM,
    applicationJVM,
    modelsLinearJVM,
    ridgeLinop4sJVM,
    quickstartJVM,
    testkitJVM
  )
  .settings(strictSettings)
  .settings(
    name           := "alder-benchmarks",
    publish / skip := true,
    libraryDependencies += "org.scalameta" %% "munit" % munitV % Test
  )

/** Curated public guide site. Its input is deliberately separate from docs/,
  * which contains internal reviews and release evidence.
  */
lazy val docs = project
  .in(file("site"))
  .dependsOn(
    kernelJVM,
    lawsJVM,
    testkitJVM,
    dataJVM,
    preprocessJVM,
    metricsJVM,
    metricsLawsJVM,
    applicationJVM,
    modelsLinearJVM,
    ridgeGaleJVM,
    ridgeLinop4sJVM,
    quickstartJVM,
    tuneJVM,
    tuneLawsJVM,
    codecJVM
  )
  .enablePlugins(TypelevelSitePlugin)
  .settings(
    name           := "alder-docs",
    publish / skip := true,
    mdocIn         := (ThisBuild / baseDirectory).value / "site-docs",
    // Laika validates links against the rendered site tree. mdoc's link hygiene
    // assumes its default source root and reports false positives for mdocIn.
    mdocExtraArguments += "--no-link-hygiene"
  )

lazy val root = project
  .in(file("."))
  .aggregate(
    kernelJVM,
    kernelJS,
    kernelNative,
    lawsJVM,
    lawsJS,
    lawsNative,
    consumerFixtureJVM,
    consumerFixtureJS,
    consumerFixtureNative,
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
    applicationJVM,
    applicationJS,
    applicationNative,
    modelsLinearJVM,
    modelsLinearJS,
    modelsLinearNative,
    ridgeGaleJVM,
    ridgeGaleJS,
    ridgeLinop4sJVM,
    ridgeLinop4sJS,
    ridgeLinop4sNative,
    quickstartJVM,
    quickstartJS,
    quickstartNative,
    tuneJVM,
    tuneJS,
    tuneNative,
    tuneLawsJVM,
    tuneLawsJS,
    tuneLawsNative,
    codecJVM,
    codecJS,
    codecNative,
    benchmarks
  )
  .settings(
    name           := "alder",
    publish / skip := true
  )

addCommandAlias(
  "compatibilityCheck",
  """;kernelJVM/mimaReportBinaryIssues
     |;lawsJVM/mimaReportBinaryIssues
     |;testkitJVM/mimaReportBinaryIssues
     |;dataJVM/mimaReportBinaryIssues
     |;preprocessJVM/mimaReportBinaryIssues
     |;metricsJVM/mimaReportBinaryIssues
     |;metricsLawsJVM/mimaReportBinaryIssues
     |;applicationJVM/mimaReportBinaryIssues
     |;modelsLinearJVM/mimaReportBinaryIssues
     |;ridgeLinop4sJVM/mimaReportBinaryIssues
     |;tuneJVM/mimaReportBinaryIssues
     |;tuneLawsJVM/mimaReportBinaryIssues
     |;codecJVM/mimaReportBinaryIssues
     |;kernelJVM/tastyMiMaReportIssues
     |;lawsJVM/tastyMiMaReportIssues
     |;testkitJVM/tastyMiMaReportIssues
     |;dataJVM/tastyMiMaReportIssues
     |;preprocessJVM/tastyMiMaReportIssues
     |;metricsJVM/tastyMiMaReportIssues
     |;metricsLawsJVM/tastyMiMaReportIssues
     |;applicationJVM/tastyMiMaReportIssues
     |;modelsLinearJVM/tastyMiMaReportIssues
     |;ridgeLinop4sJVM/tastyMiMaReportIssues
     |;tuneJVM/tastyMiMaReportIssues
     |;tuneLawsJVM/tastyMiMaReportIssues
     |;codecJVM/tastyMiMaReportIssues""".stripMargin
)

addCommandAlias(
  "apiDocs",
  """;kernelJVM/Compile/doc
     |;lawsJVM/Compile/doc
     |;testkitJVM/Compile/doc
     |;dataJVM/Compile/doc
     |;preprocessJVM/Compile/doc
     |;metricsJVM/Compile/doc
     |;metricsLawsJVM/Compile/doc
     |;applicationJVM/Compile/doc
     |;modelsLinearJVM/Compile/doc
     |;ridgeGaleJVM/Compile/doc
     |;ridgeLinop4sJVM/Compile/doc
     |;tuneJVM/Compile/doc
     |;tuneLawsJVM/Compile/doc
     |;codecJVM/Compile/doc""".stripMargin
)

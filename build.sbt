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

lazy val root = project
  .in(file("."))
  .aggregate(kernelJVM, kernelJS, kernelNative, lawsJVM, lawsJS, lawsNative)
  .settings(
    name           := "alder",
    publish / skip := true
  )

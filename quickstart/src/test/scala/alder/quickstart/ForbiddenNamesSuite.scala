package alder.quickstart

import munit.FunSuite

class ForbiddenNamesSuite extends FunSuite:
  test("getting-started source avoids forbidden proof names") {
    val text = scala.io.Source.fromFile(guidePath).mkString
    val forbidden =
      List(
        "cats.Id",
        "FitContext",
        "PhaseSeeds",
        "EvaluationSources",
        "Prepared",
        "EitherT"
      )
    forbidden.foreach { name =>
      assert(!text.contains(name), s"forbidden name present: $name")
    }
    assert(text.contains("import alder.quickstart.*"))
  }

  private def guidePath: String =
    val root = java.nio.file.Paths.get("").toAbsolutePath
    val candidates =
      List(
        root.resolve("site-docs/getting-started.md"),
        root.getParent.resolve("site-docs/getting-started.md")
      )
    candidates
      .map(_.toFile)
      .find(_.exists())
      .map(_.getAbsolutePath)
      .getOrElse(fail("missing site-docs/getting-started.md"))

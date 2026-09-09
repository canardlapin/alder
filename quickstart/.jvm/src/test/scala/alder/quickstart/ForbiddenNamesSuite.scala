package alder.quickstart

import munit.FunSuite

/** Source-level guide checks use the repository filesystem and therefore
  * belong to the JVM test configuration. The workflow itself remains in the
  * shared suite and compiles on JVM, Scala.js, and Scala Native.
  */
class ForbiddenNamesSuite extends FunSuite:
  test("first workflow has one import and avoids internal proof names") {
    val text = read(workflowPath)
    val firstWorkflow = marked(text)
    val forbidden =
      List(
        "cats.Id",
        "Unit",
        "Use",
        "FitContext",
        "PhaseSeeds",
        "EvaluationSources",
        "Prepared",
        "EitherT",
        "Linop4sRidgeBackend",
        "Blueprint",
        ".artifact",
        ".toString",
        ".select(",
        ".refit",
        ".test",
        ".deploymentRefit"
      )
    forbidden.foreach { name =>
      assert(!firstWorkflow.contains(name), s"forbidden name present: $name")
    }
    assertEquals(
      text.linesIterator.count(_.trim.startsWith("import ")),
      1
    )
  }

  test("README workflow is identical to compiled guide source") {
    assertEquals(marked(read(readmePath)), marked(read(workflowPath)))
  }

  test("first workflow stays within the recorded usability budget") {
    val source = marked(read(workflowPath))
    val nonDataLines = source.linesIterator
      .map(_.trim)
      .count(line => line.nonEmpty && !line.startsWith("//"))
    val namedDecisions = List(
      "zeroVariance =",
      "penalty =",
      "numerator =",
      "denominator =",
      "data =",
      "specification =",
      "seed =",
      "plan =",
      "learner =",
      "metric ="
    )

    assert(nonDataLines <= 20, s"workflow has $nonDataLines non-data lines")
    assert(source.contains(".learnWith("), "direct composition is not visible")
    namedDecisions.foreach { decision =>
      assert(source.contains(decision), s"unnamed workflow decision: $decision")
    }
  }

  private def read(path: String): String =
    val source = scala.io.Source.fromFile(path)
    try source.mkString
    finally source.close()

  private def marked(text: String): String =
    val start = "// alder-first-workflow:start"
    val end = "// alder-first-workflow:end"
    val startIndex = text.indexOf(start)
    val endIndex = text.indexOf(end, startIndex)
    assert(startIndex >= 0, s"missing marker: $start")
    assert(endIndex >= 0, s"missing marker: $end")
    text.substring(startIndex, endIndex + end.length)

  private def workflowPath: String =
    repositoryFile("site-docs/learn/workflow.md")

  private def readmePath: String =
    repositoryFile("README.md")

  private def repositoryFile(relative: String): String =
    val root = java.nio.file.Paths.get("").toAbsolutePath
    val candidates =
      List(
        root.resolve(relative),
        root.getParent.resolve(relative)
      )
    candidates
      .map(_.toFile)
      .find(_.exists())
      .map(_.getAbsolutePath)
      .getOrElse(fail(s"missing $relative"))

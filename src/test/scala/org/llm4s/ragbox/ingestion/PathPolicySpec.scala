package org.llm4s.ragbox.ingestion

import org.llm4s.rag.loader.LoadResult
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

class PathPolicySpec extends AnyFlatSpec with Matchers {

  /** A temp layout: `root/docs/a.md`, and `outside/secret.md` next to `root`. */
  private def layout(): (Path, Path, Path) = {
    val base = Files.createTempDirectory("ragbox-pathpolicy")
    val root = Files.createDirectories(base.resolve("root"))
    val docs = Files.createDirectories(root.resolve("docs"))
    Files.writeString(docs.resolve("a.md"), "inside")
    val outside = Files.createDirectories(base.resolve("outside"))
    Files.writeString(outside.resolve("secret.md"), "secret")
    (root, docs, outside)
  }

  "PathPolicy" should "allow a directory under an allowed root, and the root itself" in {
    val (root, docs, _) = layout()
    val policy = new PathPolicy(Seq(root.toString))
    policy.checkDirectory(docs.toString).map(_.real) shouldBe Right(docs.toRealPath())
    policy.checkDirectory(root.toString).map(_.real) shouldBe Right(root.toRealPath())
  }

  it should "refuse a directory outside the allowed roots" in {
    val (root, _, outside) = layout()
    val policy = new PathPolicy(Seq(root.toString))
    policy.checkDirectory(outside.toString) shouldBe Left("Path is outside the allowed ingestion roots")
    policy.checkDirectory("/etc") shouldBe Left("Path is outside the allowed ingestion roots")
  }

  it should "refuse '..' segments, even when they would stay inside the root" in {
    val (root, docs, _) = layout()
    val policy = new PathPolicy(Seq(root.toString))
    policy.checkDirectory(s"$root/../outside") shouldBe Left("Path must not contain '..'")
    policy.checkDirectory(s"$docs/..") shouldBe Left("Path must not contain '..'")
  }

  it should "refuse a symlink inside the root that points outside it" in {
    val (root, _, outside) = layout()
    val link = Files.createSymbolicLink(root.resolve("escape"), outside)
    val policy = new PathPolicy(Seq(root.toString))
    policy.checkDirectory(link.toString) shouldBe Left("Path is outside the allowed ingestion roots")
  }

  it should "not tell a missing path outside the roots from an existing one" in {
    val (root, _, _) = layout()
    val policy = new PathPolicy(Seq(root.toString))
    policy.checkDirectory("/no/such/dir") shouldBe Left("Path is outside the allowed ingestion roots")
    policy.checkDirectory(root.resolve("missing").toString) shouldBe Left("Directory not found or not readable")
  }

  it should "not treat a sibling with the root as a name prefix as inside it" in {
    val (root, _, _) = layout()
    val sibling = Files.createDirectories(root.resolveSibling(root.getFileName.toString + "2"))
    new PathPolicy(Seq(root.toString)).checkDirectory(sibling.toString) shouldBe
      Left("Path is outside the allowed ingestion roots")
  }

  it should "disable directory ingestion when no roots are configured" in {
    val (_, docs, _) = layout()
    val result = new PathPolicy(Seq.empty).checkDirectory(docs.toString)
    result.left.toOption.get should include("Directory ingestion is disabled")
  }

  it should "refuse a file, a blank path and a NUL byte" in {
    val (root, docs, _) = layout()
    val policy = new PathPolicy(Seq(root.toString))
    policy.checkDirectory(docs.resolve("a.md").toString) shouldBe Left("Path is not a directory")
    policy.checkDirectory(" ") shouldBe Left("Path is required")
    policy.checkDirectory(s"$docs\u0000x") shouldBe Left("Path contains invalid characters")
  }

  "PathPolicy.checkPatterns" should "turn patterns into extensions" in {
    PathPolicy.checkPatterns(Set("*.md", ".TXT", "pdf")) shouldBe Right(Set("md", "txt", "pdf"))
  }

  it should "refuse patterns with path separators, '..' or absolute paths" in {
    Seq("../*.md", "/etc/passwd", "sub/*.md", "..\\x.md", "*.md/..", "*", "", "*.").foreach { p =>
      withClue(p) { PathPolicy.checkPatterns(Set(p)).isLeft shouldBe true }
    }
    PathPolicy.checkPatterns(Set.empty).isLeft shouldBe true
  }

  "ConfinedDirectoryLoader" should "load files under the directory but not follow symlinks out of it" in {
    val (root, docs, outside) = layout()
    Files.createSymbolicLink(docs.resolve("escape-dir"), outside)
    Files.createSymbolicLink(docs.resolve("escape-file.md"), outside.resolve("secret.md"))
    val sub = Files.createDirectories(docs.resolve("sub"))
    Files.writeString(sub.resolve("b.md"), "nested")
    Files.createSymbolicLink(docs.resolve("inside-link.md"), sub.resolve("b.md"))
    Files.createSymbolicLink(sub.resolve("loop"), docs) // a cycle inside the root

    val allowed = new PathPolicy(Seq(root.toString)).checkDirectory(docs.toString).toOption.get
    val loader = ConfinedDirectoryLoader(allowed.path, allowed.real, Set("md"))
    val contents = loader.load().collect { case LoadResult.Success(doc) => doc.content }.toList

    contents should contain allOf ("inside", "nested")
    contents should not contain "secret"
  }

  it should "honour recursive = false" in {
    val (root, docs, _) = layout()
    val sub = Files.createDirectories(docs.resolve("sub"))
    Files.writeString(sub.resolve("b.md"), "nested")
    val allowed = new PathPolicy(Seq(root.toString)).checkDirectory(docs.toString).toOption.get
    val loader = ConfinedDirectoryLoader(allowed.path, allowed.real, Set("md"), recursive = false)
    loader.load().collect { case LoadResult.Success(doc) => doc.content }.toList shouldBe List("inside")
  }
}

package org.llm4s.ragbox.routes

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/**
 * Keeps `admin-ui/public/openapi.yaml` in step with the HTTP routes.
 *
 * The routes are read from the http4s DSL patterns in the Scala sources in `src/main/scala/.../routes`
 * (`case GET -> Root / "api" / "v1" / "documents" / id`), so a new route is picked up without
 * anyone having to register it anywhere. Every route must be documented in the spec, and every
 * operation in the spec must exist as a route.
 *
 * Path variables are compared by position only (`/documents/{id}` matches `/documents/{docId}`).
 */
class OpenApiCoverageSpec extends AnyFlatSpec with Matchers {

  private val routesDir = Paths.get("src/main/scala/org/llm4s/ragbox/routes")
  private val specFile = Paths.get("admin-ui/public/openapi.yaml")

  /** Routes the spec does not describe: the admin UI's static files. */
  private def undocumented(path: String): Boolean = path == "/admin" || path.startsWith("/admin/")

  /**
   * Lines that declare a route with the `/:` extractor, which the `Root / ...` pattern below
   * cannot read, mapped to the route they serve (which must also be in the spec).
   */
  private val extractorRoutes: Map[String, Option[(String, String)]] = Map(
    // GET /api/v1/collections/{path...}: a collection path with slashes
    """case GET -> "api" /: "v1" /: "collections" /: rest as principal""" -> Some("get" -> "/api/v1/collections/{}"),
    // GET /admin/{file}: admin UI assets
    """case req @ GET -> "admin" /: path =>""" -> None
  )

  private val Method = "(GET|POST|PUT|DELETE|PATCH|HEAD)"
  private val Identifier = "[A-Za-z_][A-Za-z0-9_]*"
  // A segment: "literal", a variable, or an extractor such as IntVar(id)
  private val Segment = "\\s*/\\s*(\"[^\"]*\"|" + Identifier + "(?:\\(" + Identifier + "\\))?)"
  private val RoutePattern = (Method + "\\s*->\\s*Root((?:" + Segment + ")+)").r
  private val SegmentPattern = Segment.r

  private def toPath(segments: String): String =
    SegmentPattern.findAllMatchIn(segments).map(_.group(1)).map { s =>
      if (s.startsWith("\"")) s.stripPrefix("\"").stripSuffix("\"") else "{}"
    }.mkString("/", "/", "").stripSuffix("/") match {
      case "" => "/"
      case p => if (segments.trim.endsWith("\"\"")) p + "/" else p
    }

  private def routeLines: Seq[(Path, String)] =
    Files.list(routesDir).iterator().asScala.toSeq.filter(_.toString.endsWith(".scala")).sorted.flatMap { file =>
      Files.readAllLines(file).asScala.toSeq.map(_.trim).filter(_.startsWith("case ")).map(file -> _)
    }

  private lazy val routes: Set[(String, String)] = {
    val declared = routeLines.filter { case (_, line) =>
      line.contains("-> Root") || line.contains("/:")
    }
    declared.flatMap { case (file, line) =>
      RoutePattern.findFirstMatchIn(line) match {
        case Some(m) => Some(m.group(1).toLowerCase -> toPath(m.group(2)))
        case None =>
          extractorRoutes.getOrElse(
            line,
            fail(s"Cannot read the route declared in ${file.getFileName}: '$line'. " +
              "Use the `Root / ...` form or add the line to OpenApiCoverageSpec.extractorRoutes.")
          )
      }
    }.filterNot { case (_, path) => undocumented(path) }.toSet
  }

  private lazy val specOperations: Set[(String, String)] = {
    val PathLine = """^  (/\S*):\s*$""".r
    val MethodLine = """^    (get|post|put|delete|patch|head):\s*$""".r
    var current: Option[String] = None
    Files.readAllLines(specFile).asScala.toSeq.flatMap {
      case PathLine(p) =>
        current = Some(p.replaceAll("\\{[^}]*\\}", "{}")); None
      case MethodLine(m) => current.map(m -> _)
      case l if l.nonEmpty && !l.startsWith(" ") && !l.startsWith("#") =>
        current = None; None
      case _ => None
    }.toSet
  }

  private def show(ops: Set[(String, String)]): String =
    ops.toSeq.sortBy(_.swap).map { case (m, p) => s"${m.toUpperCase} $p" }.mkString("\n  ", "\n  ", "")

  "the route scan" should "find the application's routes" in {
    // Guards against the scan silently matching nothing after a refactor
    routes.size should be > 90
    routes should contain("get" -> "/health")
    routes should contain("post" -> "/api/v1/query")
    routes should contain("get" -> "/api/v1/documents/{}")
  }

  "openapi.yaml" should "document every route" in {
    val missing = routes -- specOperations
    assert(missing.isEmpty, s"Routes missing from $specFile:${show(missing)}")
  }

  it should "only document routes that exist" in {
    val extra = specOperations -- routes
    assert(extra.isEmpty, s"Operations in $specFile with no matching route:${show(extra)}")
  }
}

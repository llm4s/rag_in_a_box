package org.llm4s.ragbox.routes

import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.headers._
import org.http4s.MediaType
import fs2.io.file.{Files, Path}

/**
 * HTTP routes for serving static files (Admin UI).
 *
 * Serves the Vue.js admin UI from resources/public/admin/.
 * Includes SPA fallback routing - serves index.html for unmatched paths.
 */
object StaticRoutes {

  private val resourceBasePath = "public/admin"

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // GET /admin - Serve admin UI index
    case GET -> Root / "admin" =>
      serveResource(s"$resourceBasePath/index.html", "text/html")

    // GET /admin/ - Serve admin UI index
    case GET -> Root / "admin" / "" =>
      serveResource(s"$resourceBasePath/index.html", "text/html")

    // GET /admin/* - Serve static assets or fallback to index.html for SPA routing
    case req @ GET -> "admin" /: path =>
      val segments = path.segments.map(_.decoded()).mkString("/")
      val resourcePath = s"$resourceBasePath/$segments"

      // Try to serve the requested file
      StaticFile.fromResource[IO](resourcePath, Some(req))
        .getOrElseF {
          // For SPA routing: if file not found, serve index.html
          // This allows client-side routing to work
          serveResource(s"$resourceBasePath/index.html", "text/html")
        }
  }

  /**
   * Serve a resource file with the specified content type.
   */
  private def serveResource(path: String, contentType: String): IO[Response[IO]] = {
    val ct = contentType match {
      case "text/html" => `Content-Type`(MediaType.text.html)
      case "text/css" => `Content-Type`(MediaType.text.css)
      case "application/javascript" => `Content-Type`(MediaType.application.javascript)
      case "application/json" => `Content-Type`(MediaType.application.json)
      case _ => `Content-Type`(MediaType.application.`octet-stream`)
    }

    StaticFile.fromResource[IO](path)
      .map(_.withHeaders(ct))
      .getOrElseF(NotFound(s"Resource not found: $path"))
  }
}

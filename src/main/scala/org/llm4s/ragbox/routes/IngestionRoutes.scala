package org.llm4s.ragbox.routes

import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.llm4s.ragbox.auth.Principal
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.llm4s.ragbox.ingestion.{IngestionAlreadyRunning, IngestionRequestRejected, IngestionService, DirectorySourceConfig, UrlSourceConfig, DatabaseSourceConfig, WebCrawlerSourceConfig, S3SourceConfig}
import org.llm4s.ragbox.model._

/**
 * HTTP routes for document ingestion.
 *
 * The one-shot directory and URL endpoints are checked against the ingestion policy (allowed
 * roots, URL rules; see [[org.llm4s.ragbox.ingestion.PathPolicy]] and
 * [[org.llm4s.ragbox.ingestion.UrlPolicy]]); a refused request gets 400 Bad Request.
 *
 * Every route here is an administrator operation (it reads server files, fetches URLs, or runs
 * configured sources), so non-admin principals get 403 (#13).
 */
object IngestionRoutes {

  // Every ingestion endpoint is admin-only: they read server files, fetch URLs or run the configured sources.
  def routes(ingestionService: IngestionService): AuthedRoutes[Principal, IO] = Authz.adminOnlyWhen(Authz.under("/api/v1/ingest"))(AuthedRoutes.of[Principal, IO] {

    // POST /api/v1/ingest/directory - One-shot directory ingestion
    case (req @ POST -> Root / "api" / "v1" / "ingest" / "directory") as _ =>
      req.as[DirectoryIngestRequest].flatMap { body =>
        ingestionService.ingestDirectory(
          path = body.path,
          patterns = body.patterns.map(_.toSet).getOrElse(Set("*.md", "*.txt", "*.pdf")),
          recursive = body.recursive.getOrElse(true),
          metadata = body.metadata.getOrElse(Map.empty)
        ).flatMap(resultResponse).recoverWith(rejected)
      }

    // POST /api/v1/ingest/url - One-shot URL ingestion
    case (req @ POST -> Root / "api" / "v1" / "ingest" / "url") as _ =>
      req.as[UrlIngestRequest].flatMap { body =>
        ingestionService.ingestUrls(urls = body.urls, metadata = body.metadata.getOrElse(Map.empty))
          .flatMap(resultResponse).recoverWith(rejected)
      }

    // POST /api/v1/ingest/run - Run all configured sources
    case POST -> Root / "api" / "v1" / "ingest" / "run" as _ =>
      ingestionService.runAll().flatMap(results => Ok(results.map(toIngestResponse).asJson)).recoverWith(rejected)

    // POST /api/v1/ingest/run/:source - Run specific source
    case POST -> Root / "api" / "v1" / "ingest" / "run" / sourceName as _ =>
      ingestionService.runSource(sourceName).flatMap {
        case Some(result) => resultResponse(result)
        case None => NotFound(ErrorResponse.notFound(s"Source '$sourceName' not found").asJson)
      }.recoverWith(rejected)

    // GET /api/v1/ingest/status - Get ingestion status
    case GET -> Root / "api" / "v1" / "ingest" / "status" as principal =>
      for {
        status <- ingestionService.getStatus
        sources <- ingestionService.listSources
        response <- Ok(IngestionStatusResponse(
          running = status.running,
          lastRun = status.lastRun,
          lastResults = status.lastResults.map(toIngestResponse),
          nextScheduledRun = status.nextScheduledRun,
          sources = sources.map(toSourceInfo)
        ).asJson)
      } yield response

    // GET /api/v1/ingest/sources - List configured sources
    case GET -> Root / "api" / "v1" / "ingest" / "sources" as principal =>
      for {
        sources <- ingestionService.listSources
        response <- Ok(sources.map(toSourceInfo).asJson)
      } yield response
  })

  /** 200 with the result, or 500 with it when the run failed as a whole. */
  private def resultResponse(result: org.llm4s.ragbox.ingestion.IngestionResult): IO[Response[IO]] = {
    val resp = toIngestResponse(result)
    if (resp.error.isEmpty) Ok(resp.asJson) else InternalServerError(resp.asJson)
  }

  /** A request the ingestion policy refuses (400), or a run while one is in progress (409). */
  private val rejected: PartialFunction[Throwable, IO[Response[IO]]] = {
    case IngestionRequestRejected(reason) => BadRequest(ErrorResponse.badRequest(reason).asJson)
    case _: IngestionAlreadyRunning => Conflict(ErrorResponse("conflict", IngestionAlreadyRunning.Message).asJson)
  }

  private def toIngestResponse(result: org.llm4s.ragbox.ingestion.IngestionResult): IngestResponse =
    IngestResponse(
      sourceName = result.sourceName,
      sourceType = result.sourceType,
      documentsAdded = result.documentsAdded,
      documentsUpdated = result.documentsUpdated,
      documentsDeleted = result.documentsDeleted,
      documentsUnchanged = result.documentsUnchanged,
      documentsFailed = result.documentsFailed,
      durationMs = result.duration,
      error = result.error
    )

  private def toSourceInfo(source: org.llm4s.ragbox.ingestion.SourceConfig): SourceInfo = source match {
    case dir: DirectorySourceConfig =>
      SourceInfo(
        name = dir.name,
        sourceType = "directory",
        enabled = dir.enabled,
        config = Map(
          "path" -> dir.path,
          "patterns" -> dir.patterns.mkString(","),
          "recursive" -> dir.recursive.toString
        )
      )
    case url: UrlSourceConfig =>
      SourceInfo(
        name = url.name,
        sourceType = "url",
        enabled = url.enabled,
        config = Map(
          "urls" -> url.urls.mkString(",")
        )
      )
    case db: DatabaseSourceConfig =>
      SourceInfo(
        name = db.name,
        sourceType = "database",
        enabled = db.enabled,
        config = Map(
          "url" -> db.url.replaceAll(":[^:@]+@", ":****@"), // Mask password in URL
          "query" -> (db.query.take(50) + (if (db.query.length > 50) "..." else ""))
        )
      )
    case web: WebCrawlerSourceConfig =>
      SourceInfo(
        name = web.name,
        sourceType = "web",
        enabled = web.enabled,
        config = Map(
          "seed-urls" -> web.seedUrls.mkString(","),
          "max-depth" -> web.maxDepth.toString,
          "max-pages" -> web.maxPages.toString
        )
      )
    case s3: S3SourceConfig =>
      SourceInfo(
        name = s3.name,
        sourceType = "s3",
        enabled = s3.enabled,
        config = Map(
          "bucket" -> s3.bucket,
          "prefix" -> s3.prefix,
          "region" -> s3.region,
          "patterns" -> s3.patterns.mkString(",")
        )
      )
  }
}

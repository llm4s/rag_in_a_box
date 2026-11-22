package org.llm4s.ragbox.routes

import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.llm4s.ragbox.ingestion.{IngestionAlreadyRunning, IngestionRequestRejected, IngestionService, DirectorySourceConfig, UrlSourceConfig, DatabaseSourceConfig, WebCrawlerSourceConfig, S3SourceConfig}
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.model.Codecs._

/**
 * HTTP routes for document ingestion.
 *
 * The one-shot directory and URL endpoints are checked against the ingestion policy (allowed
 * roots, URL rules; see [[org.llm4s.ragbox.ingestion.PathPolicy]] and
 * [[org.llm4s.ragbox.ingestion.UrlPolicy]]); a refused request gets 400 Bad Request.
 *
 * TODO(#13): every route here is an admin operation (it reads server files, fetches URLs, or
 * runs configured sources) and must require the admin role once the authorization rework in
 * #12/#13 lands. Until then they are only as protected as the API key / auth mode in front of
 * them. Wrap [[routes]] with the admin check at that point, e.g. `adminOnly(routes(...))`.
 */
object IngestionRoutes {

  def routes(ingestionService: IngestionService): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // POST /api/v1/ingest/directory - One-shot directory ingestion
    case req @ POST -> Root / "api" / "v1" / "ingest" / "directory" =>
      for {
        body <- req.as[DirectoryIngestRequest]
        result <- ingestionService.ingestDirectory(
          path = body.path,
          patterns = body.patterns.map(_.toSet).getOrElse(Set("*.md", "*.txt", "*.pdf")),
          recursive = body.recursive.getOrElse(true),
          metadata = body.metadata.getOrElse(Map.empty)
        ).attempt
        response <- result match {
          case Right(ingestionResult) =>
            val resp = toIngestResponse(ingestionResult)
            if (resp.error.isEmpty) Ok(resp.asJson) else InternalServerError(resp.asJson)
          case Left(IngestionRequestRejected(reason)) =>
            BadRequest(ErrorResponse.badRequest(reason).asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to ingest directory",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // POST /api/v1/ingest/url - One-shot URL ingestion
    case req @ POST -> Root / "api" / "v1" / "ingest" / "url" =>
      for {
        body <- req.as[UrlIngestRequest]
        result <- ingestionService.ingestUrls(
          urls = body.urls,
          metadata = body.metadata.getOrElse(Map.empty)
        ).attempt
        response <- result match {
          case Right(ingestionResult) =>
            val resp = toIngestResponse(ingestionResult)
            if (resp.error.isEmpty) Ok(resp.asJson) else InternalServerError(resp.asJson)
          case Left(IngestionRequestRejected(reason)) =>
            BadRequest(ErrorResponse.badRequest(reason).asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to ingest URLs",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // POST /api/v1/ingest/run - Run all configured sources
    case POST -> Root / "api" / "v1" / "ingest" / "run" =>
      for {
        results <- ingestionService.runAll().attempt
        response <- results match {
          case Right(ingestionResults) =>
            Ok(ingestionResults.map(toIngestResponse).asJson)
          case Left(e: IngestionAlreadyRunning) =>
            Conflict(ErrorResponse("conflict", e.getMessage).asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to run ingestion",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // POST /api/v1/ingest/run/:source - Run specific source
    case POST -> Root / "api" / "v1" / "ingest" / "run" / sourceName =>
      for {
        result <- ingestionService.runSource(sourceName).attempt
        response <- result match {
          case Right(Some(ingestionResult)) =>
            val resp = toIngestResponse(ingestionResult)
            if (resp.error.isEmpty) Ok(resp.asJson) else InternalServerError(resp.asJson)
          case Right(None) =>
            NotFound(ErrorResponse.notFound(s"Source '$sourceName' not found").asJson)
          case Left(e: IngestionAlreadyRunning) =>
            Conflict(ErrorResponse("conflict", e.getMessage).asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              s"Failed to run source '$sourceName'",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // GET /api/v1/ingest/status - Get ingestion status
    case GET -> Root / "api" / "v1" / "ingest" / "status" =>
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
    case GET -> Root / "api" / "v1" / "ingest" / "sources" =>
      for {
        sources <- ingestionService.listSources
        response <- Ok(sources.map(toSourceInfo).asJson)
      } yield response
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

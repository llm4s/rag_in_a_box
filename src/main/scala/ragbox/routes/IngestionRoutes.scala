package ragbox.routes

import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import ragbox.ingestion.{IngestionService, DirectorySourceConfig, UrlSourceConfig, DatabaseSourceConfig}
import ragbox.model._
import ragbox.model.Codecs._

/**
 * HTTP routes for document ingestion.
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

  private def toIngestResponse(result: ragbox.ingestion.IngestionResult): IngestResponse =
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

  private def toSourceInfo(source: ragbox.ingestion.SourceConfig): SourceInfo = source match {
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
  }
}

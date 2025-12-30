package ragbox.routes

import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.dsl.io._
import ragbox.model._
import ragbox.model.Codecs._
import ragbox.service.ChunkingService

/**
 * HTTP routes for chunking preview and comparison.
 *
 * These endpoints allow users to test chunking strategies without
 * persisting any data, making it easy to tune RAG configuration.
 */
object ChunkingRoutes {

  def routes(chunkingService: ChunkingService): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // POST /api/v1/chunking/preview - Preview chunking on sample content
    case req @ POST -> Root / "api" / "v1" / "chunking" / "preview" =>
      for {
        request <- req.as[ChunkingPreviewRequest]
        result <- chunkingService.preview(request)
        response <- Ok(result.asJson)
      } yield response

    // POST /api/v1/chunking/compare - Compare multiple strategies
    case req @ POST -> Root / "api" / "v1" / "chunking" / "compare" =>
      for {
        request <- req.as[ChunkingCompareRequest]
        result <- chunkingService.compare(request)
        response <- Ok(result.asJson)
      } yield response

    // GET /api/v1/chunking/strategies - List available strategies
    case GET -> Root / "api" / "v1" / "chunking" / "strategies" =>
      for {
        result <- chunkingService.getStrategies
        response <- Ok(result.asJson)
      } yield response

    // GET /api/v1/chunking/presets - Get preset configurations
    case GET -> Root / "api" / "v1" / "chunking" / "presets" =>
      for {
        result <- chunkingService.getPresets
        response <- Ok(result.asJson)
      } yield response
  }
}

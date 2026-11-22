package org.llm4s.ragbox.routes

import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.llm4s.ragbox.auth.Principal
import org.http4s.circe._
import org.http4s.dsl.io._
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.service.RAGService

/**
 * HTTP routes for visibility into RAG configuration and behavior.
 *
 * These endpoints allow users to understand how their RAG system is configured
 * and how documents have been chunked.
 */
object VisibilityRoutes {

  // Query parameter extractors
  object PageParam extends OptionalQueryParamDecoderMatcher[Int]("page")
  object PageSizeParam extends OptionalQueryParamDecoderMatcher[Int]("pageSize")

  // Visibility exposes raw chunk text of every document regardless of permissions: admin-only.
  def routes(ragService: RAGService): AuthedRoutes[Principal, IO] = Authz.adminOnlyWhen(Authz.under("/api/v1/visibility"))(AuthedRoutes.of[Principal, IO] {

    // GET /api/v1/visibility/config - Get detailed configuration with changeability annotations
    case GET -> Root / "api" / "v1" / "visibility" / "config" as principal =>
      for {
        config <- ragService.getDetailedConfig
        response <- Ok(config.asJson)
      } yield response

    // GET /api/v1/visibility/chunks - List all chunks (paginated)
    case GET -> Root / "api" / "v1" / "visibility" / "chunks"
        :? PageParam(page) +& PageSizeParam(pageSize) as principal =>
      for {
        result <- ragService.listChunks(page.getOrElse(1), pageSize.getOrElse(50))
        response <- Ok(result.asJson)
      } yield response

    // GET /api/v1/visibility/chunks/{docId} - Get all chunks for a document
    case GET -> Root / "api" / "v1" / "visibility" / "chunks" / docId as principal =>
      for {
        result <- ragService.getDocumentChunks(docId)
        response <- result match {
          case Some(doc) => Ok(doc.asJson)
          case None => NotFound(ErrorResponse.notFound(s"Document $docId not found").asJson)
        }
      } yield response

    // GET /api/v1/visibility/chunks/{docId}/{idx} - Get a specific chunk
    case GET -> Root / "api" / "v1" / "visibility" / "chunks" / docId / IntVar(idx) as principal =>
      for {
        result <- ragService.getChunk(docId, idx)
        response <- result match {
          case Some(chunk) => Ok(chunk.asJson)
          case None => NotFound(ErrorResponse.notFound(s"Chunk $docId[$idx] not found").asJson)
        }
      } yield response

    // GET /api/v1/visibility/stats - Get detailed statistics
    case GET -> Root / "api" / "v1" / "visibility" / "stats" as principal =>
      for {
        stats <- ragService.getDetailedStats
        response <- Ok(stats.asJson)
      } yield response

    // GET /api/v1/visibility/collections - Get collection details with chunking rules
    case GET -> Root / "api" / "v1" / "visibility" / "collections" as principal =>
      for {
        collections <- ragService.getCollectionDetails
        response <- Ok(collections.asJson)
      } yield response
  })
}

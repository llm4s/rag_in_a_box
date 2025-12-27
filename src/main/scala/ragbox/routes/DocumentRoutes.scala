package ragbox.routes

import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.dsl.impl.OptionalQueryParamDecoderMatcher
import ragbox.model._
import ragbox.model.Codecs._
import ragbox.service.RAGService

import java.util.UUID

/**
 * HTTP routes for document management.
 */
object DocumentRoutes {

  // Query param matchers
  object CollectionParam extends OptionalQueryParamDecoderMatcher[String]("collection")

  def routes(ragService: RAGService): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // GET /api/v1/collections - List all collections
    case GET -> Root / "api" / "v1" / "collections" =>
      for {
        collections <- ragService.listCollections.attempt
        response <- collections match {
          case Right(cols) =>
            Ok(Map("collections" -> cols).asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to list collections",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // POST /api/v1/documents - Upload document
    case req @ POST -> Root / "api" / "v1" / "documents" =>
      for {
        body <- req.as[DocumentUploadRequest]
        documentId = UUID.randomUUID().toString
        result <- ragService.ingestDocument(
          content = body.content,
          documentId = documentId,
          metadata = body.metadata.getOrElse(Map.empty) +
            ("filename" -> body.filename) ++
            body.collection.map("collection" -> _).toMap
        ).attempt
        response <- result match {
          case Right(uploadResponse) =>
            Created(uploadResponse.asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to ingest document",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // GET /api/v1/documents - List documents
    case GET -> Root / "api" / "v1" / "documents" =>
      for {
        stats <- ragService.getStats.attempt
        response <- stats match {
          case Right(s) =>
            // Note: llm4s RAG doesn't expose document listing directly
            // Return stats-based info for now
            Ok(DocumentListResponse(
              documents = Seq.empty, // Would need document registry
              total = s.documentCount
            ).asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to list documents",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // PUT /api/v1/documents/:id - Upsert document (idempotent)
    case req @ PUT -> Root / "api" / "v1" / "documents" / id =>
      for {
        body <- req.as[DocumentUpsertRequest]
        result <- ragService.upsertDocument(
          documentId = id,
          content = body.content,
          metadata = body.metadata.getOrElse(Map.empty),
          providedHash = body.contentHash
        ).attempt
        response <- result match {
          case Right(upsertResponse) =>
            upsertResponse.action match {
              case "created" => Created(upsertResponse.asJson)
              case "updated" => Ok(upsertResponse.asJson)
              case "unchanged" => Ok(upsertResponse.asJson)
              case _ => Ok(upsertResponse.asJson)
            }
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              s"Failed to upsert document $id",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // GET /api/v1/documents/:id - Get document details
    case GET -> Root / "api" / "v1" / "documents" / id =>
      // Note: llm4s RAG doesn't expose individual document retrieval
      // This would need document registry integration
      NotFound(ErrorResponse.notFound(s"Document $id not found").asJson)

    // DELETE /api/v1/documents/:id - Delete document
    case DELETE -> Root / "api" / "v1" / "documents" / id =>
      for {
        result <- ragService.deleteDocument(id).attempt
        response <- result match {
          case Right(_) =>
            NoContent()
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              s"Failed to delete document $id",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // GET /api/v1/sync/status - Get sync status
    case GET -> Root / "api" / "v1" / "sync" / "status" =>
      for {
        status <- ragService.getSyncStatus.attempt
        response <- status match {
          case Right(s) => Ok(s.asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to get sync status",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // POST /api/v1/sync - Mark sync complete and optionally prune
    case req @ POST -> Root / "api" / "v1" / "sync" =>
      for {
        body <- req.attemptAs[SyncPruneRequest].value
        result <- body match {
          case Right(pruneReq) =>
            // Prune documents not in keep list, then mark sync complete
            for {
              deleted <- ragService.pruneDocuments(pruneReq.keepDocumentIds.toSet)
              _ <- ragService.markSyncComplete()
            } yield Map(
              "message" -> "Sync completed",
              "prunedCount" -> deleted.toString
            )
          case Left(_) =>
            // No body - just mark sync complete
            ragService.markSyncComplete().as(Map(
              "message" -> "Sync completed",
              "prunedCount" -> "0"
            ))
        }
        response <- Ok(result.asJson)
      } yield response

    // GET /api/v1/sync/documents - List registered document IDs
    case GET -> Root / "api" / "v1" / "sync" / "documents" =>
      for {
        docIds <- ragService.listDocumentIds
        response <- Ok(Map("documentIds" -> docIds).asJson)
      } yield response

    // DELETE /api/v1/documents - Clear all documents
    case DELETE -> Root / "api" / "v1" / "documents" =>
      for {
        result <- ragService.clearAll.attempt
        response <- result match {
          case Right(_) =>
            Ok(Map("message" -> "All documents cleared").asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to clear documents",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // GET /api/v1/stats - Get statistics
    case GET -> Root / "api" / "v1" / "stats" =>
      for {
        stats <- ragService.getStats.attempt
        response <- stats match {
          case Right(s) => Ok(s.asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to get stats",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response
  }
}

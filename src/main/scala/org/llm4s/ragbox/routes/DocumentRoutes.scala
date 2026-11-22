package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.syntax.all._
import io.circe.Decoder
import io.circe.syntax._
import org.http4s._
import org.llm4s.ragbox.auth.{AuthMethod, PermissionContext, Principal, TokenScope}
import org.llm4s.ragbox.registry.DocumentEntry
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.dsl.impl.{OptionalQueryParamDecoderMatcher, OptionalValidatingQueryParamDecoderMatcher}
import org.http4s.QueryParamDecoder
import org.http4s.multipart.Multipart
import java.time.Instant
import java.time.format.DateTimeParseException
import org.llm4s.rag.permissions._
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.model.Codecs._
import org.llm4s.ragbox.registry.DocumentEntry
import org.llm4s.ragbox.service.{InvalidRequestError, RAGService}
import org.llm4s.ragbox.validation.InputValidation

import java.util.UUID

/**
 * HTTP routes for document management.
 *
 * Supports both legacy (no permissions) and permission-aware document ingestion.
 * When PrincipalStore is provided, documents can specify readableBy principals.
 */
object DocumentRoutes {

  // Query param matchers
  object CollectionParam extends OptionalQueryParamDecoderMatcher[String]("collection")
  object SearchParam extends OptionalQueryParamDecoderMatcher[String]("search")
  object PageParam extends OptionalQueryParamDecoderMatcher[Int]("page")
  object PageSizeParam extends OptionalQueryParamDecoderMatcher[Int]("pageSize")

  /** Default and maximum page sizes for GET /api/v1/documents. */
  val DefaultPageSize: Int = 50
  val MaxPageSize: Int = 500

  private[routes] def toDocumentInfo(entry: DocumentEntry): DocumentInfo =
    DocumentInfo(
      id = entry.documentId,
      filename = entry.metadata.getOrElse("filename", entry.documentId),
      chunkCount = entry.chunkCount,
      metadata = entry.metadata,
      createdAt = Some(entry.indexedAt),
      collection = entry.collection.orElse(entry.metadata.get("collection")),
      updatedAt = Some(entry.updatedAt)
    )

  /**
   * Filter, sort (most recently updated first) and paginate registry entries.
   * Out-of-range page/pageSize values are clamped rather than rejected.
   *
   * Filtering happens in memory over the registry listing, which is fine for
   * the admin UI at demo scale; pushing it into SQL is a follow-up.
   */
  private[routes] def buildListResponse(
    entries: Seq[DocumentEntry],
    pageOpt: Option[Int],
    pageSizeOpt: Option[Int],
    collectionOpt: Option[String],
    searchOpt: Option[String]
  ): DocumentListResponse = {
    val pageSize = pageSizeOpt.getOrElse(DefaultPageSize).max(1).min(MaxPageSize)
    val page = pageOpt.getOrElse(1).max(1)
    val collection = collectionOpt.map(_.trim).filter(_.nonEmpty)
    val search = searchOpt.map(_.trim.toLowerCase).filter(_.nonEmpty)

    val filtered = entries
      .map(toDocumentInfo)
      .filter(d => collection.forall(c => d.collection.contains(c)))
      .filter { d =>
        search.forall { q =>
          d.id.toLowerCase.contains(q) ||
          d.filename.toLowerCase.contains(q) ||
          d.metadata.get("title").exists(_.toLowerCase.contains(q))
        }
      }
      .sortBy(d => (-d.updatedAt.orElse(d.createdAt).map(_.toEpochMilli).getOrElse(0L), d.id))

    val offset = (page - 1).toLong * pageSize
    val pageItems =
      if (offset >= filtered.size) Seq.empty
      else filtered.slice(offset.toInt, offset.toInt + pageSize)

    DocumentListResponse(
      documents = pageItems,
      total = filtered.size,
      page = page,
      pageSize = pageSize,
      hasMore = offset + pageItems.size < filtered.size
    )
  }

  // Query params for enhanced sync endpoint
  object IncludeParam extends OptionalQueryParamDecoderMatcher[String]("include")

  // Custom decoder for Instant
  implicit val instantQueryParamDecoder: QueryParamDecoder[Instant] =
    QueryParamDecoder[String].emap { str =>
      try Right(Instant.parse(str))
      catch {
        case _: DateTimeParseException =>
          Left(ParseFailure(s"Invalid timestamp format: $str", s"Expected ISO-8601 format like 2024-01-01T00:00:00Z"))
      }
    }

  object SinceParam extends OptionalQueryParamDecoderMatcher[Instant]("since")

  // Explicit opt-in for clearing every document: DELETE /api/v1/documents?all=true
  object AllParam extends OptionalValidatingQueryParamDecoderMatcher[Boolean]("all")

  /**
   * Decode an optional JSON body.
   *
   * Returns Right(None) for an empty (or whitespace-only) body, Right(Some(a)) when the
   * body decodes, and Left(error) when a body is present but cannot be decoded. This lets
   * callers distinguish "no body" from "malformed body" instead of treating any decode
   * failure as "no body".
   */
  private def decodeOptionalBody[A: Decoder](req: Request[IO]): IO[Either[String, Option[A]]] =
    req.bodyText.compile.string.map { raw =>
      if (raw.trim.isEmpty) Right(None)
      else io.circe.parser.decode[A](raw).map(Some(_)).left.map(_.getMessage)
    }

  private def invalidBody(details: String): IO[Response[IO]] =
    BadRequest(ErrorResponse.badRequest("Invalid request body", Some(details)).asJson)

  private def batchDelete(ragService: RAGService, batchReq: BatchDeleteRequest): IO[Response[IO]] =
    if (batchReq.dryRun.getOrElse(false)) {
      // Dry run - check which documents exist
      for {
        entries <- ragService.getDocumentEntries(batchReq.documentIds)
        existingIds = entries.map(_.documentId)
        missingIds = batchReq.documentIds.filterNot(existingIds.contains)
        resp <- Ok(BatchDeleteResponse(
          message = s"Dry run: would delete ${existingIds.size} documents",
          deletedCount = existingIds.size,
          deletedIds = Some(existingIds),
          failedIds = if (missingIds.nonEmpty) Some(missingIds) else None,
          dryRun = true
        ).asJson)
      } yield resp
    } else {
      // Actually delete the documents
      for {
        results <- batchReq.documentIds.toList.traverse { id =>
          ragService.deleteDocument(id).attempt.map(r => id -> r.isRight)
        }
        deleted = results.filter(_._2).map(_._1)
        failed = results.filterNot(_._2).map(_._1)
        resp <- Ok(BatchDeleteResponse(
          message = s"Deleted ${deleted.size} documents",
          deletedCount = deleted.size,
          deletedIds = Some(deleted),
          failedIds = if (failed.nonEmpty) Some(failed) else None,
          dryRun = false
        ).asJson)
      } yield resp
    }

  private def clearAllDocuments(ragService: RAGService): IO[Response[IO]] =
    ragService.clearAll.attempt.flatMap {
      case Right(_) =>
        Ok(Map("message" -> "All documents cleared").asJson)
      case Left(e) =>
        InternalServerError(ErrorResponse.internalError(
          "Failed to clear documents",
          Some(e.getMessage)
        ).asJson)
    }

  /**
   * Resolve external principal IDs to PrincipalIds.
   * External IDs are formatted as "user:alice@example.com" or "group:managers".
   */
  private def resolvePrincipals(
    externalIds: Seq[String],
    principals: PrincipalStore
  ): IO[Set[PrincipalId]] =
    externalIds.toList.traverse { extId =>
      IO.fromEither(
        ExternalPrincipal.parse(extId)
          .flatMap(principals.getOrCreate)
          .left.map(e => new RuntimeException(s"Failed to resolve principal '$extId': ${e.message}"))
      )
    }.map(_.toSet)

  /**
   * Ingest a new document under a fresh id: into its collection (or the default one) with its
   * access list in permission mode, plainly otherwise. Responds 201 with the upload response.
   */
  private def createDocument(
    ragService: RAGService,
    content: String,
    filenameOpt: Option[String],
    userMetadata: Map[String, String],
    collection: Option[String],
    readableByIds: Option[Seq[String]]
  ): IO[Response[IO]] = {
    val documentId = UUID.randomUUID().toString
    // Filename is optional (e.g. pasted text); fall back to a stable placeholder
    val filename = filenameOpt.map(_.trim).filter(_.nonEmpty)
      .getOrElse(s"untitled-${documentId.take(8)}.txt")
    val metadata = userMetadata +
      ("filename" -> filename) ++
      collection.map("collection" -> _).toMap
    val ingestIO = (ragService.hasPermissions, ragService.principals) match {
      // Permission-aware ingestion when SearchIndex is available; no collection means the default one
      case (true, Some(principals)) =>
        for {
          readableBy <- readableByIds match {
            case Some(ids) if ids.nonEmpty => resolvePrincipals(ids, principals)
            case _ => IO.pure(Set.empty[PrincipalId])
          }
          response <- ragService.ingestWithPermissions(
            collectionPath = collection.getOrElse(RAGService.DefaultCollection),
            documentId = documentId,
            content = content,
            metadata = metadata,
            readableBy = readableBy
          )
        } yield response
      // Fall back to legacy ingestion (no permissions)
      case _ =>
        ragService.ingestDocument(
          content = content,
          documentId = documentId,
          metadata = metadata
        )
    }
    ingestIO.attempt.flatMap {
      case Right(uploadResponse) =>
        Created(uploadResponse.asJson)
      case Left(e: InvalidRequestError) =>
        BadRequest(ErrorResponse.badRequest(e.message).asJson)
      case Left(e) =>
        InternalServerError(ErrorResponse.internalError(
          "Failed to ingest document",
          Some(e.getMessage)
        ).asJson)
    }
  }

  /** Collection of a registered document (documents without one live in the default collection). */
  private def collectionOf(entry: DocumentEntry): String =
    entry.collection.getOrElse(RAGService.DefaultCollection)

  /** Entries a (possibly collection-restricted) access token may see. */
  private def visibleTo(principal: Principal, entries: Seq[DocumentEntry]): Seq[DocumentEntry] =
    if (principal.isCollectionRestricted) entries.filter(e => principal.canAccessCollection(collectionOf(e)))
    else entries

  /**
   * Which collections `principal` may see and change documents in:
   *  - administrators: all
   *  - access tokens: their collection restriction
   *  - other users: the collections their permissions let them query (permission mode; without
   *    permissions every collection is open)
   */
  private def accessFilter(ragService: RAGService, principal: Principal): IO[String => Boolean] =
    if (principal.isAdmin) IO.pure(_ => true)
    else if (principal.method == AuthMethod.AccessToken) IO.pure(principal.canAccessCollection)
    else
      (ragService.collections, ragService.principals) match {
        case (Some(collections), Some(principals)) =>
          for {
            auth <- PermissionContext.authorization(principal, principals)
            accessible <- IO.blocking(collections.findAccessible(auth, CollectionPattern.All)).flatMap(r =>
              IO.fromEither(r.left.map(e => new RuntimeException(e.message))))
            paths = accessible.map(_.path.value).toSet
          } yield (c: String) => paths.contains(c)
        case _ => IO.pure(_ => true)
      }

  /** `principal` may write to `collection` (see [[accessFilter]]). */
  private def requireCollectionAccess(ragService: RAGService, principal: Principal, collection: String)(
    operation: => IO[Response[IO]]
  ): IO[Response[IO]] =
    accessFilter(ragService, principal).flatMap { allowed =>
      if (allowed(collection)) operation
      else Authz.forbidden(s"Not allowed to use collection '$collection'")
    }

  /** Run `operation` only if every one of `ids` that exists is in a collection `principal` may use. */
  private def requireDocumentsAccessible(ragService: RAGService, principal: Principal, ids: Seq[String])(
    operation: => IO[Response[IO]]
  ): IO[Response[IO]] =
    if (principal.isAdmin) operation
    else
      for {
        allowed <- accessFilter(ragService, principal)
        entries <- ragService.getDocumentEntries(ids)
        response <- entries.find(e => !allowed(collectionOf(e))) match {
          case Some(e) => Authz.forbidden(s"Not allowed to use collection '${collectionOf(e)}'")
          case None => operation
        }
      } yield response

  /**
   * Document, sync and stats endpoints.
   *
   * Access tokens need `documents:read` / `documents:write` / `sync:read` / `sync:write` as
   * appropriate, and a token restricted to collections only sees and changes documents in them.
   * Other non-admin users only see and change documents in collections they can query. The sync
   * API is for ingesters: access tokens (with the sync scopes) and administrators. Clearing
   * every document is admin-only.
   */
  def routes(ragService: RAGService): AuthedRoutes[Principal, IO] = AuthedRoutes.of[Principal, IO] {

    // GET /api/v1/collections - List all collections
    case GET -> Root / "api" / "v1" / "collections" as principal =>
      Authz.requireScope(principal, TokenScope.DocumentsRead) { for {
        collections <- ragService.listCollections.attempt
        allowed <- accessFilter(ragService, principal)
        response <- collections match {
          case Right(cols) =>
            Ok(Map("collections" -> cols.filter(allowed)).asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to list collections",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response }

    // POST /api/v1/documents - Upload document
    // Supports permission-aware ingestion when readableBy is provided and SearchIndex is available
    case (req @ POST -> Root / "api" / "v1" / "documents") as principal =>
      Authz.requireScope(principal, TokenScope.DocumentsWrite) { for {
        body <- req.as[DocumentUploadRequest]
        // Validate inputs
        validation = InputValidation.all(
          InputValidation.validateDocumentContent(body.content),
          body.filename.map(InputValidation.validateFilename(_)).getOrElse(InputValidation.Valid),
          body.metadata.map(InputValidation.validateMetadata(_)).getOrElse(InputValidation.Valid)
        )
        response <- InputValidation.toResponse(validation) match {
          case Some(errorResponse) => errorResponse
          case None =>
            requireCollectionAccess(ragService, principal, body.collection.getOrElse(RAGService.DefaultCollection)) {
              createDocument(ragService, body.content, body.filename, body.metadata.getOrElse(Map.empty), body.collection, body.readableBy)
            }
        }
      } yield response }

    // POST /api/v1/documents/upload - Upload a file (multipart/form-data)
    // Fields: file (required; PDF, DOCX, HTML, Markdown, plain text - anything Apache Tika reads),
    // collection, metadata (a JSON object of strings), readableBy (comma-separated principals).
    // The text is extracted with llm4s's TikaDocumentExtractor and ingested like POST /documents.
    case POST -> Root / "api" / "v1" / "documents" / "upload" as principal if !principal.hasScope(TokenScope.DocumentsWrite) =>
      Authz.requireScope(principal, TokenScope.DocumentsWrite)(IO.never)
    case (req @ POST -> Root / "api" / "v1" / "documents" / "upload") as principal =>
      req.as[Multipart[IO]].attempt.flatMap {
        case Left(e) =>
          BadRequest(ErrorResponse.badRequest("Expected a multipart/form-data body", Option(e.getMessage)).asJson)
        case Right(multipart) =>
          def field(name: String): IO[Option[String]] =
            multipart.parts.find(_.name.contains(name)).traverse(_.bodyText.compile.string).map(_.map(_.trim).filter(_.nonEmpty))

          multipart.parts.find(_.name.contains("file")) match {
            case None =>
              BadRequest(ErrorResponse.badRequest("A 'file' part is required").asJson)
            case Some(filePart) =>
              val filename = filePart.filename.map(_.trim).filter(_.nonEmpty).getOrElse("upload")
              for {
                bytes <- filePart.body.compile.to(Array)
                collection <- field("collection")
                metadataField <- field("metadata")
                readableBy <- field("readableBy").map(_.map(_.split(",").toSeq.map(_.trim).filter(_.nonEmpty)))
                metadata = metadataField.map(io.circe.parser.decode[Map[String, String]](_)).getOrElse(Right(Map.empty[String, String]))
                validation = InputValidation.all(
                  InputValidation.validateFilename(filename),
                  metadata.fold(_ => InputValidation.Valid, InputValidation.validateMetadata(_))
                )
                response <- (metadata, InputValidation.toResponse(validation)) match {
                  case (Left(error), _) =>
                    BadRequest(ErrorResponse.badRequest("'metadata' must be a JSON object of strings", Some(error.getMessage)).asJson)
                  case (_, Some(errorResponse)) => errorResponse
                  case (Right(_), None) if bytes.isEmpty =>
                    BadRequest(ErrorResponse.badRequest(s"'$filename' is empty").asJson)
                  case (Right(userMetadata), None) =>
                    ragService.extractText(bytes, filename).attempt.flatMap {
                      case Left(e: InvalidRequestError) =>
                        BadRequest(ErrorResponse.badRequest(e.message).asJson)
                      case Left(e) =>
                        InternalServerError(ErrorResponse.internalError("Failed to extract text", Option(e.getMessage)).asJson)
                      case Right((text, extracted)) =>
                        InputValidation.toResponse(InputValidation.validateDocumentContent(text)) match {
                          case Some(errorResponse) => errorResponse
                          case None =>
                            requireCollectionAccess(ragService, principal, collection.getOrElse(RAGService.DefaultCollection)) {
                              createDocument(ragService, text, Some(filename), extracted ++ userMetadata, collection, readableBy)
                            }
                        }
                    }
                }
              } yield response
          }
      }

    // GET /api/v1/documents - List documents (paginated, filterable)
    // Query params:
    //   - page: 1-based page number (default 1)
    //   - pageSize: items per page (default 50, max 500)
    //   - collection: exact collection match
    //   - search: case-insensitive substring of id, filename or title
    case GET -> Root / "api" / "v1" / "documents"
        :? PageParam(pageOpt) +& PageSizeParam(pageSizeOpt) +& CollectionParam(collectionOpt) +& SearchParam(searchOpt) as principal =>
      Authz.requireScope(principal, TokenScope.DocumentsRead) { for {
        entries <- ragService.listDocumentEntries.attempt
        allowed <- accessFilter(ragService, principal)
        response <- entries match {
          case Right(docs) =>
            Ok(buildListResponse(docs.filter(e => allowed(collectionOf(e))), pageOpt, pageSizeOpt, collectionOpt, searchOpt).asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to list documents",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response }

    // PUT /api/v1/documents/:id - Upsert document (idempotent)
    // Supports permission-aware ingestion when collection and readableBy are provided
    case (req @ PUT -> Root / "api" / "v1" / "documents" / id) as principal =>
      Authz.requireScope(principal, TokenScope.DocumentsWrite) { for {
        body <- req.as[DocumentUpsertRequest]
        // Validate inputs
        validation = InputValidation.all(
          InputValidation.validateDocumentId(id),
          InputValidation.validateDocumentContent(body.content),
          body.metadata.map(InputValidation.validateMetadata(_)).getOrElse(InputValidation.Valid)
        )
        response <- InputValidation.toResponse(validation) match {
          case Some(errorResponse) => errorResponse
          // The target collection must be allowed, and so must the one an existing document is in
          case None => requireCollectionAccess(ragService, principal, body.collection.getOrElse(RAGService.DefaultCollection)) {
            requireDocumentsAccessible(ragService, principal, Seq(id)) {
            val metadata = body.metadata.getOrElse(Map.empty) ++
              body.collection.map("collection" -> _).toMap
            val upsertIO = (ragService.hasPermissions, body.readableBy, body.collection, ragService.principals) match {
              // Permission-aware upsert when SearchIndex is available; no collection means the default one
              case (true, readableByOpt, collectionOpt, Some(principals)) =>
                for {
                  readableBy <- readableByOpt match {
                    case Some(ids) if ids.nonEmpty => resolvePrincipals(ids, principals)
                    case _ => IO.pure(Set.empty[PrincipalId])
                  }
                  response <- ragService.upsertWithPermissions(
                    collectionPath = collectionOpt.getOrElse(RAGService.DefaultCollection),
                    documentId = id,
                    content = body.content,
                    metadata = metadata,
                    readableBy = readableBy,
                    providedHash = body.contentHash
                  )
                } yield response
              // Fall back to legacy upsert (no permissions)
              case _ =>
                ragService.upsertDocument(
                  documentId = id,
                  content = body.content,
                  metadata = metadata,
                  providedHash = body.contentHash
                )
            }
            upsertIO.attempt.flatMap {
              case Right(upsertResponse) =>
                upsertResponse.action match {
                  case "created" => Created(upsertResponse.asJson)
                  case "updated" => Ok(upsertResponse.asJson)
                  case "unchanged" => Ok(upsertResponse.asJson)
                  case _ => Ok(upsertResponse.asJson)
                }
              case Left(e: InvalidRequestError) =>
                BadRequest(ErrorResponse.badRequest(e.message).asJson)
              case Left(e) =>
                InternalServerError(ErrorResponse.internalError(
                  s"Failed to upsert document $id",
                  Some(e.getMessage)
                ).asJson)
            }
            }
          }
        }
      } yield response }

    // GET /api/v1/documents/:id - Get document details
    // Returns registry metadata; chunk content is served by /api/v1/visibility/chunks/:id
    case GET -> Root / "api" / "v1" / "documents" / id as principal =>
      Authz.requireScope(principal, TokenScope.DocumentsRead)((ragService.getDocumentEntries(Seq(id)).attempt, accessFilter(ragService, principal)).flatMapN {
        case (Right(entries), allowed) =>
          entries.filter(e => allowed(collectionOf(e))).find(_.documentId == id) match {
            case Some(entry) => Ok(toDocumentInfo(entry).asJson)
            case None => NotFound(ErrorResponse.notFound(s"Document $id not found").asJson)
          }
        case (Left(e), _) =>
          InternalServerError(ErrorResponse.internalError(
            s"Failed to get document $id",
            Some(e.getMessage)
          ).asJson)
      })

    // DELETE /api/v1/documents/:id - Delete document
    case DELETE -> Root / "api" / "v1" / "documents" / id as principal =>
      Authz.requireScope(principal, TokenScope.DocumentsWrite) { requireDocumentsAccessible(ragService, principal, Seq(id)) { for {
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
      } yield response } }

    // GET /api/v1/sync/status - Get sync status
    case GET -> Root / "api" / "v1" / "sync" / "status" as principal =>
      Authz.requireIngester(principal, TokenScope.SyncRead) { for {
        status <- ragService.getSyncStatus.attempt
        response <- status match {
          case Right(s) => Ok(s.asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to get sync status",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response }

    // POST /api/v1/sync - Mark sync complete and optionally prune
    // Supports dryRun to preview what would be deleted
    case (req @ POST -> Root / "api" / "v1" / "sync") as principal =>
      Authz.requireIngester(principal, TokenScope.SyncWrite) { for {
        body <- decodeOptionalBody[SyncPruneRequest](req)
        response <- body match {
          case Left(err) => invalidBody(err)
          case Right(Some(pruneReq)) =>
            val isDryRun = pruneReq.dryRun.getOrElse(false)
            // A collection-restricted token only prunes within its collections: every document
            // outside them is kept
            val keepIO: IO[Set[String]] =
              if (!principal.isCollectionRestricted) IO.pure(pruneReq.keepDocumentIds.toSet)
              else ragService.listDocumentEntries.map { all =>
                pruneReq.keepDocumentIds.toSet ++
                  all.filterNot(e => principal.canAccessCollection(collectionOf(e))).map(_.documentId)
              }
            val result = if (isDryRun) {
              // Dry run - just find orphans without deleting
              keepIO.flatMap(ragService.findOrphanedDocuments).map { orphans =>
                SyncPruneResponse(
                  message = s"Dry run: would delete ${orphans.size} documents",
                  prunedCount = orphans.size,
                  prunedIds = Some(orphans),
                  dryRun = true
                )
              }
            } else {
              // Actual prune - delete orphans and mark sync complete
              for {
                keep <- keepIO
                deleted <- ragService.pruneDocuments(keep)
                _ <- ragService.markSyncComplete()
              } yield SyncPruneResponse(
                message = "Sync completed",
                prunedCount = deleted,
                prunedIds = None,
                dryRun = false
              )
            }
            result.flatMap(r => Ok(r.asJson))
          case Right(None) =>
            // No body - just mark sync complete
            ragService.markSyncComplete().as(SyncPruneResponse(
              message = "Sync completed",
              prunedCount = 0,
              prunedIds = None,
              dryRun = false
            )).flatMap(r => Ok(r.asJson))
        }
      } yield response }

    // GET /api/v1/sync/documents - List registered document states
    // Query params:
    //   - include=hash,updatedAt - Include additional fields
    //   - since=2024-01-01T00:00:00Z - Only documents modified since this time
    case GET -> Root / "api" / "v1" / "sync" / "documents" :? IncludeParam(includeOpt) +& SinceParam(sinceOpt) as principal =>
      Authz.requireIngester(principal, TokenScope.SyncRead) { for {
        allEntries <- sinceOpt match {
          case Some(since) => ragService.listDocumentEntriesSince(since)
          case None => ragService.listDocumentEntries
        }
        entries = visibleTo(principal, allEntries)
        includeFields = includeOpt.map(_.split(",").map(_.trim.toLowerCase).toSet).getOrElse(Set.empty)
        documents = entries.map { entry =>
          DocumentSyncState(
            id = entry.documentId,
            hash = if (includeFields.contains("hash")) Some(entry.contentHash) else None,
            updatedAt = if (includeFields.contains("updatedat")) Some(entry.updatedAt) else None,
            collection = entry.collection
          )
        }
        response <- Ok(DocumentSyncListResponse(documents = documents, total = documents.size).asJson)
      } yield response }

    // GET /api/v1/sync/documents/:id - Get single document state
    case GET -> Root / "api" / "v1" / "sync" / "documents" / id as principal =>
      Authz.requireIngester(principal, TokenScope.SyncRead) { for {
        entries <- ragService.getDocumentEntries(Seq(id))
        response <- visibleTo(principal, entries).headOption match {
          case Some(entry) =>
            Ok(DocumentSyncState(
              id = entry.documentId,
              hash = Some(entry.contentHash),
              updatedAt = Some(entry.updatedAt),
              collection = entry.collection
            ).asJson)
          case None =>
            NotFound(ErrorResponse.notFound(s"Document $id not found").asJson)
        }
      } yield response }

    // POST /api/v1/sync/check - Batch check document states
    case (req @ POST -> Root / "api" / "v1" / "sync" / "check") as principal =>
      Authz.requireIngester(principal, TokenScope.SyncRead) { for {
        body <- req.as[SyncCheckRequest]
        entries <- ragService.getDocumentEntries(body.documentIds).map(visibleTo(principal, _))
        foundIds = entries.map(_.documentId).toSet
        found = entries.map { entry =>
          DocumentSyncState(
            id = entry.documentId,
            hash = Some(entry.contentHash),
            updatedAt = Some(entry.updatedAt),
            collection = entry.collection
          )
        }
        missing = body.documentIds.filterNot(foundIds.contains)
        response <- Ok(SyncCheckResponse(found = found, missing = missing).asJson)
      } yield response }

    // DELETE /api/v1/documents - Batch delete documents or clear all
    // With a BatchDeleteRequest body: delete the listed documents
    // With ?all=true and no body: clear all documents (explicit opt-in)
    // Anything else (no body, malformed body, all=true plus a body) is a 400.
    case (req @ DELETE -> Root / "api" / "v1" / "documents" :? AllParam(allParam)) as principal =>
      for {
        bodyOpt <- decodeOptionalBody[BatchDeleteRequest](req)
        clearAll = allParam.map(_.toEither.left.map(_.head.sanitized))
        response <- (clearAll, bodyOpt) match {
          case (Some(Left(err)), _) =>
            BadRequest(ErrorResponse.badRequest("Invalid 'all' query parameter", Some(err)).asJson)
          case (_, Left(err)) =>
            invalidBody(err)
          case (Some(Right(true)), Right(Some(_))) =>
            BadRequest(ErrorResponse.badRequest(
              "Specify either ?all=true or a documentIds body, not both"
            ).asJson)
          case (Some(Right(true)), Right(None)) =>
            Authz.requireAdmin(principal)(clearAllDocuments(ragService))
          case (_, Right(None)) =>
            BadRequest(ErrorResponse.badRequest(
              "Request body with documentIds is required. To delete every document use DELETE /api/v1/documents?all=true"
            ).asJson)
          case (_, Right(Some(batchReq))) =>
            InputValidation.withValidation(InputValidation.validateBatchSize(batchReq.documentIds.size)) {
              Authz.requireScope(principal, TokenScope.DocumentsWrite) {
                requireDocumentsAccessible(ragService, principal, batchReq.documentIds)(batchDelete(ragService, batchReq))
              }
            }
        }
      } yield response

    // GET /api/v1/stats - Get statistics
    case GET -> Root / "api" / "v1" / "stats" as principal =>
      Authz.requireScope(principal, TokenScope.DocumentsRead) { for {
        stats <- ragService.getStats.attempt
        response <- stats match {
          case Right(s) => Ok(s.asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to get stats",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response }
  }
}

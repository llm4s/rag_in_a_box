package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.syntax.all._
import io.circe.Decoder
import io.circe.syntax._
import org.http4s._
import org.llm4s.ragbox.auth.{AuthMethod, PermissionContext, Principal, TokenScope}
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.dsl.impl.{OptionalQueryParamDecoderMatcher, OptionalValidatingQueryParamDecoderMatcher}
import org.http4s.multipart.Multipart
import org.llm4s.rag.permissions._
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.DocumentEntry
import org.llm4s.ragbox.routes.Params.{opt, withValid, Page, PageSize, Paging, Since}
import org.llm4s.ragbox.service.RAGService
import org.llm4s.ragbox.service.RagPipelines.blockingResult
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

  /** Default and maximum page sizes for GET /api/v1/documents. */
  val DefaultPageSize: Int = 50
  val MaxPageSize: Int = 500

  /**
   * The sync source of documents written with `PUT /api/v1/documents/{id}` without a `source`,
   * and the source `POST /api/v1/sync` prunes when the request names none. Uploads
   * (`POST /documents`, `/documents/upload`) belong to no source; configured ingestion sources
   * own their documents under their own names.
   */
  val DefaultSyncSource: String = "api"

  /** What a sync source name may look like: 1-128 of letters, digits and `._:/-`. */
  private val SourcePattern = "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$".r

  /** `Some(400)` when `source` is not a valid sync source name. */
  private def invalidSource(source: String): Option[IO[Response[IO]]] =
    Option.when(!SourcePattern.matches(source))(BadRequest(ErrorResponse.badRequest(
      s"Invalid source '$source'",
      Some("A source is 1-128 characters: letters, digits and . _ : / -, starting with a letter or digit")
    ).asJson))

  /** Which documents a `POST /sync` with a body prunes. */
  private enum SyncScope {
    /** The documents `source` owns. */
    case Source(source: String)
    /** Every document (admin-only opt-in). */
    case All

    def covers(entry: DocumentEntry): Boolean = this match {
      case Source(s) => entry.sourceId.contains(s)
      case All => true
    }
    def name: String = this match {
      case Source(_) => "source"
      case All => "all"
    }
    def sourceName: Option[String] = this match {
      case Source(s) => Some(s)
      case All => None
    }
  }

  /** The scope a prune request asks for, or the error response (400, or 403 for `all` by a non-admin). */
  private def syncScope(principal: Principal, request: SyncPruneRequest): Either[IO[Response[IO]], SyncScope] =
    (request.scope.map(_.trim.toLowerCase), request.source) match {
      case (Some("all"), Some(_)) =>
        Left(BadRequest(ErrorResponse.badRequest("Give either scope=all or a source, not both").asJson))
      case (Some("all"), None) =>
        if (principal.isAdmin) Right(SyncScope.All)
        else Left(Authz.forbidden("scope=all (pruning every document, whatever its source) is admin-only"))
      case (None | Some("source"), source) =>
        val s = source.getOrElse(DefaultSyncSource)
        invalidSource(s).toLeft(SyncScope.Source(s))
      case (Some(other), _) =>
        Left(BadRequest(ErrorResponse.badRequest(s"Invalid scope '$other'", Some("scope is 'source' (the default) or 'all'")).asJson))
    }

  /**
   * Default and maximum page sizes for GET /api/v1/sync/documents. The default is large so that
   * ingesters written before the listing was paged still get every document of a typical
   * installation in one response; `hasMore` says when there are more.
   */
  val DefaultSyncPageSize: Int = 1000
  val MaxSyncPageSize: Int = 10000

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
    val paging = Paging(pageOpt, pageSizeOpt, DefaultPageSize, MaxPageSize)
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

    val offset = paging.offset.toLong
    val pageItems =
      if (offset >= filtered.size) Seq.empty
      else filtered.slice(offset.toInt, offset.toInt + paging.pageSize)

    DocumentListResponse(
      documents = pageItems,
      total = filtered.size,
      page = paging.page,
      pageSize = paging.pageSize,
      hasMore = offset + pageItems.size < filtered.size
    )
  }

  // Query params for enhanced sync endpoint
  object IncludeParam extends OptionalQueryParamDecoderMatcher[String]("include")

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
      else io.circe.parser.decode[A](raw).map(Some(_)).left.map(_.show)
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
    ragService.createDocument(documentId, content, metadata, collection, readableByIds).flatMap(r => Created(r.asJson))
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
            accessible <- blockingResult(collections.findAccessible(auth, CollectionPattern.All))
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

  private def syncState(entry: DocumentEntry, includeHash: Boolean = true, includeUpdatedAt: Boolean = true): DocumentSyncState =
    DocumentSyncState(
      id = entry.documentId,
      hash = Option.when(includeHash)(entry.contentHash),
      updatedAt = Option.when(includeUpdatedAt)(entry.updatedAt),
      collection = entry.collection
    )

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

    // GET /api/v1/collections - Names of the collections that have documents (those the caller may
    // see). This is the one GET /api/v1/collections; the permission-mode collection objects with
    // their access lists are GET /api/v1/collections/accessible (CollectionPermissionRoutes).
    case GET -> Root / "api" / "v1" / "collections" as principal =>
      Authz.requireScope(principal, TokenScope.DocumentsRead) {
        (ragService.listCollections, accessFilter(ragService, principal)).flatMapN { (collections, allowed) =>
          Ok(Map("collections" -> collections.filter(allowed)).asJson)
        }
      }

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
        case Left(_) =>
          BadRequest(ErrorResponse.badRequest("Expected a multipart/form-data body").asJson)
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
                    BadRequest(ErrorResponse.badRequest("'metadata' must be a JSON object of strings", Some(error.show)).asJson)
                  case (_, Some(errorResponse)) => errorResponse
                  case (Right(_), None) if bytes.isEmpty =>
                    BadRequest(ErrorResponse.badRequest(s"'$filename' is empty").asJson)
                  case (Right(userMetadata), None) =>
                    ragService.extractText(bytes, filename).flatMap { case (text, extracted) =>
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
        :? Page(pageOpt) +& PageSize(pageSizeOpt) +& CollectionParam(collectionOpt) +& SearchParam(searchOpt) as principal =>
      Authz.requireScope(principal, TokenScope.DocumentsRead) {
        withValid((opt(pageOpt), opt(pageSizeOpt)).tupled) { case (page, pageSize) =>
          (ragService.listDocumentEntries, accessFilter(ragService, principal)).flatMapN { (docs, allowed) =>
            Ok(buildListResponse(docs.filter(e => allowed(collectionOf(e))), page, pageSize, collectionOpt, searchOpt).asJson)
          }
        }
      }

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
        source = body.source.getOrElse(DefaultSyncSource)
        response <- InputValidation.toResponse(validation).orElse(invalidSource(source)) match {
          case Some(errorResponse) => errorResponse
          // The target collection must be allowed, and so must the one an existing document is in
          case None => requireCollectionAccess(ragService, principal, body.collection.getOrElse(RAGService.DefaultCollection)) {
            requireDocumentsAccessible(ragService, principal, Seq(id)) {
              val metadata = body.metadata.getOrElse(Map.empty) ++ body.collection.map("collection" -> _).toMap
              // The document belongs to `source` (default `api`), so that source's sync prunes it
              ragService.upsertAs(id, body.content, metadata, body.collection, body.readableBy, body.contentHash, Some(source)).flatMap { upserted =>
                if (upserted.action == "created") Created(upserted.asJson) else Ok(upserted.asJson)
              }
            }
          }
        }
      } yield response }

    // GET /api/v1/documents/:id - Get document details
    // Returns registry metadata; chunk content is served by /api/v1/visibility/chunks/:id
    case GET -> Root / "api" / "v1" / "documents" / id as principal =>
      Authz.requireScope(principal, TokenScope.DocumentsRead) {
        (ragService.getDocumentEntries(Seq(id)), accessFilter(ragService, principal)).flatMapN { (entries, allowed) =>
          entries.filter(e => allowed(collectionOf(e))).find(_.documentId == id) match {
            case Some(entry) => Ok(toDocumentInfo(entry).asJson)
            case None => NotFound(ErrorResponse.notFound(s"Document $id not found").asJson)
          }
        }
      }

    // DELETE /api/v1/documents/:id - Delete document
    case DELETE -> Root / "api" / "v1" / "documents" / id as principal =>
      Authz.requireScope(principal, TokenScope.DocumentsWrite) {
        requireDocumentsAccessible(ragService, principal, Seq(id)) {
          ragService.deleteDocument(id) *> NoContent()
        }
      }

    // GET /api/v1/sync/status - Get sync status
    case GET -> Root / "api" / "v1" / "sync" / "status" as principal =>
      Authz.requireIngester(principal, TokenScope.SyncRead) {
        ragService.getSyncStatus.flatMap(s => Ok(s.asJson))
      }

    // POST /api/v1/sync - Mark sync complete and optionally prune
    // Pruning is scoped: by default only the documents of one sync source (`source`, default
    // `api`) that are not in keepDocumentIds; `scope: "all"` (admin-only) prunes every document.
    // Supports dryRun to preview what would be deleted.
    case (req @ POST -> Root / "api" / "v1" / "sync") as principal =>
      Authz.requireIngester(principal, TokenScope.SyncWrite) { for {
        body <- decodeOptionalBody[SyncPruneRequest](req)
        response <- body match {
          case Left(err) => invalidBody(err)
          case Right(Some(pruneReq)) => syncScope(principal, pruneReq) match {
            case Left(errorResponse) => errorResponse
            case Right(scope) =>
            val isDryRun = pruneReq.dryRun.getOrElse(false)
            val keep = pruneReq.keepDocumentIds.toSet
            // In scope: the source's documents (or all), and for a collection-restricted token
            // only those in its collections
            val inScope: DocumentEntry => Boolean =
              e => scope.covers(e) && principal.canAccessCollection(collectionOf(e))
            def response(message: String, count: Int, ids: Option[Seq[String]]) = SyncPruneResponse(
              message = message, prunedCount = count, prunedIds = ids, dryRun = isDryRun,
              scope = Some(scope.name), source = scope.sourceName
            )
            val result = if (isDryRun) {
              // Dry run - just find orphans without deleting
              ragService.findOrphanedDocuments(keep, inScope).map { orphans =>
                response(s"Dry run: would delete ${orphans.size} documents", orphans.size, Some(orphans))
              }
            } else {
              // Actual prune - delete orphans and mark sync complete
              for {
                deleted <- ragService.pruneDocuments(keep, inScope)
                _ <- ragService.markSyncComplete()
              } yield response("Sync completed", deleted, None)
            }
            result.flatMap(r => Ok(r.asJson))
          }
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

    // GET /api/v1/sync/documents - List registered document states, a page at a time
    // Query params:
    //   - include=hash,updatedAt - Include additional fields
    //   - since=2024-01-01T00:00:00Z - Only documents modified since this time
    //   - page (default 1), pageSize (default 1000, max 10000)
    case GET -> Root / "api" / "v1" / "sync" / "documents"
        :? IncludeParam(includeOpt) +& Since(sinceOpt) +& Page(pageOpt) +& PageSize(pageSizeOpt) as principal =>
      Authz.requireIngester(principal, TokenScope.SyncRead) {
        withValid((opt(sinceOpt), opt(pageOpt), opt(pageSizeOpt)).tupled) { case (since, page, pageSize) =>
          val paging = Paging(page, pageSize, DefaultSyncPageSize, MaxSyncPageSize)
          val includeFields = includeOpt.map(_.split(",").map(_.trim.toLowerCase).toSet).getOrElse(Set.empty)
          ragService.listDocumentEntriesPage(since, principal.collections, paging.offset, paging.pageSize).flatMap { result =>
            Ok(DocumentSyncListResponse(
              documents = result.entries.map(e => syncState(e, includeFields.contains("hash"), includeFields.contains("updatedat"))),
              total = result.total,
              page = paging.page,
              pageSize = paging.pageSize,
              hasMore = paging.offset + result.entries.size < result.total
            ).asJson)
          }
        }
      }

    // GET /api/v1/sync/documents/:id - Get single document state
    case GET -> Root / "api" / "v1" / "sync" / "documents" / id as principal =>
      Authz.requireIngester(principal, TokenScope.SyncRead) {
        ragService.getDocumentEntries(Seq(id)).flatMap { entries =>
          visibleTo(principal, entries).headOption match {
            case Some(entry) => Ok(syncState(entry).asJson)
            case None => NotFound(ErrorResponse.notFound(s"Document $id not found").asJson)
          }
        }
      }

    // POST /api/v1/sync/check - Batch check document states
    case (req @ POST -> Root / "api" / "v1" / "sync" / "check") as principal =>
      Authz.requireIngester(principal, TokenScope.SyncRead) { for {
        body <- req.as[SyncCheckRequest]
        entries <- ragService.getDocumentEntries(body.documentIds).map(visibleTo(principal, _))
        foundIds = entries.map(_.documentId).toSet
        missing = body.documentIds.filterNot(foundIds.contains)
        response <- Ok(SyncCheckResponse(found = entries.map(e => syncState(e)), missing = missing).asJson)
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
            Authz.requireAdmin(principal)(ragService.clearAll *> Ok(Map("message" -> "All documents cleared").asJson))
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
      Authz.requireScope(principal, TokenScope.DocumentsRead) {
        ragService.getStats.flatMap(s => Ok(s.asJson))
      }
  }
}

package org.llm4s.ragbox.service

import cats.effect.IO
import cats.syntax.all._
import org.llm4s.chunking.DocumentChunk
import org.llm4s.extract.TikaDocumentExtractor
import org.llm4s.llmconnect.model.{EmbeddingError, EmbeddingRequest}
import org.llm4s.rag.permissions._
import org.llm4s.rag.permissions.pg.PgSchemaManager
import org.llm4s.ragbox.config.{AppConfig, RuntimeSettings}
import org.llm4s.ragbox.db.ConnectionPool
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.{CollectionConfigRegistry, DocumentEntry, DocumentRegistry}
import org.llm4s.ragbox.service.RagPipelines.blockingResult
import org.llm4s.ragbox.store.ChunkStore

import java.security.MessageDigest
import java.time.Instant
import javax.sql.DataSource

/**
 * Writing documents: ingest, upsert, delete and prune, keeping the llm4s indexes, the chunk store
 * and the document registry in step.
 *
 * In permission mode (a [[SearchIndex]] is configured) every document lives in an llm4s
 * collection: queries filter on `collection_id`, so a vector written without one could never be
 * retrieved. Ingests that name no collection go to [[RAGService.DefaultCollection]], and deletes
 * go through the collection the document was registered in.
 *
 * '''Chunking.''' Each document is chunked once, with the chunking its collection resolves to
 * (`CollectionConfigRegistry.getEffective`: a file-type override, the collection's config, or
 * the runtime settings), and those chunks are what llm4s indexes and the chunk store keeps.
 */
final class DocumentIngestion(
  config: AppConfig,
  pipelines: RagPipelines,
  documentRegistry: DocumentRegistry,
  chunkStore: ChunkStore,
  dataSource: Option[DataSource],
  collectionConfigs: Option[CollectionConfigRegistry]
) {
  import RAGService.DefaultCollection

  private def collections: Option[CollectionStore] = pipelines.searchIndex.map(_.collections)

  /**
   * The chunking a document gets: its collection's file-type override or config when it has
   * one (and a collection config registry is attached), otherwise the runtime settings.
   */
  def effectiveChunking(collection: Option[String], filename: Option[String]): IO[EffectiveCollectionConfig] = {
    val current = pipelines.current
    val defaultMaxSize = RuntimeSettings.defaultMaxSize(current.chunkSize)
    (collection, collectionConfigs) match {
      case (Some(c), Some(registry)) =>
        registry.getEffective(c, filename, current.chunkingStrategy, current.chunkSize, defaultMaxSize, current.chunkOverlap)
      case _ =>
        IO.pure(CollectionConfigRegistry.resolveEffective(
          None, filename, current.chunkingStrategy, current.chunkSize, defaultMaxSize, current.chunkOverlap
        ))
    }
  }

  /**
   * Ingest a document with content and metadata. In permission mode it goes into its
   * `collection` metadata value, or the default collection when there is none.
   */
  def ingestDocument(content: String, documentId: String, metadata: Map[String, String]): IO[DocumentUploadResponse] =
    if (pipelines.hasPermissions)
      ingestWithPermissions(metadata.getOrElse("collection", DefaultCollection), documentId, content, metadata)
    else
      ingestAndRegister(documentId, content, DocumentIngestion.contentHash(content), metadata, sourceId = None).map { result =>
        DocumentUploadResponse(documentId, result.chunks, s"Successfully ingested ${result.chunks} chunks")
      }

  /**
   * Extract the text of an uploaded file (PDF, DOCX, HTML, plain text, ... - anything Apache Tika
   * reads) with llm4s's `TikaDocumentExtractor`, the extractor behind `RAG.ingestBytes`.
   *
   * ragbox extracts and then ingests the text itself rather than calling `RAG.ingestBytes`, so an
   * upload goes through the same path as any other document: chunked once into the chunk store,
   * registered with its content hash, and in permission mode put in its collection (llm4s has no
   * permission-aware `ingestBytes`).
   *
   * @return the text and the metadata worth keeping: the detected format and, when Tika found
   *         them, title and author
   */
  def extractText(content: Array[Byte], filename: String): IO[(String, Map[String, String])] =
    IO.blocking(TikaDocumentExtractor.extract(content, filename)).flatMap {
      case Left(error) =>
        IO.raiseError(InvalidRequestError(s"Could not extract text from '$filename': ${error.message}"))
      case Right(extracted) if extracted.text.trim.isEmpty =>
        IO.raiseError(InvalidRequestError(s"'$filename' contains no extractable text"))
      case Right(extracted) =>
        val kept = extracted.metadata.collect {
          case (key, value) if DocumentIngestion.ExtractedMetadataKeys.contains(key.toLowerCase) && value.trim.nonEmpty =>
            key.toLowerCase.replace("dc:", "") -> value.trim.take(500)
        }
        IO.pure((extracted.text, kept + ("format" -> extracted.format.name)))
    }

  /**
   * Delete a document by ID: its vectors, its visible chunks and its registry entry.
   *
   * In permission mode the vectors are deleted from the collection the document is registered
   * in (or the default collection). `RAG.deleteDocument` cannot be used there: it deletes by the
   * `<docId>-chunk-` id prefix, while permission ingest writes `coll-<id>-<docId>-chunk-<n>`.
   */
  def deleteDocument(documentId: String): IO[Unit] = {
    val deleteVectors: IO[Unit] =
      if (pipelines.hasPermissions)
        documentRegistry.get(documentId).flatMap { entry =>
          deleteVectorsFromCollection(entry.flatMap(_.collection).getOrElse(DefaultCollection), documentId).void
        }
      else pipelines.withCurrent(r => blockingResult(r.deleteDocument(documentId)))
    deleteVectors *> chunkStore.deleteByDocument(documentId) *> documentRegistry.remove(documentId)
  }

  /**
   * Delete a document's vectors from a collection (permission mode).
   *
   * A registered collection can be a free-text label from before permission mode that names no
   * llm4s collection. Such a document's vectors were migrated into the default collection at
   * startup, so that is where they are deleted from.
   */
  private def deleteVectorsFromCollection(collection: String, documentId: String): IO[Long] =
    for {
      existingPath <- CollectionPath.create(collection).toOption match {
        case Some(path) => collectionExists(path).map(exists => Option.when(exists)(path))
        case None => IO.pure(None)
      }
      path = existingPath.getOrElse(CollectionPath.unsafe(DefaultCollection))
      deleted <- pipelines.withCurrent(r => blockingResult(r.deleteFromCollection(path, documentId)))
    } yield deleted

  private def collectionExists(path: CollectionPath): IO[Boolean] =
    collections.fold(IO.pure(false))(store => blockingResult(store.get(path).map(_.isDefined)))

  /** Delete every document. */
  def clearAll: IO[Unit] =
    pipelines.withCurrent(r => blockingResult(r.clear())) *> chunkStore.clear() *> documentRegistry.clear()

  /**
   * Upsert a document (idempotent create/update): indexed when it is new or its content changed,
   * left alone when the content hash is unchanged.
   */
  def upsertDocument(
    documentId: String,
    content: String,
    metadata: Map[String, String],
    providedHash: Option[String]
  ): IO[DocumentUpsertResponse] =
    upsertFrom(None, documentId, content, metadata, providedHash)

  /**
   * Upsert a document on behalf of an ingestion source, recording the source as its owner.
   *
   * Change detection uses the persistent registry's content hash, so an unchanged document is
   * not re-embedded - also after a restart. The owner is what lets a source's sync remove only
   * its own documents (see [[listSourceDocumentIds]]).
   */
  def upsertSourceDocument(sourceId: String, documentId: String, content: String, metadata: Map[String, String]): IO[DocumentUpsertResponse] =
    upsertFrom(Some(sourceId), documentId, content, metadata, None)

  /** IDs of the documents an ingestion source owns. */
  def listSourceDocumentIds(sourceId: String): IO[Seq[String]] =
    documentRegistry.listIdsBySource(sourceId)

  private def upsertFrom(
    sourceId: Option[String],
    documentId: String,
    content: String,
    metadata: Map[String, String],
    providedHash: Option[String]
  ): IO[DocumentUpsertResponse] =
    if (pipelines.hasPermissions)
      upsertWithPermissions(
        collectionPath = metadata.getOrElse("collection", DefaultCollection),
        documentId = documentId,
        content = content,
        metadata = metadata,
        providedHash = providedHash,
        sourceId = sourceId
      )
    else upsertWithoutPermissions(documentId, content, metadata, providedHash, sourceId)

  private def upsertWithoutPermissions(
    documentId: String,
    content: String,
    metadata: Map[String, String],
    providedHash: Option[String],
    sourceId: Option[String]
  ): IO[DocumentUpsertResponse] = {
    val contentHash = providedHash.getOrElse(DocumentIngestion.contentHash(content))
    documentRegistry.get(documentId).flatMap {
      case Some(existing) if existing.contentHash == contentHash =>
        claimForSource(existing, sourceId).as(unchanged(documentId, existing.chunkCount, contentHash))
      case Some(_) =>
        // Content changed - delete (ignoring a document that is already gone) and re-index
        deleteDocument(documentId).attempt *>
          ingestAndRegister(documentId, content, contentHash, metadata, sourceId).map(_.copy(action = "updated"))
      case None =>
        ingestAndRegister(documentId, content, contentHash, metadata, sourceId)
    }
  }

  private def unchanged(documentId: String, chunks: Int, contentHash: String): DocumentUpsertResponse =
    DocumentUpsertResponse(
      documentId = documentId,
      chunks = chunks,
      action = "unchanged",
      message = s"Document unchanged (hash: ${contentHash.take(8)}...)"
    )

  /**
   * An unchanged document a source lists becomes that source's, so the source's sync removes it
   * once it is gone - without re-embedding anything. Upserts not from a source change no owner.
   */
  private def claimForSource(existing: DocumentEntry, sourceId: Option[String]): IO[Unit] =
    sourceId match {
      case Some(_) if existing.sourceId != sourceId => documentRegistry.put(existing.copy(sourceId = sourceId))
      case _ => IO.unit
    }

  /**
   * Chunk a document once, with the chunking its collection and file name resolve to (see
   * [[effectiveChunking]]), on the blocking pool (the semantic chunker calls the embedding
   * provider).
   */
  private def chunkContent(
    documentId: String,
    content: String,
    metadata: Map[String, String],
    collection: Option[String]
  ): IO[Seq[DocumentChunk]] =
    for {
      effective <- effectiveChunking(collection, RAGService.sourceName(documentId, metadata))
      chunker <- pipelines.chunkerFor(effective.strategy)
      chunks <- IO.blocking {
        val chunks = chunker.chunk(content, RAGService.chunkingConfig(effective))
        metadata.get("filename").fold(chunks)(f => chunks.map(ch => ch.copy(metadata = ch.metadata.withSource(f))))
      }
    } yield chunks

  /**
   * Ingest a document without permissions: chunk it once and hand exactly those chunks to llm4s
   * (`RAG.ingestChunks`), which embeds and indexes them as `<documentId>-chunk-<n>` in both the
   * vector and the keyword store. The chunk store keeps the same chunks under the same ids.
   */
  private def ingestAndRegister(
    documentId: String,
    content: String,
    contentHash: String,
    metadata: Map[String, String],
    sourceId: Option[String]
  ): IO[DocumentUpsertResponse] = {
    val collection = metadata.get("collection")
    for {
      chunks <- chunkContent(documentId, content, metadata, collection)
      chunkCount <- pipelines.withCurrent(r => blockingResult(r.ingestChunks(documentId, chunks.map(_.content), metadata)))
      _ <- recordIngest(
        documentId = documentId,
        chunks = chunks.zipWithIndex.map { case (chunk, i) =>
          toChunkInfo(RAGService.chunkId(documentId, i), documentId, i, collection, chunk)
        },
        contentHash = contentHash,
        chunkCount = chunkCount,
        metadata = metadata,
        collection = collection,
        readableBy = Set.empty,
        sourceId = sourceId
      )
    } yield DocumentUpsertResponse(
      documentId = documentId,
      chunks = chunkCount,
      action = "created",
      message = s"Successfully indexed $chunkCount chunks"
    )
  }

  /** After a successful ingest: store the document's indexed chunks and register the document. */
  private def recordIngest(
    documentId: String,
    chunks: Seq[ChunkInfo],
    contentHash: String,
    chunkCount: Int,
    metadata: Map[String, String],
    collection: Option[String],
    readableBy: Set[Int],
    sourceId: Option[String]
  ): IO[Unit] = {
    val now = Instant.now()
    chunkStore.store(documentId, chunks) *>
      documentRegistry.put(DocumentEntry(
        documentId = documentId,
        contentHash = contentHash,
        chunkCount = chunkCount,
        metadata = metadata,
        collection = collection,
        indexedAt = now,
        updatedAt = now,
        readableBy = readableBy,
        sourceId = sourceId
      ))
  }

  /**
   * A chunk as the chunk store keeps it, under the id llm4s indexed it as. Chunks of documents
   * without a collection have no `collection` metadata (stored as NULL), which is what the
   * default-collection statistics query for.
   */
  private def toChunkInfo(id: String, documentId: String, index: Int, collection: Option[String], chunk: DocumentChunk): ChunkInfo =
    ChunkInfo(
      id = id,
      documentId = documentId,
      index = index,
      content = chunk.content,
      contentLength = chunk.length,
      metadata = collection.map("collection" -> _).toMap,
      chunkMetadata = ChunkMetadataInfo(
        headings = chunk.metadata.headings,
        isCodeBlock = chunk.metadata.isCodeBlock,
        language = chunk.metadata.language,
        startOffset = chunk.metadata.startOffset,
        endOffset = chunk.metadata.endOffset
      )
    )

  /**
   * Delete the documents not in `keepDocumentIds` (garbage collection after a sync). A document
   * that fails to delete is skipped.
   *
   * @return how many were deleted
   */
  def pruneDocuments(keepDocumentIds: Set[String]): IO[Int] =
    documentRegistry.findOrphans(keepDocumentIds).flatMap { toDelete =>
      toDelete.toList.traverse(id => deleteDocument(id).attempt.map(_.isRight)).map(_.count(identity))
    }

  /**
   * Ingest a document with permission controls.
   *
   * @param collectionPath collection for the document; [[RAGService.DefaultCollection]] for
   *                       documents that name none
   * @param readableBy     principals who can read it (empty: inherit from the collection)
   */
  def ingestWithPermissions(
    collectionPath: String,
    documentId: String,
    content: String,
    metadata: Map[String, String] = Map.empty,
    readableBy: Set[PrincipalId] = Set.empty
  ): IO[DocumentUploadResponse] =
    ingestIntoCollection(collectionPath, documentId, content, metadata, readableBy, DocumentIngestion.contentHash(content), None)

  /**
   * Ingest into a collection: chunk the document once (with its collection's chunking, see
   * [[chunkContent]]), embed the chunks and write them through llm4s's `SearchIndex.ingest` -
   * the call `RAG.ingestWithPermissions` makes once it has chunked and embedded. ragbox does not
   * call `RAG.ingestWithPermissions` itself, as that chunks with the pipeline's one chunking
   * config (llm4s/llm4s#2732). The chunk store keeps the same chunks under the ids llm4s gave
   * them (`coll-<collectionId>-<doc>-chunk-<n>`).
   */
  private def ingestIntoCollection(
    collectionPath: String,
    documentId: String,
    content: String,
    metadata: Map[String, String],
    readableBy: Set[PrincipalId],
    contentHash: String,
    sourceId: Option[String]
  ): IO[DocumentUploadResponse] =
    for {
      path <- DocumentIngestion.parsePath(collectionPath)
      index <- IO.fromOption(pipelines.searchIndex)(new IllegalStateException("Permission-aware ingestion needs a SearchIndex"))
      chunks <- chunkContent(documentId, content, metadata, Some(path.value))
      embeddings <- embed(chunks.map(_.content))
      chunkCount <- blockingResult(index.ingest(
        collectionPath = path,
        documentId = documentId,
        chunks = chunks.zip(embeddings).zipWithIndex.map { case ((chunk, embedding), i) =>
          ChunkWithEmbedding(content = chunk.content, embedding = embedding, chunkIndex = i, metadata = metadata)
        },
        metadata = metadata,
        readableBy = readableBy
      ))
      collectionId <- collections.traverse(store => blockingResult(store.get(path))).map(_.flatten.map(_.id))
      // The registry records documents in the default collection with no collection, which is
      // how it has always represented "default" (see PgDocumentRegistry.listIdsByCollection).
      registeredCollection = Option.when(path.value != DefaultCollection)(path.value)
      _ <- recordIngest(
        documentId = documentId,
        chunks = chunks.zipWithIndex.map { case (chunk, i) =>
          val id = collectionId.fold(RAGService.chunkId(documentId, i))(cid => s"coll-$cid-${RAGService.chunkId(documentId, i)}")
          toChunkInfo(id, documentId, i, registeredCollection, chunk)
        },
        contentHash = contentHash,
        chunkCount = chunkCount,
        metadata = metadata ++ registeredCollection.map("collection" -> _),
        collection = registeredCollection,
        readableBy = readableBy.map(_.value),
        sourceId = sourceId
      )
    } yield DocumentUploadResponse(
      documentId = documentId,
      chunks = chunkCount,
      message = s"Successfully indexed $chunkCount chunks in ${path.value}"
    )

  /** Embed `texts` in one request, as llm4s's `RAG` embeds a document's chunks. */
  private def embed(texts: Seq[String]): IO[Seq[Array[Float]]] =
    if (texts.isEmpty) IO.pure(Seq.empty)
    else
      pipelines.embedder.flatMap { case (client, model) =>
        blockingResult(client.embed(EmbeddingRequest(texts, model)).flatMap { response =>
          Either.cond(
            response.embeddings.size == texts.size,
            response.embeddings.map(_.map(_.toFloat).toArray),
            EmbeddingError(None, s"Embedding provider returned ${response.embeddings.size} embeddings for ${texts.size} texts", model.name)
          )
        })
      }

  /**
   * Upsert a document with permission controls (idempotent create/update).
   *
   * The document is unchanged - and not re-indexed - only when its content hash, collection and
   * access list all match the registry. Otherwise its vectors are deleted from the collection it
   * is registered in (which may differ from `collectionPath`, for a move) and it is re-ingested.
   *
   * @param sourceId the ingestion source upserting the document, recorded as its owner
   */
  def upsertWithPermissions(
    collectionPath: String,
    documentId: String,
    content: String,
    metadata: Map[String, String] = Map.empty,
    readableBy: Set[PrincipalId] = Set.empty,
    providedHash: Option[String] = None,
    sourceId: Option[String] = None
  ): IO[DocumentUpsertResponse] = {
    val contentHash = providedHash.getOrElse(DocumentIngestion.contentHash(content))

    def ingest(action: String): IO[DocumentUpsertResponse] =
      // Register the caller's hash, so the next upsert with the same hash is unchanged
      ingestIntoCollection(collectionPath, documentId, content, metadata, readableBy, contentHash, sourceId)
        .map(result => DocumentUpsertResponse(result.documentId, result.chunks, action, result.message))

    (DocumentIngestion.parsePath(collectionPath), documentRegistry.get(documentId)).flatMapN {
      case (path, Some(existing))
          if existing.contentHash == contentHash &&
            existing.collection.getOrElse(DefaultCollection) == path.value &&
            existing.readableBy == readableBy.map(_.value) =>
        // Content, collection and access list unchanged - skip re-indexing
        claimForSource(existing, sourceId).as(unchanged(documentId, existing.chunkCount, contentHash))

      case (_, Some(existing)) =>
        // Changed - delete from the collection it is in now (not the target one, for a move),
        // then re-index
        deleteVectorsFromCollection(existing.collection.getOrElse(DefaultCollection), documentId) *>
          chunkStore.deleteByDocument(documentId) *>
          ingest("updated")

      case (_, None) =>
        // Not registered, but it may still have vectors written outside the registry - by an
        // ingestion source before permission mode routed sources through collections - that
        // startup migrated into the default collection. Remove them so it is not indexed twice.
        deleteVectorsFromCollection(DefaultCollection, documentId) *>
          ingest("created")
    }
  }

  /** Delete a document from a specific collection. */
  def deleteFromCollection(collectionPath: String, documentId: String): IO[Long] =
    for {
      path <- DocumentIngestion.parsePath(collectionPath)
      deleted <- pipelines.withCurrent(r => blockingResult(r.deleteFromCollection(path, documentId)))
      _ <- chunkStore.deleteByDocument(documentId)
      _ <- documentRegistry.remove(documentId)
    } yield deleted

  /**
   * Permission mode: make sure the default collection exists and move vectors that have no
   * collection into it.
   *
   * Uses llm4s's own `PgSchemaManager` definitions: the default collection is a public (empty
   * `queryable_by`) leaf named `default`. Vectors written before permission mode was enabled -
   * or by an ingest path that bypassed collections - have `collection_id = NULL`, which
   * permission-filtered queries can never return; migrating them makes them searchable again.
   *
   * @return the number of vectors migrated
   */
  def ensureDefaultCollection: IO[Long] =
    (pipelines.hasPermissions, dataSource) match {
      case (false, _) => IO.pure(0L)
      case (true, None) =>
        IO.raiseError(new IllegalStateException("Permission mode needs the application's connection pool"))
      case (true, Some(ds)) =>
        ConnectionPool.withTransaction(ds) { conn =>
          for {
            defaultId <- PgSchemaManager.createDefaultCollection(conn)
            migrated <- PgSchemaManager.migrateExistingVectors(conn, config.database.tableName, defaultId)
          } yield migrated
        }.flatMap(result => IO.fromEither(result.left.map(e => new RuntimeException(e.message))))
    }
}

object DocumentIngestion {

  /** Tika metadata kept on an uploaded document (lower-cased keys; `dc:` is dropped). */
  private val ExtractedMetadataKeys: Set[String] = Set("dc:title", "title", "dc:creator", "author")

  /** SHA-256 of the content, for change detection. */
  def contentHash(content: String): String =
    MessageDigest.getInstance("SHA-256").digest(content.getBytes("UTF-8")).map("%02x".format(_)).mkString

  def parsePath(path: String): IO[CollectionPath] =
    IO.fromEither(
      CollectionPath.create(path).left.map(e => InvalidRequestError(s"Invalid collection path '$path': ${e.message}"))
    )
}

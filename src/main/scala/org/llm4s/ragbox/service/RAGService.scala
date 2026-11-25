package org.llm4s.ragbox.service

import cats.effect.IO
import cats.syntax.all._
import fs2.Stream
import org.llm4s.chunking.ChunkingConfig
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.rag.RAG
import org.llm4s.rag.permissions._
import org.llm4s.ragbox.auth.{PermissionContext, Principal}
import org.llm4s.ragbox.config.{AppConfig, RuntimeSettings}
import org.llm4s.ragbox.service.Llm4sPipeline.{ChatTurn, PipelineSettings}
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.{CollectionConfigRegistry, DocumentEntry, DocumentEntryPage, DocumentRegistry, PgDocumentRegistry}
import org.llm4s.ragbox.store.{ChunkStore, PgChunkStore}

import javax.sql.DataSource

/**
 * ragbox's RAG operations, the one service the routes use. It is a thin facade over cohesive
 * services that share the llm4s components in [[RagPipelines]]:
 *
 *  - [[DocumentIngestion]]: ingest, upsert, delete and prune documents (chunking, embedding,
 *    permission-mode collections, the chunk store and the document registry)
 *  - [[QueryService]]: search, answers and chat replies, with their metrics
 *  - [[StatsService]]: statistics, chunks and sync status (the visibility API)
 *  - [[SystemInfoService]]: health, readiness, configuration views and providers
 *
 * It also decides, in one place, how a request runs in permission mode: [[answer]], [[searchAs]]
 * and [[chatAs]] filter by the caller's permissions when a [[SearchIndex]] is configured, and
 * [[createDocument]] / [[upsertAs]] resolve access lists. Its methods are what tests override
 * (`MockRAGService`), so the composite ones go through the overridable ones.
 *
 * @param providerRegistry  where the embedding provider is resolved; tests pass one holding a
 *                          deterministic in-process embedder
 * @param dataSource        the application's shared connection pool, for the permission-mode
 *                          schema setup in [[ensureDefaultCollection]]
 * @param runtimeSettings   the runtime-modifiable settings; `Main` passes the
 *                          `RuntimeConfigManager`. By default the configuration file's, fixed.
 * @param collectionConfigs per-collection chunking configs; without them every document is
 *                          chunked with the runtime settings
 */
class RAGService(
  config: AppConfig,
  documentRegistry: DocumentRegistry,
  chunkStore: ChunkStore,
  val searchIndex: Option[SearchIndex] = None,
  providerRegistry: ProviderRegistry = ProviderRegistry.default,
  dataSource: Option[DataSource] = None,
  runtimeSettings: Option[RuntimeSettings] = None,
  collectionConfigs: Option[CollectionConfigRegistry] = None
) {

  private val pipelines = new RagPipelines(config, providerRegistry, searchIndex, runtimeSettings)
  private val documents = new DocumentIngestion(config, pipelines, documentRegistry, chunkStore, dataSource, collectionConfigs)
  private val queries = new QueryService(pipelines)
  private val stats = new StatsService(pipelines, documentRegistry, chunkStore)
  private val system = new SystemInfoService(config, pipelines, documentRegistry)

  /** PrincipalStore for user/group ids; only with a SearchIndex. */
  def principals: Option[PrincipalStore] = searchIndex.map(_.principals)

  /** CollectionStore for the collection hierarchy; only with a SearchIndex. */
  def collections: Option[CollectionStore] = searchIndex.map(_.collections)

  /** Permission-based RAG is enabled (a SearchIndex is configured). */
  def hasPermissions: Boolean = searchIndex.isDefined

  /** The runtime settings in effect now. */
  def currentSettings: RuntimeConfig = pipelines.current

  /** The pipeline settings the current runtime settings select. */
  def currentPipelineSettings: PipelineSettings = pipelines.currentPipelineSettings

  /**
   * The pipeline settings `overrides` select over the current runtime settings, or an
   * [[InvalidRequestError]] for a value llm4s has no strategy for.
   */
  def pipelineSettingsFor(overrides: RAGService.SettingsOverride): Either[InvalidRequestError, PipelineSettings] =
    pipelines.settingsFor(overrides)

  /** The pipeline for the current runtime settings, built if need be (for checks and warm-up). */
  def rag: IO[RAG] = pipelines.rag

  def getDocumentRegistry: DocumentRegistry = documentRegistry

  // ============================================================
  // Permission-aware dispatch
  // ============================================================

  /** The caller's llm4s authorization in permission mode; None without permissions. */
  def authorizationFor(principal: Principal): IO[Option[UserAuthorization]] =
    principals.filter(_ => hasPermissions).traverse(ps => PermissionContext.authorization(principal, ps))

  /** Answer `question` as `principal`: permission-filtered to `collectionPattern` in permission mode. */
  def answer(
    principal: Principal,
    question: String,
    collectionPattern: String,
    topK: Option[Int],
    overrides: RAGService.SettingsOverride = RAGService.SettingsOverride.Empty
  ): IO[QueryResponse] =
    authorizationFor(principal).flatMap {
      case Some(auth) => queryWithPermissionsAndAnswer(question, auth, collectionPattern, topK, overrides)
      case None => queryWithAnswer(question, topK, overrides)
    }

  /** [[answer]], streamed as it is generated. */
  def answerStream(
    principal: Principal,
    question: String,
    collectionPattern: String,
    topK: Option[Int],
    overrides: RAGService.SettingsOverride = RAGService.SettingsOverride.Empty
  ): Stream[IO, AnswerEvent] =
    Stream.eval(authorizationFor(principal)).flatMap { auth =>
      streamAnswer(question, auth.map(_ -> collectionPattern), Nil, topK, overrides)
    }

  /** Search as `principal`: permission-filtered to `collectionPattern` in permission mode. */
  def searchAs(principal: Principal, query: String, collectionPattern: String, topK: Option[Int]): IO[SearchResponse] =
    authorizationFor(principal).flatMap {
      case Some(auth) => searchWithPermissions(query, auth, collectionPattern, topK)
      case None => search(query, topK)
    }

  /** A chat reply as `principal`, retrieving from `collectionPattern` in permission mode. */
  def chatAs(principal: Principal, question: String, history: Seq[ChatTurn], collectionPattern: String): IO[QueryResponse] =
    authorizationFor(principal).flatMap { auth =>
      chatAnswer(question, history, auth.map(_ -> collectionPattern), topK = None)
    }

  /** [[chatAs]], streamed as it is generated. */
  def chatStreamAs(principal: Principal, question: String, history: Seq[ChatTurn], collectionPattern: String): Stream[IO, AnswerEvent] =
    Stream.eval(authorizationFor(principal)).flatMap { auth =>
      streamAnswer(question, auth.map(_ -> collectionPattern), history, topK = None, RAGService.SettingsOverride.Empty)
    }

  /**
   * Ingest a new document: into `collection` (or the default one) with the `readableBy` access
   * list in permission mode, plainly otherwise.
   */
  def createDocument(
    documentId: String,
    content: String,
    metadata: Map[String, String],
    collection: Option[String],
    readableBy: Option[Seq[String]]
  ): IO[DocumentUploadResponse] =
    principals.filter(_ => hasPermissions) match {
      case Some(ps) =>
        RAGService.resolvePrincipals(readableBy.getOrElse(Nil), ps).flatMap { readers =>
          ingestWithPermissions(collection.getOrElse(RAGService.DefaultCollection), documentId, content, metadata, readers)
        }
      case None => ingestDocument(content, documentId, metadata)
    }

  /**
   * Upsert a document: into `collection` with the `readableBy` access list in permission mode.
   * `sourceId` is the sync source recorded as the document's owner (see `POST /sync`).
   */
  def upsertAs(
    documentId: String,
    content: String,
    metadata: Map[String, String],
    collection: Option[String],
    readableBy: Option[Seq[String]],
    providedHash: Option[String],
    sourceId: Option[String] = None
  ): IO[DocumentUpsertResponse] =
    principals.filter(_ => hasPermissions) match {
      case Some(ps) =>
        RAGService.resolvePrincipals(readableBy.getOrElse(Nil), ps).flatMap { readers =>
          upsertWithPermissions(collection.getOrElse(RAGService.DefaultCollection), documentId, content, metadata, readers, providedHash, sourceId)
        }
      case None => upsertDocument(documentId, content, metadata, providedHash, sourceId)
    }

  // ============================================================
  // Documents (DocumentIngestion)
  // ============================================================

  def effectiveChunking(collection: Option[String], filename: Option[String]): IO[EffectiveCollectionConfig] =
    documents.effectiveChunking(collection, filename)

  def ingestDocument(content: String, documentId: String, metadata: Map[String, String]): IO[DocumentUploadResponse] =
    documents.ingestDocument(content, documentId, metadata)

  def extractText(content: Array[Byte], filename: String): IO[(String, Map[String, String])] =
    documents.extractText(content, filename)

  def deleteDocument(documentId: String): IO[Unit] = documents.deleteDocument(documentId)

  def clearAll: IO[Unit] = documents.clearAll

  def upsertDocument(documentId: String, content: String, metadata: Map[String, String], providedHash: Option[String]): IO[DocumentUpsertResponse] =
    upsertDocument(documentId, content, metadata, providedHash, None)

  /** Upsert a document, recording `sourceId` (when given) as its owner. */
  def upsertDocument(
    documentId: String,
    content: String,
    metadata: Map[String, String],
    providedHash: Option[String],
    sourceId: Option[String]
  ): IO[DocumentUpsertResponse] =
    documents.upsertDocument(documentId, content, metadata, providedHash, sourceId)

  def upsertSourceDocument(sourceId: String, documentId: String, content: String, metadata: Map[String, String]): IO[DocumentUpsertResponse] =
    documents.upsertSourceDocument(sourceId, documentId, content, metadata)

  def listSourceDocumentIds(sourceId: String): IO[Seq[String]] = documents.listSourceDocumentIds(sourceId)

  def pruneDocuments(keepDocumentIds: Set[String]): IO[Int] = documents.pruneDocuments(keepDocumentIds)

  /**
   * The documents a scoped sync would prune: those `inScope` that are not in `keepIds`. The
   * scope is checked on each orphan's registry entry, so a document written meanwhile outside the
   * scope (an upload, another source's) is never a candidate.
   */
  def findOrphanedDocuments(keepIds: Set[String], inScope: DocumentEntry => Boolean): IO[Seq[String]] =
    findOrphanedDocuments(keepIds).flatMap { orphans =>
      if (orphans.isEmpty) IO.pure(Seq.empty)
      else getDocumentEntries(orphans).map(_.filter(inScope).map(_.documentId))
    }

  /** Delete the documents [[findOrphanedDocuments]] finds; returns how many were deleted. */
  def pruneDocuments(keepIds: Set[String], inScope: DocumentEntry => Boolean): IO[Int] =
    findOrphanedDocuments(keepIds, inScope).flatMap { ids =>
      ids.toList.traverse(id => deleteDocument(id).attempt.map(_.isRight)).map(_.count(identity))
    }

  def ingestWithPermissions(
    collectionPath: String,
    documentId: String,
    content: String,
    metadata: Map[String, String] = Map.empty,
    readableBy: Set[PrincipalId] = Set.empty
  ): IO[DocumentUploadResponse] =
    documents.ingestWithPermissions(collectionPath, documentId, content, metadata, readableBy)

  def upsertWithPermissions(
    collectionPath: String,
    documentId: String,
    content: String,
    metadata: Map[String, String] = Map.empty,
    readableBy: Set[PrincipalId] = Set.empty,
    providedHash: Option[String] = None,
    sourceId: Option[String] = None
  ): IO[DocumentUpsertResponse] =
    documents.upsertWithPermissions(collectionPath, documentId, content, metadata, readableBy, providedHash, sourceId)

  def deleteFromCollection(collectionPath: String, documentId: String): IO[Long] =
    documents.deleteFromCollection(collectionPath, documentId)

  def ensureDefaultCollection: IO[Long] = documents.ensureDefaultCollection

  // ============================================================
  // Registry reads (sync API)
  // ============================================================

  def markSyncComplete(): IO[Unit] = documentRegistry.markSyncComplete()

  def listDocumentEntries: IO[Seq[DocumentEntry]] = documentRegistry.listEntries()

  /** A page of registry entries for the sync API (see `DocumentRegistry.listEntriesPage`). */
  def listDocumentEntriesPage(
    since: Option[java.time.Instant],
    collections: Option[Set[String]],
    offset: Int,
    limit: Int,
    source: Option[String] = None
  ): IO[DocumentEntryPage] =
    documentRegistry.listEntriesPage(since, collections, offset, limit, source)

  def getDocumentEntries(documentIds: Seq[String]): IO[Seq[DocumentEntry]] = documentRegistry.getMultiple(documentIds)

  def findOrphanedDocuments(keepIds: Set[String]): IO[Seq[String]] = documentRegistry.findOrphans(keepIds)

  def listCollections: IO[Seq[String]] = documentRegistry.listCollections()

  // ============================================================
  // Queries (QueryService)
  // ============================================================

  def search(query: String, topK: Option[Int]): IO[SearchResponse] = queries.search(query, topK)

  def queryWithAnswer(question: String, topK: Option[Int]): IO[QueryResponse] =
    queryWithAnswer(question, topK, RAGService.SettingsOverride.Empty)

  def queryWithAnswer(question: String, topK: Option[Int], overrides: RAGService.SettingsOverride): IO[QueryResponse] =
    queries.answer(question, None, Nil, topK, overrides)

  def searchWithPermissions(query: String, auth: UserAuthorization, collectionPattern: String, topK: Option[Int]): IO[SearchResponse] =
    queries.searchWithPermissions(query, auth, collectionPattern, topK)

  def queryWithPermissionsAndAnswer(
    question: String,
    auth: UserAuthorization,
    collectionPattern: String,
    topK: Option[Int],
    overrides: RAGService.SettingsOverride = RAGService.SettingsOverride.Empty
  ): IO[QueryResponse] =
    queries.answer(question, Some(auth -> collectionPattern), Nil, topK, overrides)

  /**
   * A chat reply: an answer with the session's earlier messages as conversation history, at the
   * runtime settings.
   */
  def chatAnswer(
    question: String,
    history: Seq[ChatTurn],
    permissions: Option[(UserAuthorization, String)],
    topK: Option[Int]
  ): IO[QueryResponse] =
    queries.answer(question, permissions, history, topK, RAGService.SettingsOverride.Empty)

  /**
   * An answer streamed as it is generated (see [[QueryService.answerStream]]): what [[answerStream]]
   * and [[chatStreamAs]] go through.
   */
  def streamAnswer(
    question: String,
    permissions: Option[(UserAuthorization, String)],
    history: Seq[ChatTurn],
    topK: Option[Int],
    overrides: RAGService.SettingsOverride
  ): Stream[IO, AnswerEvent] =
    queries.answerStream(question, permissions, history, topK, overrides)

  // ============================================================
  // Statistics and visibility (StatsService)
  // ============================================================

  def getStats: IO[StatsResponse] = stats.getStats

  def getCollectionStats: IO[Seq[org.llm4s.ragbox.model.CollectionStats]] = stats.getCollectionStats

  def getSyncStatus: IO[SyncStatusResponse] = getStats.flatMap(stats.syncStatus)

  def getDetailedStats: IO[DetailedStatsResponse] = getStats.flatMap(stats.detailedStats)

  def getCollectionDetails: IO[CollectionsResponse] = stats.collectionDetails

  def listChunks(page: Int, pageSize: Int): IO[ChunkListResponse] = stats.listChunks(page, pageSize)

  def getDocumentChunks(documentId: String): IO[Option[DocumentChunksResponse]] = stats.getDocumentChunks(documentId)

  def getChunk(documentId: String, index: Int): IO[Option[ChunkInfo]] = stats.getChunk(documentId, index)

  // ============================================================
  // Health and configuration (SystemInfoService)
  // ============================================================

  def getConfigInfo: IO[ConfigResponse] = system.configInfo

  def getDetailedConfig: IO[DetailedConfigResponse] = system.detailedConfig

  def getProviders: IO[ProvidersResponse] = system.providers

  def healthCheck: IO[HealthResponse] = system.healthCheck

  def readinessCheck: IO[ReadinessResponse] = system.readinessCheck

  /**
   * Close resources: the pipelines (each once the requests using it finish), the document
   * registry and the chunk store. Every one is closed even if closing another fails; the first
   * failure is re-raised with the others attached as suppressed exceptions.
   */
  def close(): IO[Unit] =
    List(pipelines.close, documentRegistry.close(), chunkStore.close())
      .traverse(_.attempt)
      .flatMap { results =>
        results.collect { case Left(e) => e } match {
          case Nil => IO.unit
          case first :: rest => IO(rest.foreach(first.addSuppressed)) *> IO.raiseError(first)
        }
      }
}

object RAGService {

  /**
   * The collection documents go to in permission mode when they name none. It is llm4s
   * `PgSchemaManager`'s default collection: public and a leaf.
   */
  val DefaultCollection: String = "default"

  /** Keyword table the pipeline uses in both modes (llm4s `PgConfig`'s default). */
  val KeywordTableName: String = Llm4sPipeline.KeywordTableName

  /** Minimum time between attempts to build the RAG instance after a failure. */
  val RetryCooldownMillis: Long = 5000L

  /** The fusion strategies a query can be given. */
  val FusionStrategies: Seq[String] = Seq("rrf", "weighted", "vector_only", "keyword_only")

  /**
   * Settings a query runs with instead of the runtime settings' (an experiment group's or a
   * request's overrides). topK is not here: it is passed with each call.
   *
   * @param reranking           `none`, `cohere` or `llm`
   * @param similarityThreshold minimum vector similarity (0-1) of a retrieved chunk for it to be
   *                            passed to the LLM and returned (see [[QueryService.aboveThreshold]]).
   *                            Applied per query; it does not select a different pipeline.
   * @param llmTemperature      the temperature the answer is generated at (0 to 2), passed with
   *                            the call as its completion options; it selects no pipeline either
   */
  final case class SettingsOverride(
    fusionStrategy: Option[String] = None,
    rrfK: Option[Int] = None,
    systemPrompt: Option[String] = None,
    reranking: Option[String] = None,
    similarityThreshold: Option[Double] = None,
    llmTemperature: Option[Double] = None
  )

  object SettingsOverride {
    val Empty: SettingsOverride = SettingsOverride()
  }

  /**
   * How many pipelines are kept at once: the current settings' and, while an experiment runs,
   * its variants'. Each holds its own store connections, so the least recently used one beyond
   * this is closed.
   */
  val MaxPipelines: Int = 4

  /**
   * The name a document's file-type chunking override is looked up by: its file name, else the
   * path or source llm4s's loaders record, else its id.
   */
  def sourceName(documentId: String, metadata: Map[String, String]): Option[String] =
    metadata.get("filename").orElse(metadata.get("path")).orElse(metadata.get("source")).orElse(Some(documentId))

  /**
   * The llm4s chunking config for an effective collection config. Sizes that validation rejects
   * when a config is saved can still arise later - a collection that sets only `maxSize`, after
   * the runtime chunk size grew past it - so they are brought within llm4s's bounds here rather
   * than failing the ingest.
   */
  def chunkingConfig(effective: EffectiveCollectionConfig): ChunkingConfig = {
    val target = math.max(1, effective.targetSize)
    ChunkingConfig(
      targetSize = target,
      maxSize = math.max(effective.maxSize, target),
      overlap = math.max(0, math.min(effective.overlap, target - 1))
    )
  }

  /** How long a provider's live model list is cached by [[RAGService.getProviders]]. */
  val ModelListTtlMillis: Long = 10 * 60 * 1000L

  /**
   * Resolve external principal ids (`user:alice@example.com`, `group:managers`) to llm4s
   * principal ids, creating them as needed. A malformed id is an [[InvalidRequestError]].
   */
  def resolvePrincipals(externalIds: Seq[String], principals: PrincipalStore): IO[Set[PrincipalId]] =
    externalIds.toList.traverse { extId =>
      ExternalPrincipal.parse(extId) match {
        case Left(e) => IO.raiseError(InvalidRequestError(s"Invalid principal '$extId': ${e.message}"))
        case Right(ext) => RagPipelines.blockingResult(principals.getOrCreate(ext))
      }
    }.map(_.toSet)

  /**
   * Create a new RAGService with PostgreSQL-backed document registry and chunk store over the
   * shared connection pool. Does not enable permission-based RAG.
   */
  def create(
    config: AppConfig,
    dataSource: DataSource,
    providerRegistry: ProviderRegistry = ProviderRegistry.default,
    runtimeSettings: Option[RuntimeSettings] = None,
    collectionConfigs: Option[CollectionConfigRegistry] = None
  ): IO[RAGService] =
    for {
      registry <- PgDocumentRegistry(dataSource)
      chunks <- PgChunkStore(dataSource)
      service = new RAGService(config, registry, chunks, None, providerRegistry, Some(dataSource), runtimeSettings, collectionConfigs)
      _ <- warmUp(service)
    } yield service

  /**
   * Create a new RAGService with permission-based RAG enabled.
   * Requires a SearchIndex to be created and initialized first.
   *
   * @param config Application configuration
   * @param dataSource The shared connection pool
   * @param searchIndex Pre-configured SearchIndex for permission-aware operations
   * @param runtimeSettings the runtime-modifiable settings (see the class description)
   * @param collectionConfigs per-collection chunking configs
   */
  def createWithPermissions(
    config: AppConfig,
    dataSource: DataSource,
    searchIndex: SearchIndex,
    providerRegistry: ProviderRegistry = ProviderRegistry.default,
    runtimeSettings: Option[RuntimeSettings] = None,
    collectionConfigs: Option[CollectionConfigRegistry] = None
  ): IO[RAGService] =
    for {
      registry <- PgDocumentRegistry(dataSource)
      chunks <- PgChunkStore(dataSource)
      service = new RAGService(config, registry, chunks, Some(searchIndex), providerRegistry, Some(dataSource), runtimeSettings, collectionConfigs)
      // Every ingest in permission mode needs the default collection, and vectors left without
      // a collection by earlier versions are unsearchable until migrated into it.
      migrated <- service.ensureDefaultCollection
      _ <- IO.whenA(migrated > 0)(
        IO(logger.info(s"Migrated $migrated vectors without a collection into '$DefaultCollection'"))
      )
      _ <- warmUp(service)
    } yield service

  /**
   * Build the RAG instance eagerly, so a misconfiguration shows at startup. A failure is logged
   * rather than raised: the instance is retried on use (see [[RAGService.rag]]) and reported by
   * the readiness check meanwhile.
   */
  private def warmUp(service: RAGService): IO[Unit] =
    service.rag.attempt.flatMap {
      case Right(_) => IO.unit
      case Left(e) =>
        IO(logger.warn(s"RAG pipeline not available yet (will retry on use): ${e.getMessage}"))
    }

  private val logger = org.slf4j.LoggerFactory.getLogger(classOf[RAGService])
}

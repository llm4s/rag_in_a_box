package org.llm4s.ragbox.service

import cats.effect.IO
import cats.syntax.all._
import org.llm4s.ragbox.config.RuntimeSettings
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.DocumentRegistry
import org.llm4s.ragbox.service.RagPipelines.blockingResult
import org.llm4s.ragbox.store.{ChunkStore, CollectionChunkStats}

/**
 * Reading what is indexed: statistics, per-collection figures, chunks (the visibility API) and
 * sync status. Collection statistics take two grouped queries (documents and chunks per
 * collection), however many collections there are.
 */
final class StatsService(pipelines: RagPipelines, documentRegistry: DocumentRegistry, chunkStore: ChunkStore) {

  /** Document, chunk and vector counts, and each collection's document and chunk counts. */
  def getStats: IO[StatsResponse] =
    for {
      stats <- pipelines.withCurrent(r => blockingResult(r.stats))
      docCount <- documentRegistry.count()
      chunkCount <- chunkStore.count()
      collections <- getCollectionStats
    } yield StatsResponse(documentCount = docCount, chunkCount = chunkCount, vectorCount = stats.vectorCount, collections = collections)

  /** Document and chunk count of each collection ("default": documents without one). */
  def getCollectionStats: IO[Seq[CollectionStats]] =
    collectionFigures.map(_.map { case (name, docs, chunks) =>
      CollectionStats(name = name, documentCount = docs, chunkCount = chunks.fold(0)(_.chunkCount))
    })

  /** Sync status, from the registry and `stats`. */
  def syncStatus(stats: StatsResponse): IO[SyncStatusResponse] =
    (documentRegistry.getSyncInfo(), documentRegistry.count()).mapN { (syncInfo, docCount) =>
      SyncStatusResponse(lastSyncTime = syncInfo.lastSyncTime, documentCount = docCount, chunkCount = stats.chunkCount)
    }

  /**
   * Detailed statistics, over the basic `stats`. The ingestion times are the registry's: when
   * the earliest current document was indexed and the latest updated.
   */
  def detailedStats(stats: StatsResponse): IO[DetailedStatsResponse] =
    for {
      distribution <- chunkStore.getChunkSizeDistribution()
      collections <- detailedCollectionStats
      chunkCount <- chunkStore.count()
      times <- documentRegistry.ingestionTimes()
    } yield DetailedStatsResponse(
      documentCount = stats.documentCount,
      chunkCount = chunkCount,
      vectorCount = stats.vectorCount,
      collectionCount = collections.size,
      avgChunksPerDocument = if (stats.documentCount > 0) chunkCount.toDouble / stats.documentCount else 0.0,
      collections = collections,
      chunkSizeDistribution = distribution,
      indexedSince = times._1,
      lastIngestion = times._2,
      currentConfig = currentChunkConfig
    )

  /** Each collection's documents, chunks and average chunk size. */
  def detailedCollectionStats: IO[Seq[DetailedCollectionStats]] =
    collectionFigures.map(_.map { case (name, docs, chunks) =>
      val chunkCount = chunks.fold(0)(_.chunkCount)
      DetailedCollectionStats(
        name = name,
        documentCount = docs,
        chunkCount = chunkCount,
        avgChunksPerDoc = if (docs > 0) chunkCount.toDouble / docs else 0.0,
        avgChunkSize = chunks.fold(0.0)(_.avgChunkSize),
        chunkingStrategy = None
      )
    })

  /** Collection details with the chunking that applies (the runtime settings'). */
  def collectionDetails: IO[CollectionsResponse] =
    detailedCollectionStats.map { stats =>
      val chunking = currentChunkConfig
      val defaultConfig = EffectiveChunkingInfo(
        strategy = chunking.strategy,
        targetSize = chunking.targetSize,
        maxSize = chunking.maxSize,
        overlap = chunking.overlap,
        source = "default"
      )
      CollectionsResponse(
        collections = stats.map(s => CollectionDetailInfo(s.name, s.documentCount, s.chunkCount, customConfig = None, effectiveConfig = defaultConfig)),
        defaultConfig = defaultConfig
      )
    }

  /** All chunks, a page at a time. */
  def listChunks(page: Int, pageSize: Int): IO[ChunkListResponse] =
    chunkStore.listChunks(page, pageSize)

  /** A document's chunks, if the document or any chunk of it exists. */
  def getDocumentChunks(documentId: String): IO[Option[DocumentChunksResponse]] =
    (documentRegistry.get(documentId), chunkStore.getChunks(documentId)).mapN { (entry, chunks) =>
      Option.when(chunks.nonEmpty || entry.isDefined)(DocumentChunksResponse(
        documentId = documentId,
        filename = entry.flatMap(_.metadata.get("filename")),
        collection = entry.flatMap(_.collection),
        chunkCount = chunks.size,
        chunks = chunks,
        chunkingConfig = currentChunkConfig
      ))
    }

  def getChunk(documentId: String, index: Int): IO[Option[ChunkInfo]] =
    chunkStore.getChunk(documentId, index)

  /** The runtime chunking settings, as the visibility API shows them. */
  private def currentChunkConfig: ChunkConfigSnapshot = {
    val current = pipelines.current
    ChunkConfigSnapshot(
      strategy = current.chunkingStrategy,
      targetSize = current.chunkSize,
      maxSize = RuntimeSettings.defaultMaxSize(current.chunkSize),
      overlap = current.chunkOverlap
    )
  }

  /**
   * (collection, documents, chunk figures) for every collection with documents: "default" first,
   * then by name. Two grouped queries.
   */
  private def collectionFigures: IO[Seq[(String, Int, Option[CollectionChunkStats])]] =
    (documentRegistry.countsByCollection(), chunkStore.statsByCollection()).mapN { (docs, chunks) =>
      docs.toSeq
        .sortBy { case (name, _) => (name != RAGService.DefaultCollection, name) }
        .map { case (name, count) => (name, count, chunks.get(name)) }
    }
}

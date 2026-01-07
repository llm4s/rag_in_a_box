package org.llm4s.ragbox.routes

import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.llm4s.ragbox.service.RAGService

import java.lang.management.ManagementFactory

/**
 * Prometheus-compatible metrics endpoint.
 *
 * Exposes metrics in Prometheus text format at /metrics.
 * Includes RAG stats, JVM metrics, and system info.
 */
object MetricsRoutes {

  private val startTime: Long = System.currentTimeMillis()

  def routes(ragService: RAGService): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // GET /metrics - Prometheus metrics
    case GET -> Root / "metrics" =>
      for {
        stats <- ragService.getStats.attempt
        readiness <- ragService.readinessCheck.attempt
        metrics = buildMetrics(stats, readiness)
        response <- Ok(metrics).map(_.withContentType(
          headers.`Content-Type`(MediaType.text.plain)
        ))
      } yield response
  }

  private def buildMetrics(
    statsResult: Either[Throwable, org.llm4s.ragbox.model.StatsResponse],
    readinessResult: Either[Throwable, org.llm4s.ragbox.model.ReadinessResponse]
  ): String = {
    val uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000.0

    val lines = scala.collection.mutable.ArrayBuffer.empty[String]

    // ============================================================
    // Service Info Metrics
    // ============================================================
    lines += "# HELP ragbox_uptime_seconds Time since service start"
    lines += "# TYPE ragbox_uptime_seconds gauge"
    lines += s"ragbox_uptime_seconds $uptimeSeconds"

    lines += ""
    lines += "# HELP ragbox_info RAG in a Box version information"
    lines += "# TYPE ragbox_info gauge"
    lines += """ragbox_info{version="0.1.0"} 1"""

    // ============================================================
    // RAG Stats Metrics
    // ============================================================
    statsResult match {
      case Right(stats) =>
        lines += ""
        lines += "# HELP ragbox_documents_total Total number of documents indexed"
        lines += "# TYPE ragbox_documents_total gauge"
        lines += s"ragbox_documents_total ${stats.documentCount}"

        lines += ""
        lines += "# HELP ragbox_chunks_total Total number of chunks indexed"
        lines += "# TYPE ragbox_chunks_total gauge"
        lines += s"ragbox_chunks_total ${stats.chunkCount}"

        lines += ""
        lines += "# HELP ragbox_vectors_total Total number of vectors in store"
        lines += "# TYPE ragbox_vectors_total gauge"
        lines += s"ragbox_vectors_total ${stats.vectorCount}"

        lines += ""
        lines += "# HELP ragbox_collections_total Number of collections"
        lines += "# TYPE ragbox_collections_total gauge"
        lines += s"ragbox_collections_total ${stats.collections.size}"

        // Per-collection metrics
        if (stats.collections.nonEmpty) {
          lines += ""
          lines += "# HELP ragbox_collection_documents Documents per collection"
          lines += "# TYPE ragbox_collection_documents gauge"
          stats.collections.foreach { coll =>
            lines += s"""ragbox_collection_documents{collection="${escapeLabel(coll.name)}"} ${coll.documentCount}"""
          }

          lines += ""
          lines += "# HELP ragbox_collection_chunks Chunks per collection"
          lines += "# TYPE ragbox_collection_chunks gauge"
          stats.collections.foreach { coll =>
            lines += s"""ragbox_collection_chunks{collection="${escapeLabel(coll.name)}"} ${coll.chunkCount}"""
          }
        }

      case Left(_) =>
        lines += ""
        lines += "# HELP ragbox_stats_available Whether stats are available"
        lines += "# TYPE ragbox_stats_available gauge"
        lines += "ragbox_stats_available 0"
    }

    // ============================================================
    // Health/Readiness Metrics
    // ============================================================
    lines += ""
    lines += "# HELP ragbox_healthy Whether the service is healthy"
    lines += "# TYPE ragbox_healthy gauge"
    lines += s"ragbox_healthy ${if (statsResult.isRight) 1 else 0}"

    lines += ""
    lines += "# HELP ragbox_ready Whether the service is ready"
    lines += "# TYPE ragbox_ready gauge"
    val isReady = readinessResult.toOption.exists(_.ready)
    lines += s"ragbox_ready ${if (isReady) 1 else 0}"

    // Per-component readiness
    readinessResult match {
      case Right(readiness) =>
        lines += ""
        lines += "# HELP ragbox_component_status Status of each component (1=ok, 0=failed)"
        lines += "# TYPE ragbox_component_status gauge"
        readiness.checks.foreach { case (name, status) =>
          val statusValue = if (status.status == "ok") 1 else 0
          lines += s"""ragbox_component_status{component="${escapeLabel(name)}"} $statusValue"""
        }
      case Left(_) =>
        // Readiness check failed
        lines += ""
        lines += "# HELP ragbox_component_status Status of each component (1=ok, 0=failed)"
        lines += "# TYPE ragbox_component_status gauge"
        lines += """ragbox_component_status{component="readiness_check"} 0"""
    }

    // ============================================================
    // JVM Memory Metrics
    // ============================================================
    val runtime = Runtime.getRuntime
    val memoryMXBean = ManagementFactory.getMemoryMXBean
    val heapUsage = memoryMXBean.getHeapMemoryUsage
    val nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage

    lines += ""
    lines += "# HELP ragbox_jvm_memory_used_bytes JVM memory currently used"
    lines += "# TYPE ragbox_jvm_memory_used_bytes gauge"
    lines += s"""ragbox_jvm_memory_used_bytes{area="heap"} ${heapUsage.getUsed}"""
    lines += s"""ragbox_jvm_memory_used_bytes{area="nonheap"} ${nonHeapUsage.getUsed}"""

    lines += ""
    lines += "# HELP ragbox_jvm_memory_max_bytes JVM maximum memory"
    lines += "# TYPE ragbox_jvm_memory_max_bytes gauge"
    lines += s"""ragbox_jvm_memory_max_bytes{area="heap"} ${heapUsage.getMax}"""

    lines += ""
    lines += "# HELP ragbox_jvm_memory_committed_bytes JVM committed memory"
    lines += "# TYPE ragbox_jvm_memory_committed_bytes gauge"
    lines += s"""ragbox_jvm_memory_committed_bytes{area="heap"} ${heapUsage.getCommitted}"""
    lines += s"""ragbox_jvm_memory_committed_bytes{area="nonheap"} ${nonHeapUsage.getCommitted}"""

    // ============================================================
    // JVM Thread Metrics
    // ============================================================
    val threadMXBean = ManagementFactory.getThreadMXBean

    lines += ""
    lines += "# HELP ragbox_jvm_threads_current Current thread count"
    lines += "# TYPE ragbox_jvm_threads_current gauge"
    lines += s"ragbox_jvm_threads_current ${threadMXBean.getThreadCount}"

    lines += ""
    lines += "# HELP ragbox_jvm_threads_daemon Daemon thread count"
    lines += "# TYPE ragbox_jvm_threads_daemon gauge"
    lines += s"ragbox_jvm_threads_daemon ${threadMXBean.getDaemonThreadCount}"

    lines += ""
    lines += "# HELP ragbox_jvm_threads_peak Peak thread count"
    lines += "# TYPE ragbox_jvm_threads_peak gauge"
    lines += s"ragbox_jvm_threads_peak ${threadMXBean.getPeakThreadCount}"

    // ============================================================
    // JVM GC Metrics
    // ============================================================
    val gcBeans = ManagementFactory.getGarbageCollectorMXBeans
    if (!gcBeans.isEmpty) {
      lines += ""
      lines += "# HELP ragbox_jvm_gc_collection_seconds_total Total GC collection time"
      lines += "# TYPE ragbox_jvm_gc_collection_seconds_total counter"
      gcBeans.forEach { gc =>
        val timeSeconds = gc.getCollectionTime / 1000.0
        lines += s"""ragbox_jvm_gc_collection_seconds_total{gc="${escapeLabel(gc.getName)}"} $timeSeconds"""
      }

      lines += ""
      lines += "# HELP ragbox_jvm_gc_collection_count_total Total GC collection count"
      lines += "# TYPE ragbox_jvm_gc_collection_count_total counter"
      gcBeans.forEach { gc =>
        lines += s"""ragbox_jvm_gc_collection_count_total{gc="${escapeLabel(gc.getName)}"} ${gc.getCollectionCount}"""
      }
    }

    // ============================================================
    // System Metrics
    // ============================================================
    lines += ""
    lines += "# HELP ragbox_jvm_available_processors Available CPU cores"
    lines += "# TYPE ragbox_jvm_available_processors gauge"
    lines += s"ragbox_jvm_available_processors ${runtime.availableProcessors()}"

    lines.mkString("\n") + "\n"
  }

  /**
   * Escape label values for Prometheus format.
   */
  private def escapeLabel(value: String): String =
    value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
}

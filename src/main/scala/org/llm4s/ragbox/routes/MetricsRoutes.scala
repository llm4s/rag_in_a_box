package org.llm4s.ragbox.routes

import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.llm4s.ragbox.service.RAGService

/**
 * Prometheus-compatible metrics endpoint.
 *
 * Exposes metrics in Prometheus text format at /metrics.
 */
object MetricsRoutes {

  private val startTime: Long = System.currentTimeMillis()

  def routes(ragService: RAGService): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // GET /metrics - Prometheus metrics
    case GET -> Root / "metrics" =>
      for {
        stats <- ragService.getStats.attempt
        metrics = buildMetrics(stats)
        response <- Ok(metrics).map(_.withContentType(
          headers.`Content-Type`(MediaType.text.plain)
        ))
      } yield response
  }

  private def buildMetrics(statsResult: Either[Throwable, org.llm4s.ragbox.model.StatsResponse]): String = {
    val uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000.0

    val lines = scala.collection.mutable.ArrayBuffer.empty[String]

    // HELP and TYPE comments for Prometheus
    lines += "# HELP ragbox_uptime_seconds Time since service start"
    lines += "# TYPE ragbox_uptime_seconds gauge"
    lines += s"ragbox_uptime_seconds $uptimeSeconds"

    lines += ""
    lines += "# HELP ragbox_info RAG in a Box version information"
    lines += "# TYPE ragbox_info gauge"
    lines += """ragbox_info{version="0.1.0"} 1"""

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
        }

      case Left(e) =>
        lines += ""
        lines += "# HELP ragbox_healthy Whether the service is healthy"
        lines += "# TYPE ragbox_healthy gauge"
        lines += "ragbox_healthy 0"
    }

    lines += ""
    lines += "# HELP ragbox_healthy Whether the service is healthy"
    lines += "# TYPE ragbox_healthy gauge"
    lines += s"ragbox_healthy ${if (statsResult.isRight) 1 else 0}"

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

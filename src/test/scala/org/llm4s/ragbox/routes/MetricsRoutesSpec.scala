package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.ragbox.testkit.MockRAGService

class MetricsRoutesSpec extends AnyFlatSpec with Matchers {

  private val ragService = MockRAGService()
  private val routes = MetricsRoutes.routes(ragService)

  private def runRequest(request: Request[IO]): Response[IO] =
    routes.orNotFound.run(request).unsafeRunSync()

  "GET /metrics" should "return Prometheus-formatted metrics" in {
    val request = Request[IO](Method.GET, uri"/metrics")

    val response = runRequest(request)
    response.status shouldBe Status.Ok
    response.contentType.map(_.mediaType) shouldBe Some(MediaType.text.plain)
  }

  it should "include uptime metric" in {
    val request = Request[IO](Method.GET, uri"/metrics")
    val body = runRequest(request).as[String].unsafeRunSync()

    body should include("ragbox_uptime_seconds")
    body should include("# HELP ragbox_uptime_seconds")
    body should include("# TYPE ragbox_uptime_seconds gauge")
  }

  it should "include version info metric" in {
    val request = Request[IO](Method.GET, uri"/metrics")
    val body = runRequest(request).as[String].unsafeRunSync()

    body should include("ragbox_info")
    body should include("version=")
  }

  it should "include document count metrics" in {
    val request = Request[IO](Method.GET, uri"/metrics")
    val body = runRequest(request).as[String].unsafeRunSync()

    body should include("ragbox_documents_total")
    body should include("ragbox_chunks_total")
    body should include("ragbox_vectors_total")
  }

  it should "include health metrics" in {
    val request = Request[IO](Method.GET, uri"/metrics")
    val body = runRequest(request).as[String].unsafeRunSync()

    body should include("ragbox_healthy")
    body should include("ragbox_ready")
  }

  it should "include JVM memory metrics" in {
    val request = Request[IO](Method.GET, uri"/metrics")
    val body = runRequest(request).as[String].unsafeRunSync()

    body should include("ragbox_jvm_memory_used_bytes")
    body should include("""area="heap"""")
    body should include("""area="nonheap"""")
  }

  it should "include JVM thread metrics" in {
    val request = Request[IO](Method.GET, uri"/metrics")
    val body = runRequest(request).as[String].unsafeRunSync()

    body should include("ragbox_jvm_threads_current")
    body should include("ragbox_jvm_threads_daemon")
    body should include("ragbox_jvm_threads_peak")
  }

  it should "include GC metrics" in {
    val request = Request[IO](Method.GET, uri"/metrics")
    val body = runRequest(request).as[String].unsafeRunSync()

    body should include("ragbox_jvm_gc_collection_seconds_total")
    body should include("ragbox_jvm_gc_collection_count_total")
  }

  it should "include processor count metric" in {
    val request = Request[IO](Method.GET, uri"/metrics")
    val body = runRequest(request).as[String].unsafeRunSync()

    body should include("ragbox_jvm_available_processors")
  }

  it should "include component status metrics" in {
    val request = Request[IO](Method.GET, uri"/metrics")
    val body = runRequest(request).as[String].unsafeRunSync()

    body should include("ragbox_component_status")
  }
}

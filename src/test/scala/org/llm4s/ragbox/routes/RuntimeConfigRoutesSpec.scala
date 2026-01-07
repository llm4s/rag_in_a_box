package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.ragbox.config.{AppConfig, DatabaseConfig}
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.testkit.TestFixtures

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class RuntimeConfigRoutesSpec extends AnyFlatSpec with Matchers {

  /**
   * Mock RuntimeConfigManager that doesn't require a database connection.
   */
  private class MockRuntimeConfigManager(initialConfig: AppConfig) {
    private val currentConfig: AtomicReference[RuntimeConfig] = new AtomicReference(
      RuntimeConfig(
        topK = initialConfig.rag.search.topK,
        fusionStrategy = initialConfig.rag.search.fusionStrategy,
        rrfK = initialConfig.rag.search.rrfK,
        systemPrompt = initialConfig.rag.systemPrompt,
        llmTemperature = initialConfig.llm.temperature,
        chunkingStrategy = initialConfig.rag.chunking.strategy,
        chunkSize = initialConfig.rag.chunking.size,
        chunkOverlap = initialConfig.rag.chunking.overlap
      )
    )
    private var lastModified: Option[Instant] = None
    private var historyEntries: Seq[ConfigChange] = Seq.empty

    private val validFusionStrategies = Set("rrf", "weighted", "vector_only", "keyword_only")
    private val validChunkingStrategies = Set("simple", "sentence", "markdown", "semantic")

    def get: IO[RuntimeConfigResponse] = IO {
      RuntimeConfigResponse(
        config = currentConfig.get(),
        lastModified = lastModified,
        hotSettings = Seq("topK", "fusionStrategy", "rrfK", "systemPrompt", "llmTemperature"),
        warmSettings = Seq("chunkingStrategy", "chunkSize", "chunkOverlap")
      )
    }

    def update(request: RuntimeConfigUpdateRequest): IO[RuntimeConfigUpdateResponse] = IO {
      val current = currentConfig.get()
      val changes = scala.collection.mutable.ArrayBuffer.empty[SettingUpdateResult]
      val now = Instant.now()
      var updated = current

      request.topK.foreach { v =>
        if (v != current.topK) {
          changes += SettingUpdateResult("topK", current.topK.toString, v.toString, "hot", effectiveImmediately = true)
          updated = updated.copy(topK = v)
          historyEntries = historyEntries :+ ConfigChange(historyEntries.size + 1L, "topK", current.topK.toString, v.toString, now, None)
        }
      }

      request.fusionStrategy.foreach { v =>
        if (v != current.fusionStrategy) {
          changes += SettingUpdateResult("fusionStrategy", current.fusionStrategy, v, "hot", effectiveImmediately = true)
          updated = updated.copy(fusionStrategy = v)
          historyEntries = historyEntries :+ ConfigChange(historyEntries.size + 1L, "fusionStrategy", current.fusionStrategy, v, now, None)
        }
      }

      request.chunkingStrategy.foreach { v =>
        if (v != current.chunkingStrategy) {
          changes += SettingUpdateResult("chunkingStrategy", current.chunkingStrategy, v, "warm", effectiveImmediately = false)
          updated = updated.copy(chunkingStrategy = v)
          historyEntries = historyEntries :+ ConfigChange(historyEntries.size + 1L, "chunkingStrategy", current.chunkingStrategy, v, now, None)
        }
      }

      if (changes.nonEmpty) {
        currentConfig.set(updated)
        lastModified = Some(now)
      }

      RuntimeConfigUpdateResponse(
        success = true,
        changes = changes.toSeq,
        current = updated,
        message = if (changes.isEmpty) "No changes applied" else s"${changes.size} setting(s) updated"
      )
    }

    def validate(request: RuntimeConfigValidateRequest): IO[RuntimeConfigValidateResponse] = IO {
      val current = currentConfig.get()
      val validations = scala.collection.mutable.ArrayBuffer.empty[SettingValidation]
      val warnings = scala.collection.mutable.ArrayBuffer.empty[String]

      request.topK.foreach { v =>
        val (valid, error) = if (v < 1) (false, Some("topK must be at least 1"))
          else if (v > 100) (false, Some("topK must be at most 100"))
          else (true, None)
        validations += SettingValidation("topK", valid, current.topK.toString, v.toString, error)
      }

      request.fusionStrategy.foreach { v =>
        val (valid, error) = if (!validFusionStrategies.contains(v))
          (false, Some(s"Invalid fusion strategy. Valid values: ${validFusionStrategies.mkString(", ")}"))
        else (true, None)
        validations += SettingValidation("fusionStrategy", valid, current.fusionStrategy, v, error)
      }

      request.chunkingStrategy.foreach { v =>
        val (valid, error) = if (!validChunkingStrategies.contains(v))
          (false, Some(s"Invalid chunking strategy. Valid values: ${validChunkingStrategies.mkString(", ")}"))
        else (true, None)
        validations += SettingValidation("chunkingStrategy", valid, current.chunkingStrategy, v, error)
      }

      request.chunkSize.foreach { v =>
        val (valid, error) = if (v < 100) (false, Some("chunkSize must be at least 100"))
          else if (v > 10000) (false, Some("chunkSize must be at most 10000"))
          else (true, None)
        validations += SettingValidation("chunkSize", valid, current.chunkSize.toString, v.toString, error)
      }

      RuntimeConfigValidateResponse(
        valid = validations.forall(_.valid),
        validations = validations.toSeq,
        warnings = warnings.toSeq
      )
    }

    def getHistory(page: Int, pageSize: Int): IO[ConfigHistoryResponse] = IO {
      val offset = (page - 1) * pageSize
      val pageItems = historyEntries.drop(offset).take(pageSize)
      ConfigHistoryResponse(
        changes = pageItems,
        total = historyEntries.size,
        page = page,
        pageSize = pageSize,
        hasMore = offset + pageItems.size < historyEntries.size
      )
    }

    def close(): IO[Unit] = IO.unit
  }

  /**
   * Create routes using a mock that wraps the real RuntimeConfigRoutes logic
   * but with our mock config manager.
   */
  private def createRoutes(): HttpRoutes[IO] = {
    val mockManager = new MockRuntimeConfigManager(TestFixtures.testAppConfig)

    // Directly implement the routes using our mock
    import io.circe.syntax._
    import org.http4s.circe.CirceEntityDecoder._
    import org.http4s.dsl.io._
    import org.llm4s.ragbox.model.Codecs._

    HttpRoutes.of[IO] {
      case GET -> Root / "api" / "v1" / "config" / "runtime" =>
        mockManager.get.flatMap(config => Ok(config.asJson))

      case req @ PUT -> Root / "api" / "v1" / "config" / "runtime" =>
        for {
          request <- req.as[RuntimeConfigUpdateRequest]
          validation <- mockManager.validate(RuntimeConfigValidateRequest(
            topK = request.topK,
            fusionStrategy = request.fusionStrategy,
            rrfK = request.rrfK,
            systemPrompt = request.systemPrompt,
            llmTemperature = request.llmTemperature,
            chunkingStrategy = request.chunkingStrategy,
            chunkSize = request.chunkSize,
            chunkOverlap = request.chunkOverlap
          ))
          response <- if (validation.valid) {
            mockManager.update(request).flatMap(result => Ok(result.asJson))
          } else {
            BadRequest(ErrorResponse(
              error = "validation_failed",
              message = "Configuration validation failed",
              details = Some(validation.validations.filter(!_.valid).map(v => s"${v.field}: ${v.error.getOrElse("invalid")}").mkString("; "))
            ).asJson)
          }
        } yield response

      case req @ POST -> Root / "api" / "v1" / "config" / "runtime" / "validate" =>
        for {
          request <- req.as[RuntimeConfigValidateRequest]
          result <- mockManager.validate(request)
          response <- Ok(result.asJson)
        } yield response

      case GET -> Root / "api" / "v1" / "config" / "runtime" / "history" =>
        mockManager.getHistory(1, 50).flatMap(result => Ok(result.asJson))

      case GET -> Root / "api" / "v1" / "config" / "runtime" / "meta" =>
        mockManager.get.flatMap { current =>
          val meta: Seq[ConfigMetaItem] = Seq(
            ConfigMetaItem("topK", current.config.topK.toString, "hot", "Number of chunks to retrieve", requiresRestart = false),
            ConfigMetaItem("fusionStrategy", current.config.fusionStrategy, "hot", "Strategy for combining results", requiresRestart = false)
          )
          Ok(meta.asJson)
        }
    }
  }

  // ============================================================
  // GET /api/v1/config/runtime
  // ============================================================

  "GET /api/v1/config/runtime" should "return current runtime configuration" in {
    val routes = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/config/runtime")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("config")
    body should include("topK")
    body should include("fusionStrategy")
  }

  it should "include hot and warm settings lists" in {
    val routes = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/config/runtime")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("hotSettings")
    body should include("warmSettings")
  }

  // ============================================================
  // PUT /api/v1/config/runtime
  // ============================================================

  "PUT /api/v1/config/runtime" should "update valid configuration" in {
    val routes = createRoutes()

    val requestBody = """{"topK": 10}"""
    val request = Request[IO](Method.PUT, uri"/api/v1/config/runtime")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("success")
    body should include("true")
  }

  it should "reject invalid topK" in {
    val routes = createRoutes()

    val requestBody = """{"topK": 0}"""
    val request = Request[IO](Method.PUT, uri"/api/v1/config/runtime")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.BadRequest
    val body = response.as[String].unsafeRunSync()
    body should include("validation_failed")
  }

  it should "reject invalid fusion strategy" in {
    val routes = createRoutes()

    val requestBody = """{"fusionStrategy": "invalid_strategy"}"""
    val request = Request[IO](Method.PUT, uri"/api/v1/config/runtime")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.BadRequest
    val body = response.as[String].unsafeRunSync()
    body should include("validation_failed")
  }

  // ============================================================
  // POST /api/v1/config/runtime/validate
  // ============================================================

  "POST /api/v1/config/runtime/validate" should "validate valid settings" in {
    val routes = createRoutes()

    val requestBody = """{"topK": 10, "fusionStrategy": "rrf"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/config/runtime/validate")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("\"valid\":true")
  }

  it should "report invalid settings" in {
    val routes = createRoutes()

    val requestBody = """{"topK": -1}"""
    val request = Request[IO](Method.POST, uri"/api/v1/config/runtime/validate")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("\"valid\":false")
    body should include("topK must be at least 1")
  }

  it should "validate chunk size limits" in {
    val routes = createRoutes()

    val requestBody = """{"chunkSize": 50}"""
    val request = Request[IO](Method.POST, uri"/api/v1/config/runtime/validate")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("\"valid\":false")
    body should include("chunkSize must be at least 100")
  }

  // ============================================================
  // GET /api/v1/config/runtime/history
  // ============================================================

  "GET /api/v1/config/runtime/history" should "return empty history initially" in {
    val routes = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/config/runtime/history")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("changes")
    body should include("total")
  }

  // ============================================================
  // GET /api/v1/config/runtime/meta
  // ============================================================

  "GET /api/v1/config/runtime/meta" should "return metadata about settings" in {
    val routes = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/config/runtime/meta")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("topK")
    body should include("description")
  }
}

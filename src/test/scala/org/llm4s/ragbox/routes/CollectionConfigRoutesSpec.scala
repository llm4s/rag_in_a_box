package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.ragbox.config.AppConfig
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.{CollectionConfigRegistry, DocumentEntry, DocumentRegistry, SyncInfo}
import org.llm4s.ragbox.testkit.TestFixtures

import java.time.Instant

class CollectionConfigRoutesSpec extends AnyFlatSpec with Matchers {

  /**
   * Mock CollectionConfigRegistry for testing.
   */
  private class MockCollectionConfigRegistry extends CollectionConfigRegistry {
    private var configs: Map[String, CollectionChunkingConfig] = Map.empty

    override def initialize(): IO[Unit] = IO.unit

    override def get(collection: String): IO[Option[CollectionChunkingConfig]] =
      IO.pure(configs.get(collection))

    override def put(config: CollectionChunkingConfig): IO[Unit] = IO {
      configs = configs + (config.collection -> config)
    }

    override def delete(collection: String): IO[Boolean] = IO {
      val existed = configs.contains(collection)
      configs = configs - collection
      existed
    }

    override def listConfigured(): IO[Seq[String]] = IO.pure(configs.keys.toSeq)

    override def getAll(): IO[Seq[CollectionChunkingConfig]] = IO.pure(configs.values.toSeq)

    override def getEffective(
      collection: String,
      filename: Option[String],
      defaultStrategy: String,
      defaultTargetSize: Int,
      defaultMaxSize: Int,
      defaultOverlap: Int
    ): IO[EffectiveCollectionConfig] = IO {
      configs.get(collection) match {
        case Some(config) =>
          // Check for file extension override
          val extOverride = for {
            fname <- filename
            ext <- fname.lastIndexOf('.') match {
              case i if i > 0 => Some(fname.substring(i).toLowerCase)
              case _ => None
            }
            strategy <- config.fileTypeStrategies.get(ext)
          } yield (strategy, ext)

          extOverride match {
            case Some((strategy, ext)) =>
              EffectiveCollectionConfig(
                strategy = strategy,
                targetSize = config.targetSize.getOrElse(defaultTargetSize),
                maxSize = config.maxSize.getOrElse(defaultMaxSize),
                overlap = config.overlap.getOrElse(defaultOverlap),
                source = "file-type",
                appliedFileTypeOverride = Some(ext)
              )
            case None =>
              EffectiveCollectionConfig(
                strategy = config.strategy.getOrElse(defaultStrategy),
                targetSize = config.targetSize.getOrElse(defaultTargetSize),
                maxSize = config.maxSize.getOrElse(defaultMaxSize),
                overlap = config.overlap.getOrElse(defaultOverlap),
                source = "collection"
              )
          }
        case None =>
          EffectiveCollectionConfig(
            strategy = defaultStrategy,
            targetSize = defaultTargetSize,
            maxSize = defaultMaxSize,
            overlap = defaultOverlap,
            source = "default"
          )
      }
    }

    override def close(): IO[Unit] = IO.unit
  }

  /**
   * Mock DocumentRegistry for testing.
   */
  private class MockDocumentRegistry extends DocumentRegistry {
    private var documents: Map[String, (String, Int)] = Map.empty // collection -> (docId, count)

    def addDocument(collection: String, docId: String): Unit = {
      val count = documents.get(collection).map(_._2).getOrElse(0) + 1
      documents = documents + (collection -> (docId, count))
    }

    override def initialize(): IO[Unit] = IO.unit
    override def get(documentId: String): IO[Option[DocumentEntry]] = IO.pure(None)
    override def put(entry: DocumentEntry): IO[Unit] = IO.unit
    override def remove(documentId: String): IO[Unit] = IO.unit
    override def listIds(): IO[Seq[String]] = IO.pure(Seq.empty)
    override def listEntries(): IO[Seq[DocumentEntry]] = IO.pure(Seq.empty)
    override def listEntriesSince(since: Instant): IO[Seq[DocumentEntry]] = IO.pure(Seq.empty)
    override def count(): IO[Int] = IO.pure(0)
    override def countByCollection(collection: String): IO[Int] =
      IO.pure(documents.get(collection).map(_._2).getOrElse(0))
    override def listCollections(): IO[Seq[String]] = IO.pure(documents.keys.toSeq)
    override def getMultiple(documentIds: Seq[String]): IO[Seq[DocumentEntry]] = IO.pure(Seq.empty)
    override def contains(documentId: String): IO[Boolean] = IO.pure(false)
    override def findOrphans(keepIds: Set[String]): IO[Seq[String]] = IO.pure(Seq.empty)
    override def listIdsByCollection(collection: String): IO[Seq[String]] = IO.pure(Seq.empty)
    override def clear(): IO[Unit] = IO.unit
    override def getSyncInfo(): IO[SyncInfo] = IO.pure(SyncInfo(None))
    override def markSyncComplete(): IO[Unit] = IO.unit
    override def close(): IO[Unit] = IO.unit
  }

  /**
   * Mock RuntimeConfigManager for testing.
   */
  private class MockRuntimeConfigManager {
    def get: IO[RuntimeConfigResponse] = IO.pure(RuntimeConfigResponse(
      config = RuntimeConfig(
        topK = 5,
        fusionStrategy = "rrf",
        rrfK = 60,
        systemPrompt = "You are helpful.",
        llmTemperature = 0.7,
        chunkingStrategy = "simple",
        chunkSize = 500,
        chunkOverlap = 50
      ),
      lastModified = None,
      hotSettings = Seq("topK"),
      warmSettings = Seq("chunkSize")
    ))
  }

  private def createRoutes(): (HttpRoutes[IO], MockCollectionConfigRegistry, MockDocumentRegistry) = {
    val configRegistry = new MockCollectionConfigRegistry()
    val documentRegistry = new MockDocumentRegistry()
    val runtimeConfigManager = new MockRuntimeConfigManager()

    import io.circe.syntax._
    import cats.syntax.all._
    import org.http4s.circe.CirceEntityDecoder._
    import org.http4s.dsl.io._
    import org.llm4s.ragbox.model.Codecs._

    val routes = HttpRoutes.of[IO] {
      case GET -> Root / "api" / "v1" / "collections" / collection / "config" =>
        for {
          maybeConfig <- configRegistry.get(collection)
          docCount <- documentRegistry.countByCollection(collection)
          runtimeConfig <- runtimeConfigManager.get
          effectiveConfig <- configRegistry.getEffective(
            collection = collection,
            filename = None,
            defaultStrategy = runtimeConfig.config.chunkingStrategy,
            defaultTargetSize = runtimeConfig.config.chunkSize,
            defaultMaxSize = runtimeConfig.config.chunkSize * 2,
            defaultOverlap = runtimeConfig.config.chunkOverlap
          )
          response <- Ok(CollectionConfigResponse(
            collection = collection,
            hasCustomConfig = maybeConfig.isDefined,
            config = maybeConfig,
            effectiveConfig = effectiveConfig,
            documentCount = docCount
          ).asJson)
        } yield response

      case req @ PUT -> Root / "api" / "v1" / "collections" / collection / "config" =>
        for {
          request <- req.as[CollectionConfigUpdateRequest]
          validationErrors = validateConfigRequest(request)
          response <- if (validationErrors.nonEmpty) {
            BadRequest(ErrorResponse(
              error = "validation_failed",
              message = "Configuration validation failed",
              details = Some(validationErrors.mkString("; "))
            ).asJson)
          } else {
            val config = CollectionChunkingConfig(
              collection = collection,
              strategy = request.strategy,
              targetSize = request.targetSize,
              maxSize = request.maxSize,
              overlap = request.overlap,
              fileTypeStrategies = request.fileTypeStrategies.getOrElse(Map.empty)
            )
            for {
              _ <- configRegistry.put(config)
              saved <- configRegistry.get(collection)
              result <- Ok(CollectionConfigUpdateResponse(
                collection = collection,
                config = saved.get,
                message = "Collection chunking configuration updated"
              ).asJson)
            } yield result
          }
        } yield response

      case DELETE -> Root / "api" / "v1" / "collections" / collection / "config" =>
        for {
          deleted <- configRegistry.delete(collection)
          response <- if (deleted) {
            Ok(CollectionConfigDeleteResponse(
              collection = collection,
              message = "Custom configuration removed."
            ).asJson)
          } else {
            NotFound(ErrorResponse(
              error = "not_found",
              message = s"No custom configuration found for collection '$collection'"
            ).asJson)
          }
        } yield response

      case GET -> Root / "api" / "v1" / "collections" / "configs" =>
        for {
          allConfigs <- configRegistry.getAll()
          allCollections <- documentRegistry.listCollections()
          runtimeConfig <- runtimeConfigManager.get
          defaultEffective = EffectiveCollectionConfig(
            strategy = runtimeConfig.config.chunkingStrategy,
            targetSize = runtimeConfig.config.chunkSize,
            maxSize = runtimeConfig.config.chunkSize * 2,
            overlap = runtimeConfig.config.chunkOverlap,
            source = "default"
          )
          response <- Ok(AllCollectionConfigsResponse(
            collections = Seq.empty,
            defaultConfig = defaultEffective
          ).asJson)
        } yield response
    }

    (routes, configRegistry, documentRegistry)
  }

  private val validStrategies = Set("simple", "sentence", "markdown", "semantic")

  private def validateConfigRequest(request: CollectionConfigUpdateRequest): Seq[String] = {
    val errors = scala.collection.mutable.ArrayBuffer.empty[String]

    request.strategy.foreach { s =>
      if (!validStrategies.contains(s)) {
        errors += s"Invalid strategy '$s'"
      }
    }

    request.targetSize.foreach { size =>
      if (size < 100) errors += "targetSize must be at least 100"
      if (size > 10000) errors += "targetSize must be at most 10000"
    }

    request.maxSize.foreach { size =>
      if (size < 100) errors += "maxSize must be at least 100"
    }

    errors.toSeq
  }

  // ============================================================
  // GET /api/v1/collections/{name}/config
  // ============================================================

  "GET /api/v1/collections/{name}/config" should "return default config for collection without custom config" in {
    val (routes, _, _) = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/collections/test-collection/config")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("\"collection\":\"test-collection\"")
    body should include("\"hasCustomConfig\":false")
    body should include("\"source\":\"default\"")
  }

  it should "return custom config when set" in {
    val (routes, configRegistry, _) = createRoutes()

    // Add a custom config
    configRegistry.put(CollectionChunkingConfig(
      collection = "custom-collection",
      strategy = Some("markdown"),
      targetSize = Some(1000),
      maxSize = Some(1500),
      overlap = Some(100),
      fileTypeStrategies = Map.empty
    )).unsafeRunSync()

    val request = Request[IO](Method.GET, uri"/api/v1/collections/custom-collection/config")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("\"hasCustomConfig\":true")
    body should include("\"strategy\":\"markdown\"")
  }

  // ============================================================
  // PUT /api/v1/collections/{name}/config
  // ============================================================

  "PUT /api/v1/collections/{name}/config" should "create custom config" in {
    val (routes, _, _) = createRoutes()

    val requestBody = """{"strategy": "sentence", "targetSize": 800}"""
    val request = Request[IO](Method.PUT, uri"/api/v1/collections/new-collection/config")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("Collection chunking configuration updated")
    body should include("\"strategy\":\"sentence\"")
  }

  it should "reject invalid strategy" in {
    val (routes, _, _) = createRoutes()

    val requestBody = """{"strategy": "invalid_strategy"}"""
    val request = Request[IO](Method.PUT, uri"/api/v1/collections/test-collection/config")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.BadRequest
    val body = response.as[String].unsafeRunSync()
    body should include("validation_failed")
  }

  it should "reject targetSize below minimum" in {
    val (routes, _, _) = createRoutes()

    val requestBody = """{"targetSize": 50}"""
    val request = Request[IO](Method.PUT, uri"/api/v1/collections/test-collection/config")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.BadRequest
    val body = response.as[String].unsafeRunSync()
    body should include("targetSize must be at least 100")
  }

  // ============================================================
  // DELETE /api/v1/collections/{name}/config
  // ============================================================

  "DELETE /api/v1/collections/{name}/config" should "delete existing config" in {
    val (routes, configRegistry, _) = createRoutes()

    // Add config first
    configRegistry.put(CollectionChunkingConfig(
      collection = "to-delete",
      strategy = Some("markdown"),
      targetSize = None,
      maxSize = None,
      overlap = None,
      fileTypeStrategies = Map.empty
    )).unsafeRunSync()

    val request = Request[IO](Method.DELETE, uri"/api/v1/collections/to-delete/config")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("Custom configuration removed")
  }

  it should "return 404 for non-existent config" in {
    val (routes, _, _) = createRoutes()

    val request = Request[IO](Method.DELETE, uri"/api/v1/collections/nonexistent/config")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.NotFound
    val body = response.as[String].unsafeRunSync()
    body should include("not_found")
  }

  // ============================================================
  // GET /api/v1/collections/configs
  // ============================================================

  "GET /api/v1/collections/configs" should "return list of all configs" in {
    val (routes, _, _) = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/collections/configs")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("collections")
    body should include("defaultConfig")
  }
}

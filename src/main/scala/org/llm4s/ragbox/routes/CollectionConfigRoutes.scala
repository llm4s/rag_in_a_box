package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.syntax.all._
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.dsl.io._
import org.llm4s.ragbox.config.RuntimeConfigManager
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.model.Codecs._
import org.llm4s.ragbox.registry.{CollectionConfigRegistry, DocumentRegistry}

/**
 * HTTP routes for per-collection chunking configuration.
 *
 * Allows users to set different chunking strategies and parameters
 * for different collections, with optional file-type overrides.
 */
object CollectionConfigRoutes {

  def routes(
    collectionConfigRegistry: CollectionConfigRegistry,
    documentRegistry: DocumentRegistry,
    runtimeConfigManager: RuntimeConfigManager
  ): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // GET /api/v1/collections/{name}/config - Get collection chunking config
    case GET -> Root / "api" / "v1" / "collections" / collection / "config" =>
      for {
        maybeConfig <- collectionConfigRegistry.get(collection)
        docCount <- documentRegistry.countByCollection(collection)
        runtimeConfig <- runtimeConfigManager.get

        effectiveConfig <- collectionConfigRegistry.getEffective(
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

    // PUT /api/v1/collections/{name}/config - Set collection chunking config
    case req @ PUT -> Root / "api" / "v1" / "collections" / collection / "config" =>
      for {
        request <- req.as[CollectionConfigUpdateRequest]

        // Validate the settings
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
            _ <- collectionConfigRegistry.put(config)
            saved <- collectionConfigRegistry.get(collection)
            result <- Ok(CollectionConfigUpdateResponse(
              collection = collection,
              config = saved.get,
              message = "Collection chunking configuration updated"
            ).asJson)
          } yield result
        }
      } yield response

    // DELETE /api/v1/collections/{name}/config - Remove custom config
    case DELETE -> Root / "api" / "v1" / "collections" / collection / "config" =>
      for {
        deleted <- collectionConfigRegistry.delete(collection)
        response <- if (deleted) {
          Ok(CollectionConfigDeleteResponse(
            collection = collection,
            message = "Custom configuration removed. Collection will use default settings."
          ).asJson)
        } else {
          NotFound(ErrorResponse(
            error = "not_found",
            message = s"No custom configuration found for collection '$collection'"
          ).asJson)
        }
      } yield response

    // GET /api/v1/collections/configs - List all collection configs
    case GET -> Root / "api" / "v1" / "collections" / "configs" =>
      for {
        allConfigs <- collectionConfigRegistry.getAll()
        allCollections <- documentRegistry.listCollections()
        runtimeConfig <- runtimeConfigManager.get

        defaultEffective = EffectiveCollectionConfig(
          strategy = runtimeConfig.config.chunkingStrategy,
          targetSize = runtimeConfig.config.chunkSize,
          maxSize = runtimeConfig.config.chunkSize * 2,
          overlap = runtimeConfig.config.chunkOverlap,
          source = "default"
        )

        // Build responses for all collections (with and without custom configs)
        configMap = allConfigs.map(c => c.collection -> c).toMap
        collectionResponses <- allCollections.toList.traverse { coll =>
          for {
            docCount <- documentRegistry.countByCollection(coll)
            effective <- collectionConfigRegistry.getEffective(
              collection = coll,
              filename = None,
              defaultStrategy = runtimeConfig.config.chunkingStrategy,
              defaultTargetSize = runtimeConfig.config.chunkSize,
              defaultMaxSize = runtimeConfig.config.chunkSize * 2,
              defaultOverlap = runtimeConfig.config.chunkOverlap
            )
          } yield CollectionConfigResponse(
            collection = coll,
            hasCustomConfig = configMap.contains(coll),
            config = configMap.get(coll),
            effectiveConfig = effective,
            documentCount = docCount
          )
        }

        response <- Ok(AllCollectionConfigsResponse(
          collections = collectionResponses,
          defaultConfig = defaultEffective
        ).asJson)
      } yield response

    // POST /api/v1/collections/{name}/config/preview - Preview effective config for a file
    case req @ POST -> Root / "api" / "v1" / "collections" / collection / "config" / "preview" =>
      for {
        request <- req.as[EffectiveConfigPreviewRequest]
        runtimeConfig <- runtimeConfigManager.get

        effective <- collectionConfigRegistry.getEffective(
          collection = collection,
          filename = request.filename,
          defaultStrategy = runtimeConfig.config.chunkingStrategy,
          defaultTargetSize = runtimeConfig.config.chunkSize,
          defaultMaxSize = runtimeConfig.config.chunkSize * 2,
          defaultOverlap = runtimeConfig.config.chunkOverlap
        )

        resolutionPath = buildResolutionPath(collection, request.filename, effective)

        response <- Ok(EffectiveConfigPreviewResponse(
          collection = collection,
          filename = request.filename,
          effectiveConfig = effective,
          configResolutionPath = resolutionPath
        ).asJson)
      } yield response
  }

  private val validStrategies = Set("simple", "sentence", "markdown", "semantic")

  private def validateConfigRequest(request: CollectionConfigUpdateRequest): Seq[String] = {
    val errors = scala.collection.mutable.ArrayBuffer.empty[String]

    request.strategy.foreach { s =>
      if (!validStrategies.contains(s)) {
        errors += s"Invalid strategy '$s'. Valid values: ${validStrategies.mkString(", ")}"
      }
    }

    request.targetSize.foreach { size =>
      if (size < 100) errors += "targetSize must be at least 100"
      if (size > 10000) errors += "targetSize must be at most 10000"
    }

    request.maxSize.foreach { size =>
      if (size < 100) errors += "maxSize must be at least 100"
      if (size > 20000) errors += "maxSize must be at most 20000"
    }

    request.overlap.foreach { overlap =>
      if (overlap < 0) errors += "overlap must be non-negative"
      val targetSize = request.targetSize.getOrElse(1000)
      if (overlap >= targetSize) errors += "overlap must be less than targetSize"
    }

    request.fileTypeStrategies.foreach { strategies =>
      strategies.foreach { case (ext, strategy) =>
        if (!ext.startsWith(".")) {
          errors += s"File extension '$ext' must start with a dot (e.g., '.md')"
        }
        if (!validStrategies.contains(strategy)) {
          errors += s"Invalid strategy '$strategy' for extension '$ext'. Valid values: ${validStrategies.mkString(", ")}"
        }
      }
    }

    errors.toSeq
  }

  private def buildResolutionPath(
    collection: String,
    filename: Option[String],
    effective: EffectiveCollectionConfig
  ): Seq[String] = {
    val path = scala.collection.mutable.ArrayBuffer.empty[String]

    effective.source match {
      case "file-type" =>
        path += s"Checked file extension: ${effective.appliedFileTypeOverride.getOrElse("unknown")}"
        path += s"Found file-type override in collection '$collection' config"
        path += s"Using strategy: ${effective.strategy}"

      case "collection" =>
        filename.foreach { f =>
          val ext = f.lastIndexOf('.') match {
            case i if i > 0 => Some(f.substring(i).toLowerCase)
            case _ => None
          }
          ext.foreach(e => path += s"Checked file extension: $e (no override found)")
        }
        path += s"Using collection '$collection' config"

      case "default" =>
        filename.foreach { f =>
          val ext = f.lastIndexOf('.') match {
            case i if i > 0 => Some(f.substring(i).toLowerCase)
            case _ => None
          }
          ext.foreach(e => path += s"Checked file extension: $e (no override found)")
        }
        path += s"No custom config for collection '$collection'"
        path += "Using default runtime configuration"
    }

    path.toSeq
  }
}

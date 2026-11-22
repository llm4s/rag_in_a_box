package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.syntax.all._
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._
import org.http4s._
import org.llm4s.ragbox.auth.{PermissionContext, Principal}
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.dsl.impl.OptionalQueryParamDecoderMatcher
import org.llm4s.rag.permissions._
import org.llm4s.ragbox.model.ErrorResponse

/**
 * HTTP routes for collection management with permission controls.
 *
 * Provides CRUD operations for the hierarchical collection structure
 * with queryableBy permissions via the LLM4S CollectionStore.
 */
object CollectionPermissionRoutes {

  // Query param matchers
  object PatternParam extends OptionalQueryParamDecoderMatcher[String]("pattern")

  // Request/Response models
  case class CreateCollectionRequest(
    path: String,
    description: Option[String] = None,
    queryableBy: Option[Seq[String]] = None, // External principal IDs like "user:alice", "group:eng"
    isLeaf: Option[Boolean] = None,
    metadata: Option[Map[String, String]] = None
  )

  case class UpdatePermissionsRequest(
    queryableBy: Seq[String] // External principal IDs
  )

  case class CollectionResponse(
    id: Int,
    path: String,
    parentPath: Option[String],
    queryableBy: Seq[Int], // Principal IDs
    isLeaf: Boolean,
    isPublic: Boolean,
    metadata: Map[String, String]
  )

  case class CollectionListResponse(
    collections: Seq[CollectionResponse]
  )

  case class CollectionStatsResponse(
    path: String,
    documentCount: Long,
    chunkCount: Long
  )

  // Circe codecs
  implicit val createCollectionDecoder: Decoder[CreateCollectionRequest] = deriveDecoder
  implicit val updatePermissionsDecoder: Decoder[UpdatePermissionsRequest] = deriveDecoder
  implicit val collectionResponseEncoder: Encoder[CollectionResponse] = deriveEncoder
  implicit val collectionListResponseEncoder: Encoder[CollectionListResponse] = deriveEncoder
  implicit val collectionStatsResponseEncoder: Encoder[CollectionStatsResponse] = deriveEncoder

  /**
   * Convert LLM4S Collection to our response format.
   */
  private def toResponse(coll: Collection): CollectionResponse =
    CollectionResponse(
      id = coll.id,
      path = coll.path.value,
      parentPath = coll.parentPath.map(_.value),
      queryableBy = coll.queryableBy.map(_.value).toSeq.sorted,
      isLeaf = coll.isLeaf,
      isPublic = coll.isPublic,
      metadata = coll.metadata
    )

  /**
   * Resolve external principal IDs to PrincipalIds.
   */
  private def resolvePrincipals(
    externalIds: Seq[String],
    principals: PrincipalStore
  ): IO[Set[PrincipalId]] =
    externalIds.toList.traverse { extId =>
      IO.fromEither(
        ExternalPrincipal.parse(extId)
          .flatMap(principals.getOrCreate)
          .left.map(e => new RuntimeException(s"Failed to resolve principal '$extId': ${e.message}"))
      )
    }.map(_.toSet)

  // Creating and deleting collections and changing their permissions is admin-only; reads are filtered.
  def routes(collectionStore: CollectionStore, principalStore: PrincipalStore): AuthedRoutes[Principal, IO] = Authz.adminOnlyWhen(req => Authz.mutating(req) && Authz.under("/api/v1/collections")(req))(AuthedRoutes.of[Principal, IO] {

    // POST /api/v1/collections - Create collection
    case (req @ POST -> Root / "api" / "v1" / "collections") as principal =>
      for {
        body <- req.as[CreateCollectionRequest]
        path <- IO.fromEither(
          CollectionPath.create(body.path)
            .left.map(e => new RuntimeException(e.message))
        )
        queryableBy <- body.queryableBy match {
          case Some(ids) => resolvePrincipals(ids, principalStore)
          case None => IO.pure(Set.empty[PrincipalId])
        }
        config = CollectionConfig(
          path = path,
          queryableBy = queryableBy,
          isLeaf = body.isLeaf.getOrElse(true),
          metadata = body.metadata.getOrElse(Map.empty)
        )
        result <- IO.fromEither(
          collectionStore.create(config)
            .left.map(e => new RuntimeException(e.message))
        ).attempt
        response <- result match {
          case Right(coll) =>
            Created(toResponse(coll).asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to create collection",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // GET /api/v1/collections - List collections (with optional pattern filter)
    case GET -> Root / "api" / "v1" / "collections" :? PatternParam(patternOpt) as principal =>
      val pattern = patternOpt
        .flatMap(p => CollectionPattern.parse(p).toOption)
        .getOrElse(CollectionPattern.All)

      for {
        result <- visibleCollections(collectionStore, principalStore, principal, pattern).attempt
        response <- result match {
          case Right(collections) =>
            Ok(CollectionListResponse(collections.map(toResponse)).asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to list collections",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // GET /api/v1/collections/accessible - List collections accessible to current user
    case GET -> Root / "api" / "v1" / "collections" / "accessible" :? PatternParam(patternOpt) as principal =>
      val pattern = patternOpt
        .flatMap(p => CollectionPattern.parse(p).toOption)
        .getOrElse(CollectionPattern.All)

      for {
        auth <- PermissionContext.authorization(principal, principalStore)
        result <- IO.fromEither(
          collectionStore.findAccessible(auth, pattern)
            .left.map(e => new RuntimeException(e.message))
        ).attempt
        response <- result match {
          case Right(collections) =>
            Ok(CollectionListResponse(collections.map(toResponse)).asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to list accessible collections",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // GET /api/v1/collections/:path - Get collection by path
    // Note: path can contain slashes, so we match the remainder
    // Exclude "configs" which is handled by CollectionConfigRoutes
    case GET -> Root / "api" / "v1" / "collections" / path as principal if !path.contains("/") && path != "configs" =>
      ifVisible(collectionStore, principalStore, principal, path)(getCollection(collectionStore, path))

    // Paths ending in /config belong to CollectionConfigRoutes (GET /collections/{c}/config);
    // this catch-all is mounted later in the Router and would otherwise shadow it.
    case GET -> "api" /: "v1" /: "collections" /: rest as principal
        if rest.segments.mkString("/") != "accessible" && rest.segments.mkString("/") != "configs" &&
          !(rest.segments.size > 1 && rest.segments.last.decoded() == "config") =>
      val path = rest.segments.mkString("/")
      ifVisible(collectionStore, principalStore, principal, path)(getCollection(collectionStore, path))

    // PUT /api/v1/collections/:path/permissions - Update collection permissions
    case (req @ PUT -> Root / "api" / "v1" / "collections" / path / "permissions") as principal =>
      updateCollectionPermissions(req, collectionStore, principalStore, path)

    // DELETE /api/v1/collections/:path - Delete collection
    case DELETE -> Root / "api" / "v1" / "collections" / path as principal =>
      deleteCollection(collectionStore, path)

    // GET /api/v1/collections/:path/stats - Get collection statistics
    case GET -> Root / "api" / "v1" / "collections" / path / "stats" as principal =>
      ifVisible(collectionStore, principalStore, principal, path)(getCollectionStats(collectionStore, path))
  })

  /**
   * Collections `principal` may see: all of them for administrators, otherwise only those its
   * permissions let it query (so restricted collections, and who may query them, stay hidden).
   */
  private def visibleCollections(
    collectionStore: CollectionStore,
    principalStore: PrincipalStore,
    principal: Principal,
    pattern: CollectionPattern
  ): IO[Seq[Collection]] =
    if (principal.isAdmin) IO.fromEither(collectionStore.list(pattern).left.map(e => new RuntimeException(e.message)))
    else
      PermissionContext.authorization(principal, principalStore).flatMap { auth =>
        IO.fromEither(collectionStore.findAccessible(auth, pattern).left.map(e => new RuntimeException(e.message)))
      }.map(_.filter(c => principal.canAccessCollection(c.path.value)))

  /** Run `operation` if `principal` may see collection `path`, else 404 (existence is not revealed). */
  private def ifVisible(
    collectionStore: CollectionStore,
    principalStore: PrincipalStore,
    principal: Principal,
    path: String
  )(operation: => IO[Response[IO]]): IO[Response[IO]] =
    if (principal.isAdmin) operation
    else
      visibleCollections(collectionStore, principalStore, principal, CollectionPattern.All).flatMap { visible =>
        if (visible.exists(_.path.value == path)) operation
        else NotFound(ErrorResponse.notFound(s"Collection not found: $path").asJson)
      }

  private def getCollection(collectionStore: CollectionStore, pathStr: String): IO[Response[IO]] =
    for {
      path <- IO.fromEither(
        CollectionPath.create(pathStr)
          .left.map(e => new RuntimeException(e.message))
      )
      result <- IO.fromEither(
        collectionStore.get(path)
          .left.map(e => new RuntimeException(e.message))
      ).attempt
      response <- result match {
        case Right(Some(coll)) =>
          Ok(toResponse(coll).asJson)
        case Right(None) =>
          NotFound(ErrorResponse.notFound(s"Collection not found: $pathStr").asJson)
        case Left(e) =>
          InternalServerError(ErrorResponse.internalError(
            "Failed to get collection",
            Some(e.getMessage)
          ).asJson)
      }
    } yield response

  private def updateCollectionPermissions(
    req: Request[IO],
    collectionStore: CollectionStore,
    principalStore: PrincipalStore,
    pathStr: String
  ): IO[Response[IO]] =
    for {
      body <- req.as[UpdatePermissionsRequest]
      path <- IO.fromEither(
        CollectionPath.create(pathStr)
          .left.map(e => new RuntimeException(e.message))
      )
      queryableBy <- resolvePrincipals(body.queryableBy, principalStore)
      result <- IO.fromEither(
        collectionStore.updatePermissions(path, queryableBy)
          .left.map(e => new RuntimeException(e.message))
      ).attempt
      response <- result match {
        case Right(coll) =>
          Ok(toResponse(coll).asJson)
        case Left(e) =>
          InternalServerError(ErrorResponse.internalError(
            "Failed to update permissions",
            Some(e.getMessage)
          ).asJson)
      }
    } yield response

  private def deleteCollection(collectionStore: CollectionStore, pathStr: String): IO[Response[IO]] =
    for {
      path <- IO.fromEither(
        CollectionPath.create(pathStr)
          .left.map(e => new RuntimeException(e.message))
      )
      result <- IO.fromEither(
        collectionStore.delete(path)
          .left.map(e => new RuntimeException(e.message))
      ).attempt
      response <- result match {
        case Right(_) =>
          NoContent()
        case Left(e) =>
          InternalServerError(ErrorResponse.internalError(
            "Failed to delete collection",
            Some(e.getMessage)
          ).asJson)
      }
    } yield response

  private def getCollectionStats(collectionStore: CollectionStore, pathStr: String): IO[Response[IO]] =
    for {
      path <- IO.fromEither(
        CollectionPath.create(pathStr)
          .left.map(e => new RuntimeException(e.message))
      )
      result <- IO.fromEither(
        collectionStore.stats(path)
          .left.map(e => new RuntimeException(e.message))
      ).attempt
      response <- result match {
        case Right(stats) =>
          Ok(CollectionStatsResponse(
            path = pathStr,
            documentCount = stats.documentCount,
            chunkCount = stats.chunkCount
          ).asJson)
        case Left(e) =>
          InternalServerError(ErrorResponse.internalError(
            "Failed to get collection stats",
            Some(e.getMessage)
          ).asJson)
      }
    } yield response
}

package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.syntax.all._
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._
import org.http4s._
import org.llm4s.error.ValidationError
import org.llm4s.ragbox.auth.{PermissionContext, Principal}
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.dsl.impl.OptionalQueryParamDecoderMatcher
import org.llm4s.rag.permissions._
import org.llm4s.ragbox.model.ErrorResponse
import org.llm4s.ragbox.service.{ConflictError, InvalidRequestError, RAGService}
import org.llm4s.types.Result

/**
 * HTTP routes for collection management with permission controls.
 *
 * Provides CRUD operations for the hierarchical collection structure
 * with queryableBy permissions via the LLM4S CollectionStore.
 *
 * `GET /api/v1/collections` itself is defined once, in [[DocumentRoutes]]: the names of the
 * collections that have documents. The collections with their permissions are listed by
 * `GET /api/v1/collections/accessible`.
 */
object CollectionPermissionRoutes {

  // Query param matchers
  object PatternParam extends OptionalQueryParamDecoderMatcher[String]("pattern")

  /** The collection metadata key `CreateCollectionRequest.description` is stored under. */
  val CollectionDescriptionKey: String = "description"

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
   * A CollectionStore call (JDBC) on the blocking pool. llm4s reports what the request got wrong
   * (an existing or missing collection, a bad parent) as a `ValidationError`: a 400. Any other
   * store error is a server error.
   */
  private def store[A](call: => Result[A]): IO[A] =
    IO.blocking(call).flatMap {
      case Right(a) => IO.pure(a)
      case Left(e: ValidationError) => IO.raiseError(InvalidRequestError(e.message))
      case Left(e) => IO.raiseError(new RuntimeException(e.message))
    }

  private def parsePath(path: String): IO[CollectionPath] =
    IO.fromEither(CollectionPath.create(path).left.map(e => InvalidRequestError(s"Invalid collection path '$path': ${e.message}")))

  /** Run `f` with the `pattern` parameter (all collections when absent), or 400 if it is invalid. */
  private def withPattern(pattern: Option[String])(f: CollectionPattern => IO[Response[IO]]): IO[Response[IO]] =
    pattern.traverse(p => CollectionPattern.parse(p).left.map(e => s"Invalid collection pattern '$p': ${e.message}")) match {
      case Left(message) => BadRequest(ErrorResponse.badRequest(message).asJson)
      case Right(parsed) => f(parsed.getOrElse(CollectionPattern.All))
    }

  // Creating and deleting collections and changing their permissions is admin-only; reads are
  // filtered. The per-collection chunking config endpoints belong to CollectionConfigRoutes,
  // which applies its own rule (the read-only preview is open to any authenticated user).
  def routes(collectionStore: CollectionStore, principalStore: PrincipalStore): AuthedRoutes[Principal, IO] = Authz.adminOnlyWhen(req =>
    Authz.mutating(req) && Authz.under("/api/v1/collections")(req) &&
      !CollectionRoute.isConfig(req.pathInfo) && !CollectionRoute.isConfigPreview(req.pathInfo)
  )(AuthedRoutes.of[Principal, IO] {

    // POST /api/v1/collections - Create collection (409 if it exists)
    case (req @ POST -> Root / "api" / "v1" / "collections") as _ =>
      for {
        body <- req.as[CreateCollectionRequest]
        path <- parsePath(body.path)
        existing <- store(collectionStore.get(path))
        _ <- IO.raiseWhen(existing.isDefined)(ConflictError(s"Collection '${path.value}' already exists"))
        queryableBy <- RAGService.resolvePrincipals(body.queryableBy.getOrElse(Nil), principalStore)
        created <- store(collectionStore.create(CollectionConfig(
          path = path,
          queryableBy = queryableBy,
          isLeaf = body.isLeaf.getOrElse(true),
          // The description is kept in the llm4s collection's metadata, under "description"
          metadata = body.metadata.getOrElse(Map.empty) ++ body.description.map(CollectionDescriptionKey -> _)
        )))
        response <- Created(toResponse(created).asJson)
      } yield response

    // GET /api/v1/collections/accessible?pattern= - The collections the caller may query, with
    // their permissions (administrators: all of them)
    case GET -> Root / "api" / "v1" / "collections" / "accessible" :? PatternParam(patternOpt) as principal =>
      withPattern(patternOpt) { pattern =>
        visibleCollections(collectionStore, principalStore, principal, pattern).flatMap { collections =>
          Ok(CollectionListResponse(collections.map(toResponse)).asJson)
        }
      }

    // GET /api/v1/collections/{path} - Get collection by path. Nested paths may be written with
    // raw or encoded slashes (`eng/backend`, `eng%2Fbackend`): see CollectionRoute.
    case GET -> "api" /: "v1" /: "collections" /: CollectionRoute.Bare(pathStr) as principal =>
      ifVisible(collectionStore, principalStore, principal, pathStr)(getCollection(collectionStore, pathStr))

    // PUT /api/v1/collections/{path}/permissions - Update collection permissions
    case (req @ PUT -> "api" /: "v1" /: "collections" /: CollectionRoute.Permissions(pathStr)) as _ =>
      for {
        body <- req.as[UpdatePermissionsRequest]
        path <- parsePath(pathStr)
        queryableBy <- RAGService.resolvePrincipals(body.queryableBy, principalStore)
        updated <- store(collectionStore.updatePermissions(path, queryableBy))
        response <- Ok(toResponse(updated).asJson)
      } yield response

    // DELETE /api/v1/collections/{path} - Delete collection
    case DELETE -> "api" /: "v1" /: "collections" /: CollectionRoute.Bare(pathStr) as _ =>
      parsePath(pathStr).flatMap(path => store(collectionStore.delete(path))) *> NoContent()

    // GET /api/v1/collections/{path}/stats - Get collection statistics
    case GET -> "api" /: "v1" /: "collections" /: CollectionRoute.Stats(pathStr) as principal =>
      ifVisible(collectionStore, principalStore, principal, pathStr) {
        for {
          path <- parsePath(pathStr)
          stats <- store(collectionStore.stats(path))
          response <- Ok(CollectionStatsResponse(path = pathStr, documentCount = stats.documentCount, chunkCount = stats.chunkCount).asJson)
        } yield response
      }
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
    if (principal.isAdmin) store(collectionStore.list(pattern))
    else
      PermissionContext.authorization(principal, principalStore).flatMap { auth =>
        store(collectionStore.findAccessible(auth, pattern))
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
      path <- parsePath(pathStr)
      collection <- store(collectionStore.get(path))
      response <- collection match {
        case Some(coll) => Ok(toResponse(coll).asJson)
        case None => NotFound(ErrorResponse.notFound(s"Collection not found: $pathStr").asJson)
      }
    } yield response
}

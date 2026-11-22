package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.syntax.all._
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._
import org.http4s._
import org.llm4s.ragbox.auth.Principal
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.llm4s.rag.permissions._
import org.llm4s.ragbox.model.ErrorResponse
import org.llm4s.ragbox.routes.Params.{opt, withValid, Limit, Offset}
import org.llm4s.types.Result

/**
 * HTTP routes for principal (user/group) management.
 *
 * Provides CRUD operations for users and groups via the LLM4S PrincipalStore.
 */
object PrincipalRoutes {

  /** Default and maximum page sizes of the principal listings. */
  val DefaultLimit: Int = 100
  val MaxLimit: Int = 1000

  // Request/Response models
  case class CreatePrincipalRequest(externalId: String)
  case class PrincipalResponse(id: Int, externalId: String, principalType: String)
  case class PrincipalListResponse(principals: Seq[PrincipalResponse], total: Long)

  // Circe codecs
  implicit val createPrincipalDecoder: Decoder[CreatePrincipalRequest] = deriveDecoder
  implicit val principalResponseEncoder: Encoder[PrincipalResponse] = deriveEncoder
  implicit val principalListResponseEncoder: Encoder[PrincipalListResponse] = deriveEncoder

  /** A PrincipalStore call (JDBC) on the blocking pool; a store error is a server error. */
  private def store[A](call: => Result[A]): IO[A] =
    IO.blocking(call).flatMap(r => IO.fromEither(r.left.map(e => new RuntimeException(e.message))))

  // Principal management is admin-only (#13).
  def routes(principalStore: PrincipalStore): AuthedRoutes[Principal, IO] = Authz.adminOnlyWhen(Authz.under("/api/v1/principals"))(AuthedRoutes.of[Principal, IO] {

    // POST /api/v1/principals/users - Create or get user principal
    case (req @ POST -> Root / "api" / "v1" / "principals" / "users") as _ =>
      req.as[CreatePrincipalRequest].flatMap(body => create(principalStore, ExternalPrincipal.User(body.externalId), "user"))

    // POST /api/v1/principals/groups - Create or get group principal
    case (req @ POST -> Root / "api" / "v1" / "principals" / "groups") as _ =>
      req.as[CreatePrincipalRequest].flatMap(body => create(principalStore, ExternalPrincipal.Group(body.externalId), "group"))

    // GET /api/v1/principals/users?limit=&offset= - List users
    case GET -> Root / "api" / "v1" / "principals" / "users" :? Limit(limit) +& Offset(offset) as _ =>
      withValid((opt(limit), opt(offset)).tupled) { case (l, o) => list(principalStore, "user", l, o) }

    // GET /api/v1/principals/groups?limit=&offset= - List groups
    case GET -> Root / "api" / "v1" / "principals" / "groups" :? Limit(limit) +& Offset(offset) as _ =>
      withValid((opt(limit), opt(offset)).tupled) { case (l, o) => list(principalStore, "group", l, o) }

    // GET /api/v1/principals/lookup/:externalId - Lookup principal by external ID
    case GET -> Root / "api" / "v1" / "principals" / "lookup" / externalId as _ =>
      ExternalPrincipal.parse(externalId) match {
        case Left(_) =>
          BadRequest(ErrorResponse.badRequest(
            "Invalid external ID format",
            Some("Expected user:<id> or group:<name>")
          ).asJson)
        case Right(ext) =>
          store(principalStore.lookup(ext)).flatMap {
            case Some(principalId) =>
              Ok(PrincipalResponse(
                id = principalId.value,
                externalId = externalId,
                principalType = if (principalId.value > 0) "user" else "group"
              ).asJson)
            case None =>
              NotFound(ErrorResponse.notFound(s"Principal not found: $externalId").asJson)
          }
      }

    // DELETE /api/v1/principals/:type/:externalId - Delete principal
    case DELETE -> Root / "api" / "v1" / "principals" / principalType / externalId as _ =>
      principalType match {
        case "users" | "user" => store(principalStore.delete(ExternalPrincipal.User(externalId))) *> NoContent()
        case "groups" | "group" => store(principalStore.delete(ExternalPrincipal.Group(externalId))) *> NoContent()
        case _ => BadRequest(ErrorResponse.badRequest(s"Invalid principal type: $principalType (use users or groups)").asJson)
      }
  })

  private def create(principalStore: PrincipalStore, ext: ExternalPrincipal, principalType: String): IO[Response[IO]] =
    store(principalStore.getOrCreate(ext)).flatMap { principalId =>
      Created(PrincipalResponse(id = principalId.value, externalId = ext.externalId, principalType = principalType).asJson)
    }

  private def list(principalStore: PrincipalStore, principalType: String, limit: Option[Int], offset: Option[Int]): IO[Response[IO]] =
    for {
      principals <- store(principalStore.list(principalType, Params.limit(limit, DefaultLimit, MaxLimit), Params.offset(offset)))
      total <- store(principalStore.count(principalType))
      ids <- store(principalStore.lookupBatch(principals))
      response <- Ok(PrincipalListResponse(
        principals.map(ext => PrincipalResponse(ids.get(ext).map(_.value).getOrElse(0), ext.externalId, principalType)),
        total
      ).asJson)
    } yield response
}

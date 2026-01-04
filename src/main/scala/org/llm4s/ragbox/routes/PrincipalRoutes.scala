package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.syntax.all._
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._
import org.http4s._
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.dsl.impl.OptionalQueryParamDecoderMatcher
import org.llm4s.rag.permissions._
import org.llm4s.ragbox.model.ErrorResponse
import org.llm4s.ragbox.model.Codecs._

/**
 * HTTP routes for principal (user/group) management.
 *
 * Provides CRUD operations for users and groups via the LLM4S PrincipalStore.
 */
object PrincipalRoutes {

  // Query param matchers
  object LimitParam extends OptionalQueryParamDecoderMatcher[Int]("limit")
  object OffsetParam extends OptionalQueryParamDecoderMatcher[Int]("offset")

  // Request/Response models
  case class CreatePrincipalRequest(externalId: String)
  case class PrincipalResponse(id: Int, externalId: String, principalType: String)
  case class PrincipalListResponse(principals: Seq[PrincipalResponse], total: Long)

  // Circe codecs
  implicit val createPrincipalDecoder: Decoder[CreatePrincipalRequest] = deriveDecoder
  implicit val principalResponseEncoder: Encoder[PrincipalResponse] = deriveEncoder
  implicit val principalListResponseEncoder: Encoder[PrincipalListResponse] = deriveEncoder

  def routes(principalStore: PrincipalStore): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // POST /api/v1/principals/users - Create or get user principal
    case req @ POST -> Root / "api" / "v1" / "principals" / "users" =>
      for {
        body <- req.as[CreatePrincipalRequest]
        result <- IO.fromEither(
          principalStore.getOrCreate(ExternalPrincipal.User(body.externalId))
            .left.map(e => new RuntimeException(e.message))
        ).attempt
        response <- result match {
          case Right(principalId) =>
            Created(PrincipalResponse(
              id = principalId.value,
              externalId = s"user:${body.externalId}",
              principalType = "user"
            ).asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to create user principal",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // POST /api/v1/principals/groups - Create or get group principal
    case req @ POST -> Root / "api" / "v1" / "principals" / "groups" =>
      for {
        body <- req.as[CreatePrincipalRequest]
        result <- IO.fromEither(
          principalStore.getOrCreate(ExternalPrincipal.Group(body.externalId))
            .left.map(e => new RuntimeException(e.message))
        ).attempt
        response <- result match {
          case Right(principalId) =>
            Created(PrincipalResponse(
              id = principalId.value,
              externalId = s"group:${body.externalId}",
              principalType = "group"
            ).asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to create group principal",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // GET /api/v1/principals/users - List users
    case GET -> Root / "api" / "v1" / "principals" / "users" :? LimitParam(limit) +& OffsetParam(offset) =>
      for {
        result <- IO.fromEither(
          principalStore.list("user", limit.getOrElse(100), offset.getOrElse(0))
            .left.map(e => new RuntimeException(e.message))
        ).attempt
        countResult <- IO.fromEither(
          principalStore.count("user")
            .left.map(e => new RuntimeException(e.message))
        ).attempt
        response <- (result, countResult) match {
          case (Right(principals), Right(total)) =>
            // Lookup IDs for all principals
            IO.fromEither(
              principalStore.lookupBatch(principals)
                .left.map(e => new RuntimeException(e.message))
            ).attempt.flatMap {
              case Right(idMap) =>
                val responses = principals.map { ext =>
                  PrincipalResponse(
                    id = idMap.get(ext).map(_.value).getOrElse(0),
                    externalId = ext.externalId,
                    principalType = "user"
                  )
                }
                Ok(PrincipalListResponse(responses, total).asJson)
              case Left(e) =>
                InternalServerError(ErrorResponse.internalError(
                  "Failed to lookup user IDs",
                  Some(e.getMessage)
                ).asJson)
            }
          case (Left(e), _) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to list users",
              Some(e.getMessage)
            ).asJson)
          case (_, Left(e)) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to count users",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // GET /api/v1/principals/groups - List groups
    case GET -> Root / "api" / "v1" / "principals" / "groups" :? LimitParam(limit) +& OffsetParam(offset) =>
      for {
        result <- IO.fromEither(
          principalStore.list("group", limit.getOrElse(100), offset.getOrElse(0))
            .left.map(e => new RuntimeException(e.message))
        ).attempt
        countResult <- IO.fromEither(
          principalStore.count("group")
            .left.map(e => new RuntimeException(e.message))
        ).attempt
        response <- (result, countResult) match {
          case (Right(principals), Right(total)) =>
            // Lookup IDs for all principals
            IO.fromEither(
              principalStore.lookupBatch(principals)
                .left.map(e => new RuntimeException(e.message))
            ).attempt.flatMap {
              case Right(idMap) =>
                val responses = principals.map { ext =>
                  PrincipalResponse(
                    id = idMap.get(ext).map(_.value).getOrElse(0),
                    externalId = ext.externalId,
                    principalType = "group"
                  )
                }
                Ok(PrincipalListResponse(responses, total).asJson)
              case Left(e) =>
                InternalServerError(ErrorResponse.internalError(
                  "Failed to lookup group IDs",
                  Some(e.getMessage)
                ).asJson)
            }
          case (Left(e), _) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to list groups",
              Some(e.getMessage)
            ).asJson)
          case (_, Left(e)) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to count groups",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // GET /api/v1/principals/lookup/:externalId - Lookup principal by external ID
    case GET -> Root / "api" / "v1" / "principals" / "lookup" / externalId =>
      for {
        parsed <- IO.fromEither(
          ExternalPrincipal.parse(externalId)
            .left.map(e => new RuntimeException(e.message))
        ).attempt
        result <- parsed match {
          case Right(ext) =>
            IO.fromEither(
              principalStore.lookup(ext)
                .left.map(e => new RuntimeException(e.message))
            ).attempt
          case Left(e) =>
            IO.pure(Left(e): Either[Throwable, Option[PrincipalId]])
        }
        response <- result match {
          case Right(Some(principalId)) =>
            Ok(PrincipalResponse(
              id = principalId.value,
              externalId = externalId,
              principalType = if (principalId.value > 0) "user" else "group"
            ).asJson)
          case Right(None) =>
            NotFound(ErrorResponse.notFound(s"Principal not found: $externalId").asJson)
          case Left(e) =>
            BadRequest(ErrorResponse.badRequest(
              "Invalid external ID format",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // DELETE /api/v1/principals/:type/:externalId - Delete principal
    case DELETE -> Root / "api" / "v1" / "principals" / principalType / externalId =>
      val ext = principalType match {
        case "users" | "user" => Right(ExternalPrincipal.User(externalId))
        case "groups" | "group" => Right(ExternalPrincipal.Group(externalId))
        case _ => Left(new RuntimeException(s"Invalid principal type: $principalType"))
      }

      for {
        result <- ext match {
          case Right(e) =>
            IO.fromEither(
              principalStore.delete(e)
                .left.map(err => new RuntimeException(err.message))
            ).attempt
          case Left(e) =>
            IO.pure(Left(e): Either[Throwable, Unit])
        }
        response <- result match {
          case Right(_) =>
            NoContent()
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to delete principal",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response
  }
}

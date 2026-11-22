package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.syntax.all._
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.llm4s.ragbox.auth._
import org.llm4s.ragbox.model.ErrorResponse

import java.time.Instant

/**
 * Request to create an access token.
 */
final case class CreateTokenRequest(
  name: String,
  scopes: Seq[String],
  collections: Option[Seq[String]] = None,
  expiresAt: Option[String] = None  // ISO-8601 timestamp
)

object CreateTokenRequest {
  implicit val decoder: Decoder[CreateTokenRequest] = deriveDecoder
  implicit val encoder: Encoder[CreateTokenRequest] = deriveEncoder
}

/**
 * Response with created token (includes the actual token - only shown once).
 */
final case class CreateTokenResponse(
  id: String,
  name: String,
  token: String,  // Full token - only returned on creation
  scopes: Seq[String],
  collections: Option[Seq[String]],
  expiresAt: Option[Instant]
)

object CreateTokenResponse {
  implicit val encoder: Encoder[CreateTokenResponse] = deriveEncoder
  implicit val decoder: Decoder[CreateTokenResponse] = deriveDecoder
}

/**
 * Token info response (does not include actual token).
 */
final case class TokenInfoResponse(
  id: String,
  name: String,
  tokenPrefix: String,
  scopes: Seq[String],
  collections: Option[Seq[String]],
  expiresAt: Option[Instant],
  lastUsedAt: Option[Instant],
  createdAt: Instant
)

object TokenInfoResponse {
  implicit val encoder: Encoder[TokenInfoResponse] = deriveEncoder
  implicit val decoder: Decoder[TokenInfoResponse] = deriveDecoder

  def fromAccessToken(token: AccessToken): TokenInfoResponse = {
    TokenInfoResponse(
      id = token.id,
      name = token.name,
      tokenPrefix = token.tokenPrefix,
      scopes = token.scopes.toSeq,
      collections = token.collections.map(_.toSeq),
      expiresAt = token.expiresAt,
      lastUsedAt = token.lastUsedAt,
      createdAt = token.createdAt
    )
  }
}

/**
 * Token list response.
 */
final case class TokenListResponse(
  tokens: Seq[TokenInfoResponse],
  total: Int
)

object TokenListResponse {
  implicit val encoder: Encoder[TokenListResponse] = deriveEncoder
  implicit val decoder: Decoder[TokenListResponse] = deriveDecoder
}

/**
 * HTTP routes for access token management.
 */
object TokenRoutes {

  def routes(tokenRegistry: AccessTokenRegistry): AuthedRoutes[Principal, IO] = AuthedRoutes.of[Principal, IO] {

    // POST /api/v1/tokens - Create access token (admin only)
    case (req @ POST -> Root / "api" / "v1" / "tokens") as principal =>
      Authz.requireAdmin(principal) {
        req.as[CreateTokenRequest].flatMap { body =>
          val invalidScopes = body.scopes.filterNot(TokenScope.validate)
          val expiresAt = body.expiresAt.traverse(s => Either.catchNonFatal(Instant.parse(s.trim)))
          (invalidScopes, expiresAt) match {
            case (invalid, _) if invalid.nonEmpty =>
              BadRequest(ErrorResponse.badRequest(s"Invalid scopes: ${invalid.mkString(", ")}").asJson)
            case (_, Left(_)) =>
              BadRequest(ErrorResponse.badRequest("expiresAt must be an ISO-8601 timestamp, like 2025-01-01T00:00:00Z").asJson)
            case (_, Right(expires)) =>
              tokenRegistry.create(
                name = body.name,
                scopes = body.scopes.toSet,
                collections = body.collections.map(_.toSet),
                createdBy = principal.userId,
                expiresAt = expires
              ).flatMap { case (token, fullToken) =>
                Created(CreateTokenResponse(
                  id = token.id,
                  name = token.name,
                  token = fullToken,
                  scopes = token.scopes.toSeq,
                  collections = token.collections.map(_.toSeq),
                  expiresAt = token.expiresAt
                ).asJson)
              }
          }
        }
      }

    // GET /api/v1/tokens - List all tokens (admin only)
    case GET -> Root / "api" / "v1" / "tokens" as principal =>
      Authz.requireAdmin(principal) {
        for {
          tokens <- tokenRegistry.list()
          response <- Ok(TokenListResponse(
            tokens = tokens.map(TokenInfoResponse.fromAccessToken),
            total = tokens.size
          ).asJson)
        } yield response
      }

    // GET /api/v1/tokens/:id - Get token info (admin only)
    case GET -> Root / "api" / "v1" / "tokens" / id as principal =>
      Authz.requireAdmin(principal) {
        for {
          tokenOpt <- Params.uuid(id).flatTraverse(uuid => tokenRegistry.getById(uuid.toString))
          response <- tokenOpt match {
            case Some(token) =>
              Ok(TokenInfoResponse.fromAccessToken(token).asJson)
            case None =>
              NotFound(ErrorResponse.notFound(s"Token $id not found").asJson)
          }
        } yield response
      }

    // DELETE /api/v1/tokens/:id - Revoke token (admin only)
    case DELETE -> Root / "api" / "v1" / "tokens" / id as principal =>
      Authz.requireAdmin(principal) {
        for {
          result <- Params.uuid(id).fold(IO.pure(false))(uuid => tokenRegistry.delete(uuid.toString))
          response <- if (result) {
            NoContent()
          } else {
            NotFound(ErrorResponse.notFound(s"Token $id not found").asJson)
          }
        } yield response
      }
  }

}

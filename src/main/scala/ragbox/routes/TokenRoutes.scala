package ragbox.routes

import cats.effect.IO
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.headers.`WWW-Authenticate`
import ragbox.auth._
import ragbox.model.ErrorResponse
import ragbox.model.Codecs._

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
  implicit val instantEncoder: Encoder[Instant] = Encoder.encodeString.contramap(_.toString)
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
  implicit val instantEncoder: Encoder[Instant] = Encoder.encodeString.contramap(_.toString)
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

  /**
   * Helper to create proper Unauthorized response with WWW-Authenticate header.
   */
  private def unauthorized(message: String): IO[Response[IO]] = {
    Unauthorized(`WWW-Authenticate`(Challenge("Bearer", "ragbox")))
      .map(_.withEntity(ErrorResponse("unauthorized", message).asJson))
  }

  def routes(
    tokenRegistry: AccessTokenRegistry,
    authService: AuthService
  ): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // POST /api/v1/tokens - Create access token (admin only)
    case req @ POST -> Root / "api" / "v1" / "tokens" =>
      requireAdmin(req, authService) { userId =>
        for {
          body <- req.as[CreateTokenRequest]
          expiresAt = body.expiresAt.map(Instant.parse)
          result <- tokenRegistry.create(
            name = body.name,
            scopes = body.scopes.toSet,
            collections = body.collections.map(_.toSet),
            createdBy = Some(userId),
            expiresAt = expiresAt
          ).attempt
          response <- result match {
            case Right((token, fullToken)) =>
              Created(CreateTokenResponse(
                id = token.id,
                name = token.name,
                token = fullToken,
                scopes = token.scopes.toSeq,
                collections = token.collections.map(_.toSeq),
                expiresAt = token.expiresAt
              ).asJson)
            case Left(e: IllegalArgumentException) =>
              BadRequest(ErrorResponse.badRequest(e.getMessage).asJson)
            case Left(e) =>
              InternalServerError(ErrorResponse.internalError(
                "Failed to create token",
                Some(e.getMessage)
              ).asJson)
          }
        } yield response
      }

    // GET /api/v1/tokens - List all tokens (admin only)
    case req @ GET -> Root / "api" / "v1" / "tokens" =>
      requireAdmin(req, authService) { _ =>
        for {
          tokens <- tokenRegistry.list()
          response <- Ok(TokenListResponse(
            tokens = tokens.map(TokenInfoResponse.fromAccessToken),
            total = tokens.size
          ).asJson)
        } yield response
      }

    // GET /api/v1/tokens/:id - Get token info (admin only)
    case req @ GET -> Root / "api" / "v1" / "tokens" / id =>
      requireAdmin(req, authService) { _ =>
        for {
          tokenOpt <- tokenRegistry.getById(id)
          response <- tokenOpt match {
            case Some(token) =>
              Ok(TokenInfoResponse.fromAccessToken(token).asJson)
            case None =>
              NotFound(ErrorResponse.notFound(s"Token $id not found").asJson)
          }
        } yield response
      }

    // DELETE /api/v1/tokens/:id - Revoke token (admin only)
    case req @ DELETE -> Root / "api" / "v1" / "tokens" / id =>
      requireAdmin(req, authService) { _ =>
        for {
          result <- tokenRegistry.delete(id)
          response <- if (result) {
            NoContent()
          } else {
            NotFound(ErrorResponse.notFound(s"Token $id not found").asJson)
          }
        } yield response
      }
  }

  /**
   * Extract JWT token from Authorization header.
   */
  private def extractToken(req: Request[IO]): Option[String] = {
    req.headers.get(org.typelevel.ci.CIString("Authorization"))
      .map(_.head.value)
      .filter(_.startsWith("Bearer "))
      .map(_.substring(7))
  }

  /**
   * Require admin role for an operation.
   * Returns the user ID if authorized.
   */
  private def requireAdmin(req: Request[IO], authService: AuthService)(
    operation: Int => IO[Response[IO]]
  ): IO[Response[IO]] = {
    extractToken(req) match {
      case Some(token) =>
        authService.validateToken(token) match {
          case TokenValidationResult.Valid(userId, _, role) if role == UserRole.Admin =>
            operation(userId)
          case TokenValidationResult.Valid(_, _, _) =>
            Forbidden(ErrorResponse("forbidden", "Admin access required").asJson)
          case TokenValidationResult.Expired =>
            unauthorized("Token expired")
          case TokenValidationResult.Invalid(msg) =>
            unauthorized(msg)
        }
      case None =>
        unauthorized("Missing authorization token")
    }
  }
}

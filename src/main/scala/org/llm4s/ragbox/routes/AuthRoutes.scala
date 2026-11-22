package org.llm4s.ragbox.routes

import cats.effect.IO
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.headers.`WWW-Authenticate`
import org.http4s.Challenge
import org.llm4s.ragbox.auth._
import org.llm4s.ragbox.config.{AuthMode, SecurityConfig}
import org.llm4s.ragbox.model.ErrorResponse

/**
 * Request to login.
 */
final case class LoginRequest(
  username: String,
  password: String
)

object LoginRequest {
  implicit val decoder: Decoder[LoginRequest] = deriveDecoder
  implicit val encoder: Encoder[LoginRequest] = deriveEncoder
}

/**
 * Response from successful login.
 */
final case class LoginResponse(
  token: String,
  username: String,
  role: String,
  expiresIn: Long
)

object LoginResponse {
  implicit val encoder: Encoder[LoginResponse] = deriveEncoder
  implicit val decoder: Decoder[LoginResponse] = deriveDecoder
}

/**
 * Response with current user info.
 */
final case class UserInfoResponse(
  id: Int,
  username: String,
  role: String
)

object UserInfoResponse {
  implicit val encoder: Encoder[UserInfoResponse] = deriveEncoder
  implicit val decoder: Decoder[UserInfoResponse] = deriveDecoder
}

/**
 * Request to create a user.
 */
final case class CreateUserRequest(
  username: String,
  password: String,
  role: Option[String]
)

object CreateUserRequest {
  implicit val decoder: Decoder[CreateUserRequest] = deriveDecoder
  implicit val encoder: Encoder[CreateUserRequest] = deriveEncoder
}

/**
 * Response for user creation.
 */
final case class CreateUserResponse(
  id: Int,
  username: String,
  role: String
)

object CreateUserResponse {
  implicit val encoder: Encoder[CreateUserResponse] = deriveEncoder
  implicit val decoder: Decoder[CreateUserResponse] = deriveDecoder
}

/**
 * Request to change password.
 */
final case class ChangePasswordRequest(
  currentPassword: String,
  newPassword: String
)

object ChangePasswordRequest {
  implicit val decoder: Decoder[ChangePasswordRequest] = deriveDecoder
  implicit val encoder: Encoder[ChangePasswordRequest] = deriveEncoder
}

/**
 * User list response.
 */
final case class UserListResponse(
  users: Seq[UserInfoResponse],
  total: Int
)

object UserListResponse {
  implicit val encoder: Encoder[UserListResponse] = deriveEncoder
  implicit val decoder: Decoder[UserListResponse] = deriveDecoder
}

/**
 * Response of `GET /api/v1/auth/mode`: how clients (the admin UI) should authenticate.
 *
 * @param mode           configured auth mode: open, basic or oauth
 * @param authRequired   false only in open mode without an API key
 * @param methods        accepted interactive login methods: `password`, `sso`, `api-key`
 * @param oauthLoginUrl  where to send the browser for SSO (oauth mode)
 */
final case class AuthModeResponse(
  mode: String,
  authRequired: Boolean,
  methods: Seq[String],
  oauthLoginUrl: Option[String]
)

object AuthModeResponse {
  implicit val encoder: Encoder[AuthModeResponse] = deriveEncoder
  implicit val decoder: Decoder[AuthModeResponse] = deriveDecoder

  def from(security: SecurityConfig): AuthModeResponse = {
    val apiKey = security.apiKey.exists(_.nonEmpty)
    val mode = security.auth.mode
    val methods =
      (if (mode == AuthMode.Basic) Seq("password") else Nil) ++
        (if (mode == AuthMode.OAuth) Seq("sso") else Nil) ++
        (if (apiKey) Seq("api-key") else Nil)
    AuthModeResponse(
      mode = mode match {
        case AuthMode.Open => "open"
        case AuthMode.Basic => "basic"
        case AuthMode.OAuth => "oauth"
      },
      authRequired = mode != AuthMode.Open || apiKey,
      methods = methods,
      oauthLoginUrl = if (mode == AuthMode.OAuth) Some("/api/v1/oauth/login") else None
    )
  }
}

/**
 * Response of `GET /api/v1/auth/me`: the authenticated principal.
 */
final case class PrincipalResponse(
  id: String,
  username: String,
  role: String,
  isAdmin: Boolean,
  authMethod: String,
  groups: Seq[String],
  scopes: Option[Seq[String]],
  collections: Option[Seq[String]],
  userId: Option[Int]
)

object PrincipalResponse {
  implicit val encoder: Encoder[PrincipalResponse] = deriveEncoder
  implicit val decoder: Decoder[PrincipalResponse] = deriveDecoder

  def from(p: Principal): PrincipalResponse = PrincipalResponse(
    id = p.id,
    username = p.name,
    role = p.role,
    isAdmin = p.isAdmin,
    authMethod = p.method.name,
    groups = p.groups.toSeq.sorted,
    scopes = p.scopes.map(_.toSeq.sorted),
    collections = p.collections.map(_.toSeq.sorted),
    userId = p.userId
  )
}

/**
 * HTTP routes for authentication and user management.
 *
 * [[publicRoutes]] (login, mode discovery) are mounted in front of the authenticator;
 * [[routes]] need an authenticated [[Principal]].
 */
object AuthRoutes {

  private def unauthorized(message: String): IO[Response[IO]] =
    Unauthorized(`WWW-Authenticate`(Challenge("Bearer", "ragbox")))
      .map(_.withEntity(ErrorResponse("unauthorized", message).asJson))

  /**
   * Unauthenticated endpoints:
   *  - `POST /api/v1/auth/login` - exchange username/password for a JWT (basic mode only)
   *  - `GET  /api/v1/auth/mode`  - which login methods the server accepts
   */
  def publicRoutes(authService: AuthService, security: SecurityConfig): HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ POST -> Root / "api" / "v1" / "auth" / "login" =>
      if (security.auth.mode != AuthMode.Basic) {
        // In other modes the bootstrap admin may still have a development password, and the
        // authenticator would not accept the JWT anyway.
        BadRequest(ErrorResponse.badRequest(
          "Password login is only available when AUTH_MODE=basic"
        ).asJson)
      } else {
        for {
          body <- req.as[LoginRequest]
          result <- authService.authenticate(body.username, body.password)
          response <- result match {
            case AuthResult.Success(user, token) =>
              Ok(LoginResponse(
                token = token,
                username = user.username,
                role = user.role.name,
                expiresIn = security.auth.jwtExpiration
              ).asJson)
            case AuthResult.Failure(_) =>
              // Don't reveal whether the user exists
              unauthorized("Invalid username or password")
          }
        } yield response
      }

    case GET -> Root / "api" / "v1" / "auth" / "mode" =>
      Ok(AuthModeResponse.from(security).asJson)
  }

  /**
   * Authenticated endpoints:
   *  - `GET    /api/v1/auth/me`                 - the current principal
   *  - `PUT    /api/v1/auth/password`           - change own password (password logins)
   *  - `POST   /api/v1/users`                   - create a user (admin)
   *  - `GET    /api/v1/users`                   - list users (admin)
   *  - `DELETE /api/v1/users/{id}`              - delete a user (admin)
   *  - `PUT    /api/v1/users/{id}/password`     - reset a password (admin)
   */
  def routes(authService: AuthService, userRegistry: UserRegistry): AuthedRoutes[Principal, IO] =
    AuthedRoutes.of[Principal, IO] {

      case GET -> Root / "api" / "v1" / "auth" / "me" as principal =>
        Ok(PrincipalResponse.from(principal).asJson)

      case (req @ PUT -> Root / "api" / "v1" / "auth" / "password") as principal =>
        principal.userId match {
          case Some(userId) =>
            for {
              body <- req.as[ChangePasswordRequest]
              result <- authService.changePassword(userId, body.currentPassword, body.newPassword)
              response <- result match {
                case Right(_) => Ok(Map("message" -> "Password changed successfully").asJson)
                case Left(msg) => BadRequest(ErrorResponse.badRequest(msg).asJson)
              }
            } yield response
          case None =>
            BadRequest(ErrorResponse.badRequest("Only password-login users can change a password").asJson)
        }

      case (req @ POST -> Root / "api" / "v1" / "users") as principal =>
        Authz.requireAdmin(principal) {
          for {
            body <- req.as[CreateUserRequest]
            role = body.role.map(UserRole.fromString).getOrElse(UserRole.User)
            result <- authService.createUser(body.username, body.password, role)
            response <- result match {
              case Right(user) =>
                Created(CreateUserResponse(user.id, user.username, user.role.name).asJson)
              case Left(msg) =>
                BadRequest(ErrorResponse.badRequest(msg).asJson)
            }
          } yield response
        }

      case GET -> Root / "api" / "v1" / "users" as principal =>
        Authz.requireAdmin(principal) {
          for {
            users <- userRegistry.list()
            response <- Ok(UserListResponse(
              users = users.map(u => UserInfoResponse(u.id, u.username, u.role.name)),
              total = users.size
            ).asJson)
          } yield response
        }

      case DELETE -> Root / "api" / "v1" / "users" / IntVar(id) as principal =>
        Authz.requireAdmin(principal) {
          userRegistry.delete(id).flatMap { deleted =>
            if (deleted) NoContent()
            else NotFound(ErrorResponse.notFound(s"User $id not found").asJson)
          }
        }

      case (req @ PUT -> Root / "api" / "v1" / "users" / IntVar(id) / "password") as principal =>
        Authz.requireAdmin(principal) {
          for {
            body <- req.as[Map[String, String]]
            newPassword = body.getOrElse("password", "")
            result <-
              if (newPassword.isEmpty) IO.pure(Left("Password is required"))
              else authService.resetPassword(id, newPassword)
            response <- result match {
              case Right(_) => Ok(Map("message" -> "Password reset successfully").asJson)
              case Left(msg) => BadRequest(ErrorResponse.badRequest(msg).asJson)
            }
          } yield response
        }
    }
}

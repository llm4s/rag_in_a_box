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
import org.http4s.Challenge
import ragbox.auth._
import ragbox.model.ErrorResponse
import ragbox.model.Codecs._

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
 * HTTP routes for authentication.
 */
object AuthRoutes {

  /**
   * Helper to create proper Unauthorized response with WWW-Authenticate header.
   */
  private def unauthorized(message: String): IO[Response[IO]] = {
    Unauthorized(`WWW-Authenticate`(Challenge("Bearer", "ragbox")))
      .map(_.withEntity(ErrorResponse("unauthorized", message).asJson))
  }

  def routes(authService: AuthService, userRegistry: UserRegistry, jwtExpiration: Long): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // POST /api/v1/auth/login - Login and get JWT token
    case req @ POST -> Root / "api" / "v1" / "auth" / "login" =>
      for {
        body <- req.as[LoginRequest]
        result <- authService.authenticate(body.username, body.password)
        response <- result match {
          case AuthResult.Success(user, token) =>
            Ok(LoginResponse(
              token = token,
              username = user.username,
              role = user.role.name,
              expiresIn = jwtExpiration
            ).asJson)
          case AuthResult.Failure(message) =>
            unauthorized(message)
        }
      } yield response

    // GET /api/v1/auth/me - Get current user info (requires auth)
    case req @ GET -> Root / "api" / "v1" / "auth" / "me" =>
      extractToken(req) match {
        case Some(token) =>
          authService.validateToken(token) match {
            case TokenValidationResult.Valid(userId, username, role) =>
              Ok(UserInfoResponse(userId, username, role.name).asJson)
            case TokenValidationResult.Expired =>
              unauthorized("Token expired")
            case TokenValidationResult.Invalid(msg) =>
              unauthorized(msg)
          }
        case None =>
          unauthorized("Missing authorization token")
      }

    // PUT /api/v1/auth/password - Change current user's password
    case req @ PUT -> Root / "api" / "v1" / "auth" / "password" =>
      extractToken(req) match {
        case Some(token) =>
          authService.validateToken(token) match {
            case TokenValidationResult.Valid(userId, _, _) =>
              for {
                body <- req.as[ChangePasswordRequest]
                result <- authService.changePassword(userId, body.currentPassword, body.newPassword)
                response <- result match {
                  case Right(_) =>
                    Ok(Map("message" -> "Password changed successfully").asJson)
                  case Left(msg) =>
                    BadRequest(ErrorResponse.badRequest(msg).asJson)
                }
              } yield response
            case TokenValidationResult.Expired =>
              unauthorized("Token expired")
            case TokenValidationResult.Invalid(msg) =>
              unauthorized(msg)
          }
        case None =>
          unauthorized("Missing authorization token")
      }

    // POST /api/v1/users - Create a new user (admin only)
    case req @ POST -> Root / "api" / "v1" / "users" =>
      requireAdmin(req, authService) {
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

    // GET /api/v1/users - List all users (admin only)
    case req @ GET -> Root / "api" / "v1" / "users" =>
      requireAdmin(req, authService) {
        for {
          users <- userRegistry.list()
          response <- Ok(UserListResponse(
            users = users.map(u => UserInfoResponse(u.id, u.username, u.role.name)),
            total = users.size
          ).asJson)
        } yield response
      }

    // DELETE /api/v1/users/:id - Delete a user (admin only)
    case req @ DELETE -> Root / "api" / "v1" / "users" / IntVar(id) =>
      requireAdmin(req, authService) {
        for {
          result <- userRegistry.delete(id)
          response <- if (result) {
            NoContent()
          } else {
            NotFound(ErrorResponse.notFound(s"User $id not found").asJson)
          }
        } yield response
      }

    // PUT /api/v1/users/:id/password - Reset user password (admin only)
    case req @ PUT -> Root / "api" / "v1" / "users" / IntVar(id) / "password" =>
      requireAdmin(req, authService) {
        for {
          body <- req.as[Map[String, String]]
          newPassword = body.getOrElse("password", "")
          result <- if (newPassword.isEmpty) {
            IO.pure(Left("Password is required"))
          } else {
            authService.resetPassword(id, newPassword)
          }
          response <- result match {
            case Right(_) =>
              Ok(Map("message" -> "Password reset successfully").asJson)
            case Left(msg) =>
              BadRequest(ErrorResponse.badRequest(msg).asJson)
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
   */
  private def requireAdmin(req: Request[IO], authService: AuthService)(
    operation: IO[Response[IO]]
  ): IO[Response[IO]] = {
    extractToken(req) match {
      case Some(token) =>
        authService.validateToken(token) match {
          case TokenValidationResult.Valid(_, _, role) if role == UserRole.Admin =>
            operation
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

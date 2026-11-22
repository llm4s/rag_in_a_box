package org.llm4s.ragbox.auth

import cats.effect.IO
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser._
import io.circe.syntax._
import org.llm4s.ragbox.config.{AuthConfig, AuthMode, PasswordPolicy}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.Try

/**
 * JWT token payload.
 */
final case class JwtPayload(
  sub: String,         // Subject (user ID)
  username: String,
  role: String,
  exp: Long,           // Expiration timestamp
  iat: Long,           // Issued at timestamp
  iss: String,         // Issuer (AuthConfig.jwtIssuer)
  aud: String          // Audience (AuthConfig.jwtAudience)
)

object JwtPayload {
  implicit val encoder: Encoder[JwtPayload] = deriveEncoder
  implicit val decoder: Decoder[JwtPayload] = deriveDecoder
}

/**
 * Authentication result.
 */
sealed trait AuthResult
object AuthResult {
  final case class Success(user: User, token: String) extends AuthResult
  final case class Failure(message: String) extends AuthResult
}

/**
 * Token validation result.
 */
sealed trait TokenValidationResult
object TokenValidationResult {
  final case class Valid(userId: Int, username: String, role: UserRole) extends TokenValidationResult
  final case class Invalid(message: String) extends TokenValidationResult
  case object Expired extends TokenValidationResult
}

/**
 * Authentication service: password hashing ([[PasswordHashing]]), the bootstrap admin account,
 * password logins and JWT issue/validation.
 */
class AuthService(
  userRegistry: UserRegistry,
  authConfig: AuthConfig
) {

  private val logger: Logger[IO] = Slf4jLogger.getLoggerFromClass[IO](classOf[AuthService])

  private val iterations = authConfig.passwordHashIterations
  private val policy = PasswordPolicy(authConfig.passwordMinLength)

  private val jwtHeader = """{"alg":"HS256","typ":"JWT"}"""
  private val base64Encoder = Base64.getUrlEncoder.withoutPadding()
  private val base64Decoder = Base64.getUrlDecoder

  /** Verified against for unknown users, so a login takes as long whether or not the user exists. */
  private lazy val dummyHash = PasswordHashing.hash("ragbox-timing-equaliser", iterations)

  /**
   * Prepare the user table at startup (basic mode only; the other modes have no password login).
   *
   *  - No users yet: create the admin account `ADMIN_USERNAME` with `ADMIN_PASSWORD` (config
   *    validation makes sure basic mode has a real one).
   *  - The admin account still has the password `admin` that earlier versions seeded in open
   *    mode: reset it to `ADMIN_PASSWORD`, so a well-known password never survives into basic
   *    mode.
   *
   * No account is ever created with a default password.
   */
  def initialize(): IO[Unit] =
    if (authConfig.mode != AuthMode.Basic) IO.unit
    else {
      val adminUsername = authConfig.basic.adminUsername
      IO.fromOption(authConfig.basic.adminPassword.filter(_.nonEmpty))(
        new IllegalStateException("ADMIN_PASSWORD must be set when auth.mode=basic")
      ).flatMap { adminPassword =>
        userRegistry.count().flatMap {
          case 0 =>
            hash(adminPassword).flatMap(userRegistry.create(adminUsername, _, UserRole.Admin)).flatMap { user =>
              logger.info(s"Created admin user '${user.username}' (password from ADMIN_PASSWORD)")
            }
          case _ =>
            userRegistry.getByUsername(adminUsername).flatMap {
              case Some(user) =>
                verify(AuthService.LegacyDefaultPassword, user.passwordHash).flatMap {
                  case false => IO.unit
                  case true =>
                    hash(adminPassword).flatMap(userRegistry.updatePassword(user.id, _)) *>
                      logger.warn(
                        s"User '$adminUsername' still had the old default password 'admin'; " +
                          "it has been reset to ADMIN_PASSWORD"
                      )
                }
              case None => IO.unit
            }
        }
      }
    }

  /**
   * Authenticate user with username and password. Unknown users and wrong passwords give the
   * same result and take as long. A successful login upgrades a legacy or weaker password hash.
   */
  def authenticate(username: String, password: String): IO[AuthResult] =
    userRegistry.getByUsername(username).flatMap {
      case Some(user) =>
        verify(password, user.passwordHash).flatMap {
          case true =>
            val upgrade =
              if (PasswordHashing.needsRehash(user.passwordHash, iterations))
                hash(password).flatMap(userRegistry.updatePassword(user.id, _)).void
                  .handleErrorWith(e => logger.warn(s"Could not upgrade the password hash of '${user.username}': ${e.getMessage}"))
              else IO.unit
            upgrade.as(AuthResult.Success(user, generateToken(user)))
          case false => IO.pure(AuthResult.Failure(AuthService.LoginFailed))
        }
      case None =>
        verify(password, dummyHash).as(AuthResult.Failure(AuthService.LoginFailed))
    }

  /**
   * Validate a JWT: the header must name HS256, the signature must match, and the token must
   * be unexpired and carry this server's `iss` and `aud`.
   */
  def validateToken(token: String): TokenValidationResult = {
    val parts = token.split("\\.", -1)
    if (parts.length != 3) {
      return TokenValidationResult.Invalid("Invalid token format")
    }

    val headerB64 = parts(0)
    val payloadB64 = parts(1)
    val signature = parts(2)

    // Only HS256 is ever issued; anything else (none, RS256, ...) is rejected outright
    val algOk = Try(new String(base64Decoder.decode(headerB64), StandardCharsets.UTF_8)).toOption
      .flatMap(parse(_).toOption)
      .exists(_.hcursor.get[String]("alg").contains("HS256"))
    if (!algOk) {
      return TokenValidationResult.Invalid("Unsupported token algorithm")
    }

    // Verify signature (constant-time, so response timing does not reveal how much matched)
    val expectedSignature = sign(s"$headerB64.$payloadB64")
    if (!MessageDigest.isEqual(
        signature.getBytes(StandardCharsets.US_ASCII),
        expectedSignature.getBytes(StandardCharsets.US_ASCII)
      )) {
      return TokenValidationResult.Invalid("Invalid signature")
    }

    // Decode payload
    try {
      val payloadJson = new String(base64Decoder.decode(payloadB64), StandardCharsets.UTF_8)
      parse(payloadJson).flatMap(_.as[JwtPayload]) match {
        case Right(payload) =>
          val now = Instant.now().getEpochSecond
          if (payload.iss != authConfig.jwtIssuer) {
            TokenValidationResult.Invalid("Invalid token issuer")
          } else if (payload.aud != authConfig.jwtAudience) {
            TokenValidationResult.Invalid("Invalid token audience")
          } else if (payload.exp < now) {
            TokenValidationResult.Expired
          } else {
            TokenValidationResult.Valid(
              userId = payload.sub.toInt,
              username = payload.username,
              role = UserRole.fromString(payload.role)
            )
          }
        case Left(_) =>
          TokenValidationResult.Invalid("Invalid payload format")
      }
    } catch {
      case _: Exception =>
        TokenValidationResult.Invalid("Failed to decode token")
    }
  }

  /**
   * Create a new user (admin only).
   */
  def createUser(username: String, password: String, role: UserRole): IO[Either[String, User]] =
    if (username.trim.isEmpty) IO.pure(Left("Username is required"))
    else
      policy.check(password) match {
        case Left(error) => IO.pure(Left(error))
        case Right(_) =>
          userRegistry.exists(username).flatMap {
            case true => IO.pure(Left("Username already exists"))
            case false => hash(password).flatMap(userRegistry.create(username, _, role)).map(Right(_))
          }
      }

  /**
   * Change user password.
   */
  def changePassword(userId: Int, oldPassword: String, newPassword: String): IO[Either[String, Unit]] =
    userRegistry.getById(userId).flatMap {
      case Some(user) =>
        verify(oldPassword, user.passwordHash).flatMap {
          case false => IO.pure(Left("Invalid current password"))
          case true =>
            policy.check(newPassword) match {
              case Left(error) => IO.pure(Left(error))
              case Right(_) => hash(newPassword).flatMap(userRegistry.updatePassword(userId, _)).as(Right(()))
            }
        }
      case None =>
        IO.pure(Left("User not found"))
    }

  /**
   * Reset user password (admin only).
   */
  def resetPassword(userId: Int, newPassword: String): IO[Either[String, Unit]] =
    policy.check(newPassword) match {
      case Left(error) => IO.pure(Left(error))
      case Right(_) =>
        hash(newPassword).flatMap(userRegistry.updatePassword(userId, _)).map { success =>
          if (success) Right(())
          else Left("User not found")
        }
    }

  /**
   * Generate a JWT token for the user.
   */
  def generateToken(user: User): String = {
    val now = Instant.now().getEpochSecond
    val payload = JwtPayload(
      sub = user.id.toString,
      username = user.username,
      role = user.role.name,
      exp = now + authConfig.jwtExpiration,
      iat = now,
      iss = authConfig.jwtIssuer,
      aud = authConfig.jwtAudience
    )

    val headerB64 = base64Encoder.encodeToString(jwtHeader.getBytes(StandardCharsets.UTF_8))
    val payloadB64 = base64Encoder.encodeToString(payload.asJson.noSpaces.getBytes(StandardCharsets.UTF_8))
    val signature = sign(s"$headerB64.$payloadB64")

    s"$headerB64.$payloadB64.$signature"
  }

  /** Hash a password (PBKDF2-HMAC-SHA256 with the configured iterations). */
  def hashPassword(password: String): String = PasswordHashing.hash(password, iterations)

  /** Verify a password against a stored hash (current or legacy format). */
  def verifyPassword(password: String, storedHash: String): Boolean = PasswordHashing.verify(password, storedHash)

  // Key derivation is deliberately slow: keep it off the compute pool
  private def hash(password: String): IO[String] = IO.blocking(hashPassword(password))
  private def verify(password: String, storedHash: String): IO[Boolean] = IO.blocking(verifyPassword(password, storedHash))

  private def sign(data: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(authConfig.jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    val signature = mac.doFinal(data.getBytes(StandardCharsets.UTF_8))
    base64Encoder.encodeToString(signature)
  }
}

object AuthService {

  /** The admin password earlier versions seeded in open mode; never accepted in basic mode. */
  val LegacyDefaultPassword = "admin"

  private val LoginFailed = "Invalid username or password"

  /**
   * Create and initialize an auth service.
   */
  def apply(userRegistry: UserRegistry, authConfig: AuthConfig): IO[AuthService] = {
    val service = new AuthService(userRegistry, authConfig)
    service.initialize().as(service)
  }
}

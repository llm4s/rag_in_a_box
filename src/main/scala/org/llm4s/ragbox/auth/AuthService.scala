package org.llm4s.ragbox.auth

import cats.effect.IO
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser._
import io.circe.syntax._
import org.llm4s.ragbox.config.{AuthConfig, AuthMode, BasicAuthConfig}

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * JWT token payload.
 */
final case class JwtPayload(
  sub: String,         // Subject (user ID)
  username: String,
  role: String,
  exp: Long,           // Expiration timestamp
  iat: Long            // Issued at timestamp
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
 * Authentication service.
 *
 * Handles password hashing, JWT token generation and validation.
 */
class AuthService(
  userRegistry: UserRegistry,
  authConfig: AuthConfig
) {

  private val jwtHeader = """{"alg":"HS256","typ":"JWT"}"""
  private val base64Encoder = Base64.getUrlEncoder.withoutPadding()
  private val base64Decoder = Base64.getUrlDecoder

  /**
   * Initialize the auth service.
   * Creates the default admin user if it doesn't exist (for basic auth mode).
   */
  def initialize(): IO[Unit] = {
    authConfig.mode match {
      case AuthMode.Basic =>
        authConfig.basic.adminPassword match {
          case Some(password) =>
            userRegistry.exists(authConfig.basic.adminUsername).flatMap {
              case true => IO.unit  // Admin already exists
              case false =>
                val hash = hashPassword(password)
                userRegistry.create(authConfig.basic.adminUsername, hash, UserRole.Admin).void
            }
          case None =>
            IO.raiseError(new RuntimeException(
              "ADMIN_PASSWORD must be set when auth.mode=basic"
            ))
        }
      case _ => IO.unit
    }
  }

  /**
   * Authenticate user with username and password.
   */
  def authenticate(username: String, password: String): IO[AuthResult] = {
    userRegistry.getByUsername(username).map {
      case Some(user) if verifyPassword(password, user.passwordHash) =>
        val token = generateToken(user)
        AuthResult.Success(user, token)
      case Some(_) =>
        AuthResult.Failure("Invalid password")
      case None =>
        AuthResult.Failure("User not found")
    }
  }

  /**
   * Validate a JWT token.
   */
  def validateToken(token: String): TokenValidationResult = {
    val parts = token.split("\\.")
    if (parts.length != 3) {
      return TokenValidationResult.Invalid("Invalid token format")
    }

    val headerB64 = parts(0)
    val payloadB64 = parts(1)
    val signature = parts(2)

    // Verify signature
    val expectedSignature = sign(s"$headerB64.$payloadB64")
    if (signature != expectedSignature) {
      return TokenValidationResult.Invalid("Invalid signature")
    }

    // Decode payload
    try {
      val payloadJson = new String(base64Decoder.decode(payloadB64), StandardCharsets.UTF_8)
      parse(payloadJson).flatMap(_.as[JwtPayload]) match {
        case Right(payload) =>
          val now = Instant.now().getEpochSecond
          if (payload.exp < now) {
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
  def createUser(username: String, password: String, role: UserRole): IO[Either[String, User]] = {
    userRegistry.exists(username).flatMap {
      case true => IO.pure(Left("Username already exists"))
      case false =>
        val hash = hashPassword(password)
        userRegistry.create(username, hash, role).map(Right(_))
    }
  }

  /**
   * Change user password.
   */
  def changePassword(userId: Int, oldPassword: String, newPassword: String): IO[Either[String, Unit]] = {
    userRegistry.getById(userId).flatMap {
      case Some(user) if verifyPassword(oldPassword, user.passwordHash) =>
        val newHash = hashPassword(newPassword)
        userRegistry.updatePassword(userId, newHash).map(_ => Right(()))
      case Some(_) =>
        IO.pure(Left("Invalid current password"))
      case None =>
        IO.pure(Left("User not found"))
    }
  }

  /**
   * Reset user password (admin only).
   */
  def resetPassword(userId: Int, newPassword: String): IO[Either[String, Unit]] = {
    val newHash = hashPassword(newPassword)
    userRegistry.updatePassword(userId, newHash).map { success =>
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
      iat = now
    )

    val headerB64 = base64Encoder.encodeToString(jwtHeader.getBytes(StandardCharsets.UTF_8))
    val payloadB64 = base64Encoder.encodeToString(payload.asJson.noSpaces.getBytes(StandardCharsets.UTF_8))
    val signature = sign(s"$headerB64.$payloadB64")

    s"$headerB64.$payloadB64.$signature"
  }

  /**
   * Hash a password using PBKDF2-like approach with SHA-256.
   */
  def hashPassword(password: String): String = {
    val salt = new Array[Byte](16)
    new SecureRandom().nextBytes(salt)
    val hash = pbkdf2(password, salt, 10000)
    val saltB64 = Base64.getEncoder.encodeToString(salt)
    val hashB64 = Base64.getEncoder.encodeToString(hash)
    s"$saltB64:$hashB64"
  }

  /**
   * Verify a password against a hash.
   */
  def verifyPassword(password: String, storedHash: String): Boolean = {
    val parts = storedHash.split(":")
    if (parts.length != 2) return false

    try {
      val salt = Base64.getDecoder.decode(parts(0))
      val expectedHash = Base64.getDecoder.decode(parts(1))
      val actualHash = pbkdf2(password, salt, 10000)
      MessageDigest.isEqual(expectedHash, actualHash)
    } catch {
      case _: Exception => false
    }
  }

  private def pbkdf2(password: String, salt: Array[Byte], iterations: Int): Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))

    var result = salt
    for (_ <- 0 until iterations) {
      result = mac.doFinal(result)
    }
    result
  }

  private def sign(data: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(authConfig.jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    val signature = mac.doFinal(data.getBytes(StandardCharsets.UTF_8))
    base64Encoder.encodeToString(signature)
  }
}

object AuthService {

  /**
   * Create and initialize an auth service.
   */
  def apply(userRegistry: UserRegistry, authConfig: AuthConfig): IO[AuthService] = {
    val service = new AuthService(userRegistry, authConfig)
    service.initialize().as(service)
  }
}

package org.llm4s.ragbox.auth

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.{Mac, SecretKeyFactory}
import javax.crypto.spec.{PBEKeySpec, SecretKeySpec}
import scala.util.Try

/**
 * Password hashing with PBKDF2-HMAC-SHA256 (`SecretKeyFactory`).
 *
 * Hashes are stored as `pbkdf2-sha256$<iterations>$<base64 salt>$<base64 hash>`. Hashes in the
 * format of earlier versions (`<base64 salt>:<base64 hash>`, an iterated HMAC that was not real
 * PBKDF2) still verify; [[needsRehash]] tells the caller to replace them, which `AuthService`
 * does on the next successful login.
 */
object PasswordHashing {

  val Scheme = "pbkdf2-sha256"

  private val SaltBytes = 16
  private val HashBits = 256
  private val LegacyIterations = 100000
  private val random = new SecureRandom()

  def hash(password: String, iterations: Int): String = {
    val salt = new Array[Byte](SaltBytes)
    random.nextBytes(salt)
    val derived = pbkdf2(password, salt, iterations)
    s"$Scheme$$$iterations$$${b64(salt)}$$${b64(derived)}"
  }

  /** Whether `password` matches `stored` (either format). Never throws. */
  def verify(password: String, stored: String): Boolean =
    parse(stored) match {
      case Some(Parsed.Current(iterations, salt, expected)) =>
        Try(MessageDigest.isEqual(expected, pbkdf2(password, salt, iterations))).getOrElse(false)
      case Some(Parsed.Legacy(salt, expected)) =>
        Try(MessageDigest.isEqual(expected, legacyHash(password, salt))).getOrElse(false)
      case None => false
    }

  /** True for legacy hashes and for hashes with fewer than `iterations` rounds. */
  def needsRehash(stored: String, iterations: Int): Boolean =
    parse(stored) match {
      case Some(Parsed.Current(n, _, _)) => n < iterations
      case _ => true
    }

  private enum Parsed:
    case Current(iterations: Int, salt: Array[Byte], hash: Array[Byte])
    case Legacy(salt: Array[Byte], hash: Array[Byte])

  private def parse(stored: String): Option[Parsed] = Try {
    stored.split('$') match {
      case Array(Scheme, n, salt, h) => Some(Parsed.Current(n.toInt, unb64(salt), unb64(h)))
      case _ =>
        stored.split(':') match {
          case Array(salt, h) => Some(Parsed.Legacy(unb64(salt), unb64(h)))
          case _ => None
        }
    }
  }.toOption.flatten.filter {
    case Parsed.Current(n, _, _) => n > 0
    case _ => true
  }

  private def pbkdf2(password: String, salt: Array[Byte], iterations: Int): Array[Byte] = {
    val spec = new PBEKeySpec(password.toCharArray, salt, iterations, HashBits)
    try SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded
    finally spec.clearPassword()
  }

  /** The pre-PBKDF2 scheme: HMAC-SHA256 keyed by the password, applied 100,000 times to the salt. */
  private def legacyHash(password: String, salt: Array[Byte]): Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    var result = salt
    for (_ <- 0 until LegacyIterations) result = mac.doFinal(result)
    result
  }

  /** A hash in the legacy format, for tests of the upgrade path. */
  private[ragbox] def legacyFormat(password: String): String = {
    val salt = new Array[Byte](SaltBytes)
    random.nextBytes(salt)
    s"${Base64.getEncoder.encodeToString(salt)}:${Base64.getEncoder.encodeToString(legacyHash(password, salt))}"
  }

  private def b64(bytes: Array[Byte]): String = Base64.getEncoder.withoutPadding().encodeToString(bytes)
  private def unb64(s: String): Array[Byte] = Base64.getDecoder.decode(s)
}

package org.llm4s.ragbox.testkit

import cats.effect.{IO, Resource}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server

import java.security.*
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.{Base64, UUID}
import scala.concurrent.duration.*

/**
 * A mock OIDC server for testing OAuth2/OIDC flows.
 * Generates valid JWT tokens signed with a test RSA key pair.
 */
class MockOidcServer(
    val port: Int,
    val issuer: String,
    keyPair: KeyPair
):
  private val publicKey = keyPair.getPublic.asInstanceOf[RSAPublicKey]
  private val privateKey = keyPair.getPrivate.asInstanceOf[RSAPrivateKey]
  private val algorithm = Algorithm.RSA256(publicKey, privateKey)
  private val keyId = "test-key-id"

  // OIDC endpoint URLs
  def authorizationEndpoint: String = s"$issuer/authorize"
  def tokenEndpoint: String = s"$issuer/token"
  def userinfoEndpoint: String = s"$issuer/userinfo"
  def jwksUri: String = s"$issuer/.well-known/jwks.json"

  /**
   * Generate a valid ID token for testing.
   */
  def generateIdToken(
      subject: String,
      email: Option[String] = None,
      name: Option[String] = None,
      groups: List[String] = Nil,
      audience: String = "test-client-id",
      expiresIn: FiniteDuration = 1.hour
  ): String =
    val now = Instant.now()
    var builder = JWT
      .create()
      .withKeyId(keyId)
      .withIssuer(issuer)
      .withSubject(subject)
      .withAudience(audience)
      .withIssuedAt(now)
      .withExpiresAt(now.plusSeconds(expiresIn.toSeconds))

    email.foreach(e => builder = builder.withClaim("email", e))
    name.foreach(n => builder = builder.withClaim("name", n))
    if (groups.nonEmpty) {
      import scala.jdk.CollectionConverters.*
      builder = builder.withClaim("groups", groups.asJava)
    }

    builder.sign(algorithm)

  /**
   * Generate an expired ID token for testing.
   */
  def generateExpiredIdToken(
      subject: String,
      audience: String = "test-client-id"
  ): String =
    val now = Instant.now()
    JWT
      .create()
      .withKeyId(keyId)
      .withIssuer(issuer)
      .withSubject(subject)
      .withAudience(audience)
      .withIssuedAt(now.minusSeconds(7200))
      .withExpiresAt(now.minusSeconds(3600)) // Expired 1 hour ago
      .sign(algorithm)

  /**
   * Generate a token with wrong issuer for testing.
   */
  def generateTokenWithWrongIssuer(
      subject: String,
      wrongIssuer: String,
      audience: String = "test-client-id"
  ): String =
    val now = Instant.now()
    JWT
      .create()
      .withKeyId(keyId)
      .withIssuer(wrongIssuer)
      .withSubject(subject)
      .withAudience(audience)
      .withIssuedAt(now)
      .withExpiresAt(now.plusSeconds(3600))
      .sign(algorithm)

  /**
   * Get JWKS JSON for the test key.
   */
  def jwksJson: Json =
    val n = Base64.getUrlEncoder.withoutPadding().encodeToString(publicKey.getModulus.toByteArray)
    val e = Base64.getUrlEncoder.withoutPadding().encodeToString(publicKey.getPublicExponent.toByteArray)

    Json.obj(
      "keys" -> Json.arr(
        Json.obj(
          "kty" -> Json.fromString("RSA"),
          "kid" -> Json.fromString(keyId),
          "use" -> Json.fromString("sig"),
          "alg" -> Json.fromString("RS256"),
          "n" -> Json.fromString(n),
          "e" -> Json.fromString(e)
        )
      )
    )

  /**
   * HTTP routes for the mock server.
   */
  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // JWKS endpoint
    case GET -> Root / ".well-known" / "jwks.json" =>
      Ok(jwksJson)

    // OpenID discovery endpoint
    case GET -> Root / ".well-known" / "openid-configuration" =>
      Ok(Json.obj(
        "issuer" -> Json.fromString(issuer),
        "authorization_endpoint" -> Json.fromString(authorizationEndpoint),
        "token_endpoint" -> Json.fromString(tokenEndpoint),
        "userinfo_endpoint" -> Json.fromString(userinfoEndpoint),
        "jwks_uri" -> Json.fromString(jwksUri)
      ))
  }

  /**
   * Start the mock server as a Resource.
   */
  def serverResource: Resource[IO, Server] =
    import com.comcast.ip4s.*
    val serverPort = this.port
    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"127.0.0.1")
      .withPort(Port.fromInt(serverPort).get)
      .withHttpApp(routes.orNotFound)
      .build

object MockOidcServer:
  /**
   * Create a mock OIDC server with a fresh RSA key pair.
   */
  def create(port: Int = 9999): MockOidcServer =
    val keyGen = KeyPairGenerator.getInstance("RSA")
    keyGen.initialize(2048)
    val keyPair = keyGen.generateKeyPair()
    new MockOidcServer(port, s"http://localhost:$port", keyPair)

  /**
   * Create a mock OIDC server as a Resource that starts and stops automatically.
   */
  def resource(port: Int = 9999): Resource[IO, MockOidcServer] =
    for
      server <- Resource.pure(create(port))
      _ <- server.serverResource
    yield server

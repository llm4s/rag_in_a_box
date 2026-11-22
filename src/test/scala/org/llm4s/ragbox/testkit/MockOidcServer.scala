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
import java.util.concurrent.atomic.AtomicReference
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
  /** The published signing keys, newest (the one tokens are signed with) first. */
  private val keys: AtomicReference[List[(String, KeyPair)]] = new AtomicReference(List("test-key-id" -> keyPair))

  private def keyId: String = keys.get.head._1
  private def algorithm: Algorithm =
    val pair = keys.get.head._2
    Algorithm.RSA256(pair.getPublic.asInstanceOf[RSAPublicKey], pair.getPrivate.asInstanceOf[RSAPrivateKey])

  /** Number of times the JWKS endpoint was fetched. */
  val jwksFetches: java.util.concurrent.atomic.AtomicInteger = new java.util.concurrent.atomic.AtomicInteger(0)

  /**
   * Rotate the signing key: tokens are signed with a new key (with a new `kid`) from now on.
   * With `retirePrevious` the old keys are removed from the JWKS, as a provider does once the
   * tokens they signed have expired.
   *
   * @return the new key id
   */
  def rotateKey(retirePrevious: Boolean): String =
    val newId = s"key-${UUID.randomUUID()}"
    val pair = MockOidcServer.newKeyPair()
    keys.updateAndGet(current => (newId -> pair) :: (if retirePrevious then Nil else current))
    newId

  /** ID token the mock token endpoint will return next (set by tests). */
  val nextIdToken: AtomicReference[Option[String]] = new AtomicReference(None)

  /** Form parameters of every request received by the token endpoint. */
  val tokenRequests: AtomicReference[List[Map[String, String]]] = new AtomicReference(Nil)

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
      expiresIn: FiniteDuration = 1.hour,
      nonce: Option[String] = None,
      emailVerified: Option[Boolean] = None
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
    nonce.foreach(n => builder = builder.withClaim("nonce", n))
    emailVerified.foreach(v => builder = builder.withClaim("email_verified", java.lang.Boolean.valueOf(v)))
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
    Json.obj(
      "keys" -> Json.fromValues(keys.get.map { case (kid, pair) =>
        val publicKey = pair.getPublic.asInstanceOf[RSAPublicKey]
        val n = Base64.getUrlEncoder.withoutPadding().encodeToString(publicKey.getModulus.toByteArray)
        val e = Base64.getUrlEncoder.withoutPadding().encodeToString(publicKey.getPublicExponent.toByteArray)
        Json.obj(
          "kty" -> Json.fromString("RSA"),
          "kid" -> Json.fromString(kid),
          "use" -> Json.fromString("sig"),
          "alg" -> Json.fromString("RS256"),
          "n" -> Json.fromString(n),
          "e" -> Json.fromString(e)
        )
      })
    )

  /**
   * HTTP routes for the mock server.
   */
  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // JWKS endpoint
    case GET -> Root / ".well-known" / "jwks.json" =>
      IO(jwksFetches.incrementAndGet()) *> Ok(jwksJson)

    // Token endpoint: returns whatever ID token the test configured
    case req @ POST -> Root / "token" =>
      req.as[UrlForm].flatMap { form =>
        val params = form.values.view.mapValues(_.headOption.getOrElse("")).toMap
        tokenRequests.updateAndGet(params :: _)
        nextIdToken.get match
          case Some(idToken) =>
            Ok(Json.obj(
              "access_token" -> Json.fromString("mock-access-token"),
              "token_type" -> Json.fromString("Bearer"),
              "expires_in" -> Json.fromLong(3600),
              "id_token" -> Json.fromString(idToken)
            ))
          case None =>
            BadRequest(Json.obj("error" -> Json.fromString("invalid_grant")))
      }

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
    new MockOidcServer(port, s"http://localhost:$port", newKeyPair())

  private[testkit] def newKeyPair(): KeyPair =
    val keyGen = KeyPairGenerator.getInstance("RSA")
    keyGen.initialize(2048)
    keyGen.generateKeyPair()

  /**
   * Create a mock OIDC server as a Resource that starts and stops automatically.
   */
  def resource(port: Int = 9999): Resource[IO, MockOidcServer] =
    for
      server <- Resource.pure(create(port))
      _ <- server.serverResource
    yield server

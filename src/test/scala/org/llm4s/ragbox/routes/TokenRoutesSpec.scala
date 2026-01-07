package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax._
import io.circe.parser._
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.ragbox.auth._
import org.llm4s.ragbox.config.{AuthConfig, AuthMode, BasicAuthConfig}

import java.time.Instant

class TokenRoutesSpec extends AnyFlatSpec with Matchers {

  // Test configuration
  private val testConfig = AuthConfig(
    mode = AuthMode.Basic,
    basic = BasicAuthConfig("admin", Some("adminpass")),
    jwtSecret = "test-secret-key-for-testing-32chars",
    jwtSecretExplicitlySet = true,
    jwtExpiration = 3600L
  )

  // Mock user registry
  private class InMemoryUserRegistry extends UserRegistry(null) {
    private var users = Map[String, User]()
    private var nextId = 1

    override def initialize() = IO.unit
    override def exists(username: String) = IO.pure(users.contains(username))
    override def create(username: String, passwordHash: String, role: UserRole) = IO {
      val user = User(nextId, username, passwordHash, role, Instant.now())
      users = users + (username -> user)
      nextId += 1
      user
    }
    override def getByUsername(username: String) = IO.pure(users.get(username))
    override def getById(id: Int) = IO.pure(users.values.find(_.id == id))
    override def list() = IO.pure(users.values.toSeq)
    override def delete(id: Int) = IO.pure(false)
    override def updatePassword(id: Int, passwordHash: String) = IO.pure(false)
    override def close() = IO.unit
  }

  // Mock token registry that stores tokens in memory
  private class InMemoryTokenRegistry extends AccessTokenRegistry(null) {
    private var tokens = Map[String, (AccessToken, String)]()  // id -> (token, fullToken)

    override def initialize() = IO.unit

    override def create(
      name: String,
      scopes: Set[String],
      collections: Option[Set[String]],
      createdBy: Option[Int],
      expiresAt: Option[Instant]
    ) = IO {
      // Validate scopes
      val invalidScopes = scopes.filterNot(TokenScope.validate)
      if (invalidScopes.nonEmpty) {
        throw new IllegalArgumentException(s"Invalid scopes: ${invalidScopes.mkString(", ")}")
      }

      val id = java.util.UUID.randomUUID().toString
      val fullToken = s"rat_test${id.take(8)}"
      val token = AccessToken(
        id = id,
        name = name,
        tokenPrefix = fullToken.take(12),
        scopes = scopes,
        collections = collections,
        createdBy = createdBy,
        expiresAt = expiresAt,
        lastUsedAt = None,
        createdAt = Instant.now()
      )
      tokens = tokens + (id -> (token, fullToken))
      (token, fullToken)
    }

    override def validate(token: String) = IO.pure {
      tokens.values.find(_._2 == token).map(_._1)
    }

    override def list() = IO.pure(tokens.values.map(_._1).toSeq)

    override def getById(id: String) = IO.pure(tokens.get(id).map(_._1))

    override def delete(id: String) = IO {
      val exists = tokens.contains(id)
      tokens = tokens - id
      exists
    }

    override def close() = IO.unit
  }

  private def createRoutes(): (HttpRoutes[IO], AuthService, InMemoryUserRegistry, InMemoryTokenRegistry) = {
    val userRegistry = new InMemoryUserRegistry()
    val tokenRegistry = new InMemoryTokenRegistry()
    val authService = new AuthService(userRegistry, testConfig)

    // Create admin and regular users
    authService.createUser("admin", "adminpass", UserRole.Admin).unsafeRunSync()
    authService.createUser("user", "userpass", UserRole.User).unsafeRunSync()

    val routes = TokenRoutes.routes(tokenRegistry, authService)
    (routes, authService, userRegistry, tokenRegistry)
  }

  "POST /api/v1/tokens" should "create token as admin" in {
    val (routes, authService, userRegistry, _) = createRoutes()

    val admin = userRegistry.getByUsername("admin").unsafeRunSync().get
    val jwtToken = authService.generateToken(admin)

    val requestBody = """{
      "name": "test-ingester",
      "scopes": ["documents:write", "sync:write"],
      "collections": ["test/*"]
    }"""
    val request = Request[IO](Method.POST, uri"/api/v1/tokens")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))
      .withHeaders(headers.Authorization(Credentials.Token(AuthScheme.Bearer, jwtToken)))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Created
    val body = response.as[String].unsafeRunSync()
    body should include("test-ingester")
    body should include("rat_")  // Token prefix
    body should include("documents:write")
  }

  it should "reject token creation from non-admin" in {
    val (routes, authService, userRegistry, _) = createRoutes()

    val user = userRegistry.getByUsername("user").unsafeRunSync().get
    val jwtToken = authService.generateToken(user)

    val requestBody = """{"name": "test", "scopes": ["query"]}"""
    val request = Request[IO](Method.POST, uri"/api/v1/tokens")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))
      .withHeaders(headers.Authorization(Credentials.Token(AuthScheme.Bearer, jwtToken)))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Forbidden
  }

  it should "reject invalid scopes" in {
    val (routes, authService, userRegistry, _) = createRoutes()

    val admin = userRegistry.getByUsername("admin").unsafeRunSync().get
    val jwtToken = authService.generateToken(admin)

    val requestBody = """{"name": "test", "scopes": ["invalid:scope"]}"""
    val request = Request[IO](Method.POST, uri"/api/v1/tokens")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))
      .withHeaders(headers.Authorization(Credentials.Token(AuthScheme.Bearer, jwtToken)))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.BadRequest
    val body = response.as[String].unsafeRunSync()
    body should include("Invalid scopes")
  }

  it should "reject request without authorization" in {
    val (routes, _, _, _) = createRoutes()

    val requestBody = """{"name": "test", "scopes": ["query"]}"""
    val request = Request[IO](Method.POST, uri"/api/v1/tokens")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Unauthorized
  }

  "GET /api/v1/tokens" should "list tokens as admin" in {
    val (routes, authService, userRegistry, tokenRegistry) = createRoutes()

    // Create some tokens
    tokenRegistry.create("token1", Set("query"), None, Some(1), None).unsafeRunSync()
    tokenRegistry.create("token2", Set("documents:read"), None, Some(1), None).unsafeRunSync()

    val admin = userRegistry.getByUsername("admin").unsafeRunSync().get
    val jwtToken = authService.generateToken(admin)

    val request = Request[IO](Method.GET, uri"/api/v1/tokens")
      .withHeaders(headers.Authorization(Credentials.Token(AuthScheme.Bearer, jwtToken)))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("tokens")
    body should include("token1")
    body should include("token2")
    body should include("\"total\":2")
  }

  it should "reject listing from non-admin" in {
    val (routes, authService, userRegistry, _) = createRoutes()

    val user = userRegistry.getByUsername("user").unsafeRunSync().get
    val jwtToken = authService.generateToken(user)

    val request = Request[IO](Method.GET, uri"/api/v1/tokens")
      .withHeaders(headers.Authorization(Credentials.Token(AuthScheme.Bearer, jwtToken)))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Forbidden
  }

  "GET /api/v1/tokens/:id" should "get token info as admin" in {
    val (routes, authService, userRegistry, tokenRegistry) = createRoutes()

    val (token, _) = tokenRegistry.create("my-token", Set("query"), None, Some(1), None).unsafeRunSync()

    val admin = userRegistry.getByUsername("admin").unsafeRunSync().get
    val jwtToken = authService.generateToken(admin)

    val request = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/tokens/${token.id}"))
      .withHeaders(headers.Authorization(Credentials.Token(AuthScheme.Bearer, jwtToken)))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("my-token")
    body should include("query")
  }

  it should "return 404 for non-existent token" in {
    val (routes, authService, userRegistry, _) = createRoutes()

    val admin = userRegistry.getByUsername("admin").unsafeRunSync().get
    val jwtToken = authService.generateToken(admin)

    val request = Request[IO](Method.GET, uri"/api/v1/tokens/nonexistent-id")
      .withHeaders(headers.Authorization(Credentials.Token(AuthScheme.Bearer, jwtToken)))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.NotFound
  }

  "DELETE /api/v1/tokens/:id" should "delete token as admin" in {
    val (routes, authService, userRegistry, tokenRegistry) = createRoutes()

    val (token, _) = tokenRegistry.create("to-delete", Set("query"), None, Some(1), None).unsafeRunSync()

    val admin = userRegistry.getByUsername("admin").unsafeRunSync().get
    val jwtToken = authService.generateToken(admin)

    val request = Request[IO](Method.DELETE, Uri.unsafeFromString(s"/api/v1/tokens/${token.id}"))
      .withHeaders(headers.Authorization(Credentials.Token(AuthScheme.Bearer, jwtToken)))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.NoContent

    // Verify token is deleted
    tokenRegistry.getById(token.id).unsafeRunSync() shouldBe None
  }

  it should "return 404 for non-existent token" in {
    val (routes, authService, userRegistry, _) = createRoutes()

    val admin = userRegistry.getByUsername("admin").unsafeRunSync().get
    val jwtToken = authService.generateToken(admin)

    val request = Request[IO](Method.DELETE, uri"/api/v1/tokens/nonexistent-id")
      .withHeaders(headers.Authorization(Credentials.Token(AuthScheme.Bearer, jwtToken)))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.NotFound
  }

  it should "reject deletion from non-admin" in {
    val (routes, authService, userRegistry, tokenRegistry) = createRoutes()

    val (token, _) = tokenRegistry.create("protected", Set("query"), None, Some(1), None).unsafeRunSync()

    val user = userRegistry.getByUsername("user").unsafeRunSync().get
    val jwtToken = authService.generateToken(user)

    val request = Request[IO](Method.DELETE, Uri.unsafeFromString(s"/api/v1/tokens/${token.id}"))
      .withHeaders(headers.Authorization(Credentials.Token(AuthScheme.Bearer, jwtToken)))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Forbidden

    // Verify token still exists
    tokenRegistry.getById(token.id).unsafeRunSync() shouldBe defined
  }
}

package ragbox.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax._
import io.circe.parser._
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ragbox.auth._
import ragbox.config.{AuthConfig, AuthMode, BasicAuthConfig}

class AuthRoutesSpec extends AnyFlatSpec with Matchers {

  // Test configuration
  private val testConfig = AuthConfig(
    mode = AuthMode.Basic,
    basic = BasicAuthConfig("admin", Some("adminpass")),
    jwtSecret = "test-secret-key-for-testing",
    jwtExpiration = 3600L
  )

  // Mock user registry that stores users in memory
  private class InMemoryUserRegistry extends UserRegistry(null) {
    private var users = Map[String, User]()
    private var nextId = 1

    override def initialize() = IO.unit

    override def exists(username: String) = IO.pure(users.contains(username))

    override def create(username: String, passwordHash: String, role: UserRole) = IO {
      val user = User(nextId, username, passwordHash, role, java.time.Instant.now())
      users = users + (username -> user)
      nextId += 1
      user
    }

    override def getByUsername(username: String) = IO.pure(users.get(username))

    override def getById(id: Int) = IO.pure(users.values.find(_.id == id))

    override def list() = IO.pure(users.values.toSeq)

    override def delete(id: Int) = IO {
      val exists = users.values.exists(_.id == id)
      users = users.filterNot(_._2.id == id)
      exists
    }

    override def updatePassword(id: Int, passwordHash: String) = IO {
      users.values.find(_.id == id) match {
        case Some(user) =>
          users = users + (user.username -> user.copy(passwordHash = passwordHash))
          true
        case None => false
      }
    }

    override def close() = IO.unit
  }

  private def createRoutes(): (HttpRoutes[IO], AuthService, InMemoryUserRegistry) = {
    val registry = new InMemoryUserRegistry()
    val authService = new AuthService(registry, testConfig)
    // Initialize - create admin user
    authService.createUser("admin", "adminpass", UserRole.Admin).unsafeRunSync()
    val routes = AuthRoutes.routes(authService, registry, testConfig.jwtExpiration)
    (routes, authService, registry)
  }

  "POST /api/v1/auth/login" should "return token for valid credentials" in {
    val (routes, _, _) = createRoutes()

    val requestBody = """{"username": "admin", "password": "adminpass"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/auth/login")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("token")
    body should include("admin")
  }

  it should "reject invalid credentials" in {
    val (routes, _, _) = createRoutes()

    val requestBody = """{"username": "admin", "password": "wrongpassword"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/auth/login")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Unauthorized
  }

  it should "reject unknown user" in {
    val (routes, _, _) = createRoutes()

    val requestBody = """{"username": "unknown", "password": "password"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/auth/login")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Unauthorized
  }

  "GET /api/v1/auth/me" should "return user info with valid token" in {
    val (routes, authService, registry) = createRoutes()

    // Login first to get token
    val user = registry.getByUsername("admin").unsafeRunSync().get
    val token = authService.generateToken(user)

    val request = Request[IO](Method.GET, uri"/api/v1/auth/me")
      .withHeaders(headers.Authorization(Credentials.Token(AuthScheme.Bearer, token)))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("admin")
  }

  it should "reject request without token" in {
    val (routes, _, _) = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/auth/me")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Unauthorized
  }

  it should "reject request with invalid token" in {
    val (routes, _, _) = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/auth/me")
      .withHeaders(headers.Authorization(Credentials.Token(AuthScheme.Bearer, "invalid.token.here")))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Unauthorized
  }

  "POST /api/v1/users" should "create user as admin" in {
    val (routes, authService, registry) = createRoutes()

    val user = registry.getByUsername("admin").unsafeRunSync().get
    val token = authService.generateToken(user)

    val requestBody = """{"username": "newuser", "password": "newpass", "role": "user"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/users")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))
      .withHeaders(headers.Authorization(Credentials.Token(AuthScheme.Bearer, token)))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Created
    val body = response.as[String].unsafeRunSync()
    body should include("newuser")
  }

  it should "reject user creation from non-admin" in {
    val (routes, authService, registry) = createRoutes()

    // Create a regular user
    authService.createUser("regularuser", "password", UserRole.User).unsafeRunSync()
    val user = registry.getByUsername("regularuser").unsafeRunSync().get
    val token = authService.generateToken(user)

    val requestBody = """{"username": "another", "password": "pass"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/users")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))
      .withHeaders(headers.Authorization(Credentials.Token(AuthScheme.Bearer, token)))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Forbidden
  }

  "GET /api/v1/users" should "list users as admin" in {
    val (routes, authService, registry) = createRoutes()

    val user = registry.getByUsername("admin").unsafeRunSync().get
    val token = authService.generateToken(user)

    val request = Request[IO](Method.GET, uri"/api/v1/users")
      .withHeaders(headers.Authorization(Credentials.Token(AuthScheme.Bearer, token)))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("users")
    body should include("admin")
  }

  "DELETE /api/v1/users/:id" should "delete user as admin" in {
    val (routes, authService, registry) = createRoutes()

    // Create a user to delete
    authService.createUser("todelete", "password", UserRole.User).unsafeRunSync()
    val userToDelete = registry.getByUsername("todelete").unsafeRunSync().get

    val admin = registry.getByUsername("admin").unsafeRunSync().get
    val token = authService.generateToken(admin)

    val request = Request[IO](Method.DELETE, Uri.unsafeFromString(s"/api/v1/users/${userToDelete.id}"))
      .withHeaders(headers.Authorization(Credentials.Token(AuthScheme.Bearer, token)))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.NoContent

    // Verify user is deleted
    registry.getByUsername("todelete").unsafeRunSync() shouldBe None
  }

  it should "return 404 for non-existent user" in {
    val (routes, authService, registry) = createRoutes()

    val admin = registry.getByUsername("admin").unsafeRunSync().get
    val token = authService.generateToken(admin)

    val request = Request[IO](Method.DELETE, uri"/api/v1/users/99999")
      .withHeaders(headers.Authorization(Credentials.Token(AuthScheme.Bearer, token)))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.NotFound
  }
}

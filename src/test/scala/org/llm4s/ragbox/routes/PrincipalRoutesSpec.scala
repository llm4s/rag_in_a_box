package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser._
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.http4s.headers.`Content-Type`
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import org.llm4s.ragbox.testkit.MockPrincipalStore

class PrincipalRoutesSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var mockStore: MockPrincipalStore = _
  private var routes: HttpRoutes[IO] = _

  override def beforeEach(): Unit = {
    mockStore = MockPrincipalStore()
    routes = PrincipalRoutes.routes(mockStore)
  }

  // ============================================================
  // POST /api/v1/principals/users - Create user principal
  // ============================================================

  "POST /api/v1/principals/users" should "create a new user principal" in {
    val body = """{"externalId": "alice@example.com"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/principals/users")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Created

    val responseBody = response.as[String].unsafeRunSync()
    responseBody should include("\"id\":")
    responseBody should include("\"principalType\":\"user\"")
    responseBody should include("user:alice@example.com")

    mockStore.getCreatedUsers should contain("alice@example.com")
  }

  it should "return same ID for existing user" in {
    // Create user first
    val body = """{"externalId": "bob@example.com"}"""
    val request1 = Request[IO](Method.POST, uri"/api/v1/principals/users")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response1 = routes.orNotFound.run(request1).unsafeRunSync()
    val body1 = response1.as[String].unsafeRunSync()

    // Create same user again
    val request2 = Request[IO](Method.POST, uri"/api/v1/principals/users")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response2 = routes.orNotFound.run(request2).unsafeRunSync()
    val body2 = response2.as[String].unsafeRunSync()

    response2.status shouldBe Status.Created
    body1 shouldBe body2
  }

  // ============================================================
  // POST /api/v1/principals/groups - Create group principal
  // ============================================================

  "POST /api/v1/principals/groups" should "create a new group principal" in {
    val body = """{"externalId": "engineering"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/principals/groups")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Created

    val responseBody = response.as[String].unsafeRunSync()
    responseBody should include("\"id\":")
    responseBody should include("\"principalType\":\"group\"")
    responseBody should include("group:engineering")

    mockStore.getCreatedGroups should contain("engineering")
  }

  // ============================================================
  // GET /api/v1/principals/users - List users
  // ============================================================

  "GET /api/v1/principals/users" should "return empty list when no users" in {
    val request = Request[IO](Method.GET, uri"/api/v1/principals/users")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("\"principals\":[]")
    body should include("\"total\":0")
  }

  it should "return user list when users exist" in {
    // Create some users first
    val userBody = """{"externalId": "user1@example.com"}"""
    val createRequest = Request[IO](Method.POST, uri"/api/v1/principals/users")
      .withEntity(userBody)
      .withContentType(`Content-Type`(MediaType.application.json))
    routes.orNotFound.run(createRequest).unsafeRunSync()

    val request = Request[IO](Method.GET, uri"/api/v1/principals/users")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("user1@example.com")
    body should include("\"total\":1")
  }

  it should "support pagination" in {
    // Create multiple users
    for (i <- 1 to 5) {
      val userBody = s"""{"externalId": "user$i@example.com"}"""
      val createRequest = Request[IO](Method.POST, uri"/api/v1/principals/users")
        .withEntity(userBody)
        .withContentType(`Content-Type`(MediaType.application.json))
      routes.orNotFound.run(createRequest).unsafeRunSync()
    }

    val request = Request[IO](Method.GET, uri"/api/v1/principals/users?limit=2&offset=1")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("\"total\":5")
  }

  // ============================================================
  // GET /api/v1/principals/groups - List groups
  // ============================================================

  "GET /api/v1/principals/groups" should "return empty list when no groups" in {
    val request = Request[IO](Method.GET, uri"/api/v1/principals/groups")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("\"principals\":[]")
    body should include("\"total\":0")
  }

  it should "return group list when groups exist" in {
    // Create some groups first
    val groupBody = """{"externalId": "admins"}"""
    val createRequest = Request[IO](Method.POST, uri"/api/v1/principals/groups")
      .withEntity(groupBody)
      .withContentType(`Content-Type`(MediaType.application.json))
    routes.orNotFound.run(createRequest).unsafeRunSync()

    val request = Request[IO](Method.GET, uri"/api/v1/principals/groups")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("admins")
    body should include("\"total\":1")
  }

  // ============================================================
  // GET /api/v1/principals/lookup/:externalId - Lookup principal
  // ============================================================

  "GET /api/v1/principals/lookup/:externalId" should "return principal when found" in {
    // Create user first
    val userBody = """{"externalId": "lookup@example.com"}"""
    val createRequest = Request[IO](Method.POST, uri"/api/v1/principals/users")
      .withEntity(userBody)
      .withContentType(`Content-Type`(MediaType.application.json))
    routes.orNotFound.run(createRequest).unsafeRunSync()

    val request = Request[IO](Method.GET, uri"/api/v1/principals/lookup/user:lookup@example.com")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("lookup@example.com")
    body should include("\"id\":")
  }

  it should "return 404 when principal not found" in {
    val request = Request[IO](Method.GET, uri"/api/v1/principals/lookup/user:nonexistent@example.com")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.NotFound
  }

  it should "return 400 for invalid external ID format" in {
    val request = Request[IO](Method.GET, uri"/api/v1/principals/lookup/invalid-format")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.BadRequest
  }

  // ============================================================
  // DELETE /api/v1/principals/:type/:externalId - Delete principal
  // ============================================================

  "DELETE /api/v1/principals/users/:externalId" should "delete a user principal" in {
    // Create user first
    val userBody = """{"externalId": "delete-me@example.com"}"""
    val createRequest = Request[IO](Method.POST, uri"/api/v1/principals/users")
      .withEntity(userBody)
      .withContentType(`Content-Type`(MediaType.application.json))
    routes.orNotFound.run(createRequest).unsafeRunSync()

    val request = Request[IO](Method.DELETE, uri"/api/v1/principals/users/delete-me@example.com")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.NoContent

    // Verify user no longer exists
    val lookupRequest = Request[IO](Method.GET, uri"/api/v1/principals/lookup/user:delete-me@example.com")
    val lookupResponse = routes.orNotFound.run(lookupRequest).unsafeRunSync()
    lookupResponse.status shouldBe Status.NotFound
  }

  "DELETE /api/v1/principals/groups/:externalId" should "delete a group principal" in {
    // Create group first
    val groupBody = """{"externalId": "delete-group"}"""
    val createRequest = Request[IO](Method.POST, uri"/api/v1/principals/groups")
      .withEntity(groupBody)
      .withContentType(`Content-Type`(MediaType.application.json))
    routes.orNotFound.run(createRequest).unsafeRunSync()

    val request = Request[IO](Method.DELETE, uri"/api/v1/principals/groups/delete-group")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.NoContent
  }
}

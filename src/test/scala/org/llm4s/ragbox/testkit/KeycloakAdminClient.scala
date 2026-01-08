package org.llm4s.ragbox.testkit

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.headers.{Authorization, `Content-Type`}
import org.http4s.implicits.*
import org.typelevel.ci.CIStringSyntax

/**
 * Keycloak Admin API client for setting up test realms and users.
 *
 * Uses the Keycloak Admin REST API to:
 * - Create realms
 * - Create clients with PKCE support
 * - Create test users
 * - Assign roles/groups
 */
class KeycloakAdminClient(
    baseUrl: String,
    adminUsername: String = "admin",
    adminPassword: String = "admin",
    httpClient: Client[IO]
):

  private var accessToken: Option[String] = None

  /**
   * Authenticate with Keycloak admin API.
   */
  def authenticate(): IO[Unit] =
    val tokenUrl = Uri.unsafeFromString(s"$baseUrl/realms/master/protocol/openid-connect/token")

    val form = UrlForm(
      "grant_type" -> "password",
      "client_id" -> "admin-cli",
      "username" -> adminUsername,
      "password" -> adminPassword
    )

    val request = Request[IO](Method.POST, tokenUrl)
      .withEntity(form)

    httpClient.expect[Json](request).map { json =>
      accessToken = json.hcursor.get[String]("access_token").toOption
    }

  /**
   * Create a test realm.
   */
  def createRealm(realmName: String): IO[Unit] =
    val url = Uri.unsafeFromString(s"$baseUrl/admin/realms")

    val realmJson = Json.obj(
      "realm" -> Json.fromString(realmName),
      "enabled" -> Json.fromBoolean(true),
      "registrationAllowed" -> Json.fromBoolean(false),
      "loginWithEmailAllowed" -> Json.fromBoolean(true)
    )

    val request = Request[IO](Method.POST, url)
      .withHeaders(authHeader)
      .withEntity(realmJson)

    httpClient.status(request).flatMap { status =>
      if status.isSuccess || status.code == 409 then IO.unit // 409 = already exists
      else IO.raiseError(new RuntimeException(s"Failed to create realm: $status"))
    }

  /**
   * Create a confidential client with PKCE support.
   */
  def createClient(
      realmName: String,
      clientId: String,
      clientSecret: String,
      redirectUri: String
  ): IO[Unit] =
    val url = Uri.unsafeFromString(s"$baseUrl/admin/realms/$realmName/clients")

    val clientJson = Json.obj(
      "clientId" -> Json.fromString(clientId),
      "enabled" -> Json.fromBoolean(true),
      "clientAuthenticatorType" -> Json.fromString("client-secret"),
      "secret" -> Json.fromString(clientSecret),
      "redirectUris" -> Json.arr(Json.fromString(redirectUri), Json.fromString("*")),
      "webOrigins" -> Json.arr(Json.fromString("*")),
      "standardFlowEnabled" -> Json.fromBoolean(true),
      "directAccessGrantsEnabled" -> Json.fromBoolean(true), // For testing
      "publicClient" -> Json.fromBoolean(false),
      "protocol" -> Json.fromString("openid-connect"),
      "attributes" -> Json.obj(
        "pkce.code.challenge.method" -> Json.fromString("S256")
      ),
      "defaultClientScopes" -> Json.arr(
        Json.fromString("openid"),
        Json.fromString("profile"),
        Json.fromString("email")
      )
    )

    val request = Request[IO](Method.POST, url)
      .withHeaders(authHeader)
      .withEntity(clientJson)

    httpClient.status(request).flatMap { status =>
      if status.isSuccess || status.code == 409 then IO.unit
      else IO.raiseError(new RuntimeException(s"Failed to create client: $status"))
    }

  /**
   * Create a test user.
   */
  def createUser(
      realmName: String,
      username: String,
      email: String,
      password: String,
      firstName: String = "Test",
      lastName: String = "User"
  ): IO[Unit] =
    val url = Uri.unsafeFromString(s"$baseUrl/admin/realms/$realmName/users")

    val userJson = Json.obj(
      "username" -> Json.fromString(username),
      "email" -> Json.fromString(email),
      "emailVerified" -> Json.fromBoolean(true),
      "enabled" -> Json.fromBoolean(true),
      "firstName" -> Json.fromString(firstName),
      "lastName" -> Json.fromString(lastName),
      "credentials" -> Json.arr(
        Json.obj(
          "type" -> Json.fromString("password"),
          "value" -> Json.fromString(password),
          "temporary" -> Json.fromBoolean(false)
        )
      )
    )

    val request = Request[IO](Method.POST, url)
      .withHeaders(authHeader)
      .withEntity(userJson)

    httpClient.status(request).flatMap { status =>
      if status.isSuccess || status.code == 409 then IO.unit
      else IO.raiseError(new RuntimeException(s"Failed to create user: $status"))
    }

  /**
   * Get an access token for a user via direct grant (for testing).
   * This simulates the user completing the OAuth flow.
   */
  def getDirectGrantToken(
      realmName: String,
      clientId: String,
      clientSecret: String,
      username: String,
      password: String
  ): IO[DirectGrantTokenResponse] =
    val tokenUrl = Uri.unsafeFromString(s"$baseUrl/realms/$realmName/protocol/openid-connect/token")

    val form = UrlForm(
      "grant_type" -> "password",
      "client_id" -> clientId,
      "client_secret" -> clientSecret,
      "username" -> username,
      "password" -> password,
      "scope" -> "openid profile email"
    )

    val request = Request[IO](Method.POST, tokenUrl)
      .withEntity(form)

    httpClient.expect[Json](request).map { json =>
      DirectGrantTokenResponse(
        accessToken = json.hcursor.get[String]("access_token").getOrElse(""),
        idToken = json.hcursor.get[String]("id_token").toOption,
        refreshToken = json.hcursor.get[String]("refresh_token").toOption,
        expiresIn = json.hcursor.get[Long]("expires_in").getOrElse(0L)
      )
    }

  private def authHeader: Header.Raw =
    Header.Raw(ci"Authorization", s"Bearer ${accessToken.getOrElse("")}")

/**
 * Response from direct grant token request.
 */
case class DirectGrantTokenResponse(
    accessToken: String,
    idToken: Option[String],
    refreshToken: Option[String],
    expiresIn: Long
)

object KeycloakAdminClient:
  def apply(baseUrl: String, httpClient: Client[IO]): KeycloakAdminClient =
    new KeycloakAdminClient(baseUrl, httpClient = httpClient)

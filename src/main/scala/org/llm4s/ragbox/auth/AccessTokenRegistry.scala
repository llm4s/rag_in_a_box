package org.llm4s.ragbox.auth

import cats.effect.IO
import org.llm4s.ragbox.config.DatabaseConfig

import java.security.SecureRandom
import java.sql.{Connection, DriverManager, ResultSet, Timestamp}
import java.time.Instant
import java.util.{Base64, Properties, UUID}
import scala.compiletime.uninitialized

/**
 * Available token scopes.
 */
object TokenScope {
  val DocumentsRead = "documents:read"
  val DocumentsWrite = "documents:write"
  val SyncRead = "sync:read"
  val SyncWrite = "sync:write"
  val Query = "query"
  val Admin = "admin"

  val All: Set[String] = Set(DocumentsRead, DocumentsWrite, SyncRead, SyncWrite, Query, Admin)

  def validate(scope: String): Boolean = All.contains(scope)
}

/**
 * An access token for external ingesters.
 */
final case class AccessToken(
  id: String,
  name: String,
  tokenPrefix: String,  // First 8 chars of token for identification
  scopes: Set[String],
  collections: Option[Set[String]],  // None = all collections
  createdBy: Option[Int],
  expiresAt: Option[Instant],
  lastUsedAt: Option[Instant],
  createdAt: Instant
)

/**
 * PostgreSQL-backed access token registry.
 */
class AccessTokenRegistry(dbConfig: DatabaseConfig) {

  private var connection: Connection = uninitialized
  private val TOKEN_PREFIX = "rat_"  // RAG Access Token
  private val secureRandom = new SecureRandom()

  /**
   * Initialize the registry and ensure tables exist.
   */
  def initialize(): IO[Unit] = IO {
    Class.forName("org.postgresql.Driver")

    val props = new Properties()
    props.setProperty("user", dbConfig.effectiveUser)
    props.setProperty("password", dbConfig.effectivePassword)

    connection = DriverManager.getConnection(dbConfig.connectionString, props)

    val stmt = connection.createStatement()
    try {
      stmt.execute(
        """CREATE TABLE IF NOT EXISTS access_tokens (
          |    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
          |    name TEXT NOT NULL,
          |    token_hash TEXT NOT NULL,
          |    token_prefix TEXT NOT NULL,
          |    scopes TEXT[] NOT NULL,
          |    collections TEXT[],
          |    created_by INTEGER REFERENCES users(id),
          |    expires_at TIMESTAMPTZ,
          |    last_used_at TIMESTAMPTZ,
          |    created_at TIMESTAMPTZ DEFAULT NOW()
          |)""".stripMargin
      )
      stmt.execute(
        """CREATE INDEX IF NOT EXISTS idx_access_tokens_name
          |    ON access_tokens(name)""".stripMargin
      )
    } finally {
      stmt.close()
    }
  }

  /**
   * Create a new access token.
   * Returns the full token (only returned once).
   */
  def create(
    name: String,
    scopes: Set[String],
    collections: Option[Set[String]],
    createdBy: Option[Int],
    expiresAt: Option[Instant]
  ): IO[(AccessToken, String)] = IO {
    // Validate scopes
    val invalidScopes = scopes.filterNot(TokenScope.validate)
    if (invalidScopes.nonEmpty) {
      throw new IllegalArgumentException(s"Invalid scopes: ${invalidScopes.mkString(", ")}")
    }

    // Generate random token
    val tokenBytes = new Array[Byte](32)
    secureRandom.nextBytes(tokenBytes)
    val rawToken = Base64.getUrlEncoder.withoutPadding().encodeToString(tokenBytes)
    val fullToken = s"$TOKEN_PREFIX$rawToken"
    val tokenPrefix = fullToken.take(12)  // rat_XXXXXXXX

    // Hash the token for storage
    val tokenHash = hashToken(fullToken)
    val id = UUID.randomUUID().toString

    val sql =
      """INSERT INTO access_tokens
        |    (id, name, token_hash, token_prefix, scopes, collections, created_by, expires_at)
        |VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?)
        |RETURNING id, name, token_prefix, scopes, collections, created_by, expires_at, last_used_at, created_at""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, id)
      stmt.setString(2, name)
      stmt.setString(3, tokenHash)
      stmt.setString(4, tokenPrefix)
      stmt.setArray(5, connection.createArrayOf("text", scopes.toArray))
      collections match {
        case Some(cols) => stmt.setArray(6, connection.createArrayOf("text", cols.toArray))
        case None => stmt.setNull(6, java.sql.Types.ARRAY)
      }
      createdBy match {
        case Some(userId) => stmt.setInt(7, userId)
        case None => stmt.setNull(7, java.sql.Types.INTEGER)
      }
      expiresAt match {
        case Some(exp) => stmt.setTimestamp(8, Timestamp.from(exp))
        case None => stmt.setNull(8, java.sql.Types.TIMESTAMP)
      }

      val rs = stmt.executeQuery()
      if (rs.next()) {
        (rowToToken(rs), fullToken)
      } else {
        throw new RuntimeException("Failed to create access token")
      }
    } finally {
      stmt.close()
    }
  }

  /**
   * Validate a token and return its metadata if valid.
   */
  def validate(token: String): IO[Option[AccessToken]] = IO {
    if (!token.startsWith(TOKEN_PREFIX)) {
      None
    } else {
      val tokenHash = hashToken(token)
      val sql =
        """SELECT id, name, token_prefix, scopes, collections, created_by, expires_at, last_used_at, created_at
          |FROM access_tokens WHERE token_hash = ?""".stripMargin
      val stmt = connection.prepareStatement(sql)
      try {
        stmt.setString(1, tokenHash)
        val rs = stmt.executeQuery()
        if (rs.next()) {
          val accessToken = rowToToken(rs)
          // Check expiration
          accessToken.expiresAt match {
            case Some(exp) if exp.isBefore(Instant.now()) => None
            case _ =>
              // Update last used time
              updateLastUsed(accessToken.id)
              Some(accessToken)
          }
        } else {
          None
        }
      } finally {
        stmt.close()
      }
    }
  }

  /**
   * List all tokens (without sensitive data).
   */
  def list(): IO[Seq[AccessToken]] = IO {
    val sql =
      """SELECT id, name, token_prefix, scopes, collections, created_by, expires_at, last_used_at, created_at
        |FROM access_tokens ORDER BY created_at DESC""".stripMargin
    val stmt = connection.createStatement()
    try {
      val rs = stmt.executeQuery(sql)
      val tokens = scala.collection.mutable.ArrayBuffer.empty[AccessToken]
      while (rs.next()) {
        tokens += rowToToken(rs)
      }
      tokens.toSeq
    } finally {
      stmt.close()
    }
  }

  /**
   * Get a token by ID.
   */
  def getById(id: String): IO[Option[AccessToken]] = IO {
    val sql =
      """SELECT id, name, token_prefix, scopes, collections, created_by, expires_at, last_used_at, created_at
        |FROM access_tokens WHERE id = ?::uuid""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, id)
      val rs = stmt.executeQuery()
      if (rs.next()) Some(rowToToken(rs)) else None
    } finally {
      stmt.close()
    }
  }

  /**
   * Delete a token.
   */
  def delete(id: String): IO[Boolean] = IO {
    val sql = "DELETE FROM access_tokens WHERE id = ?::uuid"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, id)
      stmt.executeUpdate() > 0
    } finally {
      stmt.close()
    }
  }

  /**
   * Close the connection.
   */
  def close(): IO[Unit] = IO {
    if (connection != null && !connection.isClosed) {
      connection.close()
    }
  }

  private def updateLastUsed(id: String): Unit = {
    val sql = "UPDATE access_tokens SET last_used_at = NOW() WHERE id = ?::uuid"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, id)
      stmt.executeUpdate()
    } finally {
      stmt.close()
    }
  }

  private def hashToken(token: String): String = {
    import java.security.MessageDigest
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(token.getBytes("UTF-8"))
    Base64.getEncoder.encodeToString(hash)
  }

  private def rowToToken(rs: ResultSet): AccessToken = {
    val scopesArray = rs.getArray("scopes")
    val scopes = if (scopesArray != null) {
      scopesArray.getArray.asInstanceOf[Array[String]].toSet
    } else {
      Set.empty[String]
    }

    val collectionsArray = rs.getArray("collections")
    val collections = if (collectionsArray != null) {
      Some(collectionsArray.getArray.asInstanceOf[Array[String]].toSet)
    } else {
      None
    }

    AccessToken(
      id = rs.getString("id"),
      name = rs.getString("name"),
      tokenPrefix = rs.getString("token_prefix"),
      scopes = scopes,
      collections = collections,
      createdBy = Option(rs.getInt("created_by")).filterNot(_ => rs.wasNull()),
      expiresAt = Option(rs.getTimestamp("expires_at")).map(_.toInstant),
      lastUsedAt = Option(rs.getTimestamp("last_used_at")).map(_.toInstant),
      createdAt = rs.getTimestamp("created_at").toInstant
    )
  }
}

object AccessTokenRegistry {

  /**
   * Create and initialize an access token registry.
   */
  def apply(dbConfig: DatabaseConfig): IO[AccessTokenRegistry] = {
    val registry = new AccessTokenRegistry(dbConfig)
    registry.initialize().as(registry)
  }
}

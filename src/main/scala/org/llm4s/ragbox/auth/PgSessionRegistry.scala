package org.llm4s.ragbox.auth

import cats.effect.IO
import cats.syntax.all.*
import com.zaxxer.hikari.HikariDataSource
import org.llm4s.ragbox.db.ConnectionPool
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.sql.{Connection, PreparedStatement, ResultSet, Timestamp}

/**
 * PostgreSQL-backed session registry for production use.
 *
 * Stores OAuth sessions and authorization states in the database
 * for multi-instance deployments where in-memory storage won't work.
 *
 * Tables required:
 * - oauth_sessions: Stores authenticated user sessions
 * - oauth_auth_state: Stores temporary PKCE authorization states
 */
class PgSessionRegistry private (
    pool: HikariDataSource,
    logger: Logger[IO]
) extends SessionRegistry[IO]:

  override def storeAuthState(state: AuthorizationState): IO[Unit] =
    ConnectionPool.withConnection(pool) { conn =>
      val sql = """
        INSERT INTO oauth_auth_state (state, code_verifier, redirect_after_login, created_at, expires_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (state) DO UPDATE SET
          code_verifier = EXCLUDED.code_verifier,
          redirect_after_login = EXCLUDED.redirect_after_login,
          created_at = EXCLUDED.created_at,
          expires_at = EXCLUDED.expires_at
      """
      val stmt = conn.prepareStatement(sql)
      try
        stmt.setString(1, state.state)
        stmt.setString(2, state.codeVerifier)
        stmt.setString(3, state.redirectAfterLogin.orNull)
        stmt.setTimestamp(4, new Timestamp(state.createdAt))
        // Default 5 minute expiry for auth states
        stmt.setTimestamp(5, new Timestamp(state.createdAt + 300000))
        stmt.executeUpdate()
      finally
        stmt.close()
    } *> logger.debug(s"Stored auth state: ${state.state.take(8)}...")

  override def getAndRemoveAuthState(stateKey: String): IO[Option[AuthorizationState]] =
    ConnectionPool.withTransaction(pool) { conn =>
      // First select the state
      val selectSql = "SELECT state, code_verifier, redirect_after_login, created_at FROM oauth_auth_state WHERE state = ?"
      val selectStmt = conn.prepareStatement(selectSql)
      try
        selectStmt.setString(1, stateKey)
        val rs = selectStmt.executeQuery()
        if rs.next() then
          val state = AuthorizationState(
            state = rs.getString("state"),
            codeVerifier = rs.getString("code_verifier"),
            redirectAfterLogin = Option(rs.getString("redirect_after_login")),
            createdAt = rs.getTimestamp("created_at").getTime
          )
          // Delete the state (one-time use)
          val deleteStmt = conn.prepareStatement("DELETE FROM oauth_auth_state WHERE state = ?")
          try
            deleteStmt.setString(1, stateKey)
            deleteStmt.executeUpdate()
          finally
            deleteStmt.close()
          Some(state)
        else
          None
      finally
        selectStmt.close()
    }

  override def createSession(session: OAuthSessionData): IO[Unit] =
    ConnectionPool.withConnection(pool) { conn =>
      val sql = """
        INSERT INTO oauth_sessions (
          session_id, user_id, email, name, groups, provider, expires_at, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (session_id) DO UPDATE SET
          user_id = EXCLUDED.user_id,
          email = EXCLUDED.email,
          name = EXCLUDED.name,
          groups = EXCLUDED.groups,
          provider = EXCLUDED.provider,
          expires_at = EXCLUDED.expires_at
      """
      val stmt = conn.prepareStatement(sql)
      try
        stmt.setString(1, session.sessionId)
        stmt.setString(2, session.userId)
        stmt.setString(3, session.email.orNull)
        stmt.setString(4, session.name.orNull)
        stmt.setString(5, session.groups.mkString(","))
        stmt.setString(6, session.provider)
        stmt.setTimestamp(7, new Timestamp(session.expiresAt))
        stmt.setTimestamp(8, new Timestamp(session.createdAt))
        stmt.executeUpdate()
      finally
        stmt.close()
    } *> logger.info(s"Created session for user: ${session.userId}")

  override def getSession(sessionId: String): IO[Option[OAuthSessionData]] =
    ConnectionPool.withConnection(pool) { conn =>
      val sql = """
        SELECT session_id, user_id, email, name, groups, provider, expires_at, created_at
        FROM oauth_sessions
        WHERE session_id = ?
      """
      val stmt = conn.prepareStatement(sql)
      try
        stmt.setString(1, sessionId)
        val rs = stmt.executeQuery()
        if rs.next() then
          Some(OAuthSessionData(
            sessionId = rs.getString("session_id"),
            userId = rs.getString("user_id"),
            email = Option(rs.getString("email")),
            name = Option(rs.getString("name")),
            groups = Option(rs.getString("groups")).map(_.split(",").toList.filter(_.nonEmpty)).getOrElse(List.empty),
            provider = rs.getString("provider"),
            expiresAt = rs.getTimestamp("expires_at").getTime,
            createdAt = rs.getTimestamp("created_at").getTime
          ))
        else
          None
      finally
        stmt.close()
    }

  override def deleteSession(sessionId: String): IO[Unit] =
    ConnectionPool.withConnection(pool) { conn =>
      val stmt = conn.prepareStatement("DELETE FROM oauth_sessions WHERE session_id = ?")
      try
        stmt.setString(1, sessionId)
        stmt.executeUpdate()
      finally
        stmt.close()
    } *> logger.info(s"Deleted session: ${sessionId.take(8)}...")

  /**
   * Clean up expired sessions and auth states.
   * Should be called periodically (e.g., via a scheduled task).
   */
  def cleanupExpired(): IO[Int] =
    ConnectionPool.withConnection(pool) { conn =>
      val now = new Timestamp(System.currentTimeMillis())

      // Delete expired sessions
      val sessionStmt = conn.prepareStatement("DELETE FROM oauth_sessions WHERE expires_at < ?")
      val sessionCount = try
        sessionStmt.setTimestamp(1, now)
        sessionStmt.executeUpdate()
      finally
        sessionStmt.close()

      // Delete expired auth states
      val stateStmt = conn.prepareStatement("DELETE FROM oauth_auth_state WHERE expires_at < ?")
      val stateCount = try
        stateStmt.setTimestamp(1, now)
        stateStmt.executeUpdate()
      finally
        stateStmt.close()

      sessionCount + stateCount
    }.flatTap(count => logger.info(s"Cleaned up $count expired OAuth records"))

object PgSessionRegistry:
  /**
   * Create a PostgreSQL session registry.
   */
  def create(pool: HikariDataSource): IO[PgSessionRegistry] =
    for
      logger <- Slf4jLogger.create[IO]
      _ <- logger.info("Created PostgreSQL session registry")
    yield new PgSessionRegistry(pool, logger)

  /**
   * SQL to create required tables.
   * Run this during application initialization or via a migration tool.
   */
  val createTablesSql: String = """
    -- OAuth sessions table
    CREATE TABLE IF NOT EXISTS oauth_sessions (
      session_id VARCHAR(64) PRIMARY KEY,
      user_id VARCHAR(255) NOT NULL,
      email VARCHAR(255),
      name VARCHAR(255),
      groups TEXT,
      provider VARCHAR(50) NOT NULL,
      expires_at TIMESTAMP NOT NULL,
      created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

    CREATE INDEX IF NOT EXISTS idx_oauth_sessions_user_id ON oauth_sessions(user_id);
    CREATE INDEX IF NOT EXISTS idx_oauth_sessions_expires_at ON oauth_sessions(expires_at);

    -- OAuth authorization state table (for PKCE flow)
    CREATE TABLE IF NOT EXISTS oauth_auth_state (
      state VARCHAR(64) PRIMARY KEY,
      code_verifier VARCHAR(128) NOT NULL,
      redirect_after_login VARCHAR(512),
      created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
      expires_at TIMESTAMP NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_oauth_auth_state_expires_at ON oauth_auth_state(expires_at);
  """

  /**
   * Initialize database tables if they don't exist.
   */
  def initializeTables(pool: HikariDataSource): IO[Unit] =
    ConnectionPool.withConnection(pool) { conn =>
      val stmt = conn.createStatement()
      try
        stmt.execute(createTablesSql)
      finally
        stmt.close()
    }

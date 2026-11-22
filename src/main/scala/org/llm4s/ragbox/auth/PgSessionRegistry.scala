package org.llm4s.ragbox.auth

import cats.effect.IO
import cats.syntax.all.*
import com.zaxxer.hikari.HikariDataSource
import org.llm4s.ragbox.db.ConnectionPool
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.sql.{ResultSet, Timestamp}

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
        INSERT INTO oauth_auth_state (
          state, code_verifier, redirect_after_login, created_at, expires_at, nonce, browser_binding_hash
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (state) DO UPDATE SET
          code_verifier = EXCLUDED.code_verifier,
          redirect_after_login = EXCLUDED.redirect_after_login,
          created_at = EXCLUDED.created_at,
          expires_at = EXCLUDED.expires_at,
          nonce = EXCLUDED.nonce,
          browser_binding_hash = EXCLUDED.browser_binding_hash
      """
      val stmt = conn.prepareStatement(sql)
      try
        stmt.setString(1, state.state)
        stmt.setString(2, state.codeVerifier)
        stmt.setString(3, state.redirectAfterLogin.orNull)
        stmt.setTimestamp(4, new Timestamp(state.createdAt))
        // Default 5 minute expiry for auth states
        stmt.setTimestamp(5, new Timestamp(state.createdAt + 300000))
        stmt.setString(6, state.nonce)
        stmt.setString(7, state.browserBindingHash)
        stmt.executeUpdate()
      finally
        stmt.close()
    } *> logger.debug(s"Stored auth state: ${state.state.take(8)}...")

  override def getAndRemoveAuthState(stateKey: String): IO[Option[AuthorizationState]] =
    // Single atomic statement: a state can only ever be consumed once, even under concurrency
    ConnectionPool.withConnection(pool) { conn =>
      val stmt = conn.prepareStatement(
        """DELETE FROM oauth_auth_state WHERE state = ?
          |RETURNING state, code_verifier, redirect_after_login, created_at, nonce, browser_binding_hash""".stripMargin
      )
      try
        stmt.setString(1, stateKey)
        val rs = stmt.executeQuery()
        if rs.next() then
          for
            nonce <- Option(rs.getString("nonce"))
            bindingHash <- Option(rs.getString("browser_binding_hash"))
          yield AuthorizationState(
            state = rs.getString("state"),
            codeVerifier = rs.getString("code_verifier"),
            redirectAfterLogin = Option(rs.getString("redirect_after_login")),
            createdAt = rs.getTimestamp("created_at").getTime,
            nonce = nonce,
            browserBindingHash = bindingHash
          )
        else None
      finally stmt.close()
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
        stmt.setArray(5, conn.createArrayOf("text", session.groups.toArray[AnyRef]))
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
            groups = readGroups(rs),
            provider = rs.getString("provider"),
            expiresAt = rs.getTimestamp("expires_at").getTime,
            createdAt = rs.getTimestamp("created_at").getTime
          ))
        else
          None
      finally
        stmt.close()
    }

  private def readGroups(rs: ResultSet): List[String] =
    Option(rs.getArray("groups")) match
      case Some(arr) =>
        try arr.getArray.asInstanceOf[Array[AnyRef]].toList.collect { case g: String => g }
        finally arr.free()
      case None => Nil

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
      groups TEXT[] NOT NULL DEFAULT '{}',
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
      expires_at TIMESTAMP NOT NULL,
      nonce VARCHAR(128),
      browser_binding_hash VARCHAR(128)
    );

    CREATE INDEX IF NOT EXISTS idx_oauth_auth_state_expires_at ON oauth_auth_state(expires_at);
  """

  /**
   * Idempotent migrations for tables created by earlier releases.
   *
   * - `oauth_sessions.groups` was a comma-joined TEXT column, which let a group named
   *   `x,admins` be read back as two groups. It is converted in place to TEXT[]; existing
   *   rows are split on commas exactly as they were previously read (empty/NULL become an
   *   empty array), so live sessions keep working until they expire.
   * - `oauth_auth_state` gains `nonce` and `browser_binding_hash`. In-flight login states
   *   created before the upgrade have neither and can never pass the callback checks, so
   *   they are deleted (users simply click "login" again).
   */
  val migrateTablesSql: String = """
    DO $$
    BEGIN
      IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'oauth_sessions'
          AND column_name = 'groups'
          AND data_type <> 'ARRAY'
      ) THEN
        ALTER TABLE oauth_sessions
          ALTER COLUMN groups TYPE TEXT[]
          USING CASE
            WHEN groups IS NULL OR groups = '' THEN ARRAY[]::TEXT[]
            ELSE array_remove(string_to_array(groups, ','), '')
          END;
        ALTER TABLE oauth_sessions ALTER COLUMN groups SET DEFAULT '{}';
        ALTER TABLE oauth_sessions ALTER COLUMN groups SET NOT NULL;
      END IF;
    END $$;

    ALTER TABLE oauth_auth_state ADD COLUMN IF NOT EXISTS nonce VARCHAR(128);
    ALTER TABLE oauth_auth_state ADD COLUMN IF NOT EXISTS browser_binding_hash VARCHAR(128);
    DELETE FROM oauth_auth_state WHERE nonce IS NULL OR browser_binding_hash IS NULL;
  """

  /**
   * Initialize database tables if they don't exist, then apply migrations.
   */
  def initializeTables(pool: HikariDataSource): IO[Unit] =
    ConnectionPool.withConnection(pool) { conn =>
      val stmt = conn.createStatement()
      try
        stmt.execute(createTablesSql)
        stmt.execute(migrateTablesSql)
      finally
        stmt.close()
    }

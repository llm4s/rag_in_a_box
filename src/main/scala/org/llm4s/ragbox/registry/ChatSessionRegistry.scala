package org.llm4s.ragbox.registry

import cats.effect.IO
import io.circe.syntax._
import io.circe.parser._
import org.llm4s.ragbox.db.ConnectionPool
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.model.Codecs._

import java.sql.{Connection, PreparedStatement, ResultSet, Timestamp}
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Raised when a message is added to a chat session that does not exist. */
final case class ChatSessionNotFoundError(sessionId: String)
    extends RuntimeException(s"Chat session '$sessionId' not found")

/**
 * PostgreSQL-backed chat session registry.
 *
 * Stores and manages chat sessions and messages for persistent conversation history.
 * Borrows a pooled connection per operation (see [[ConnectionPool]]), so it is safe for
 * concurrent use across HTTP requests.
 *
 * Ids are `chat-<uuid>` / `msg-<uuid>`. Older installations created 8-hex-digit ids
 * (`chat-1a2b3c4d`); those are still found by every lookup, which compares ids as plain text.
 */
class ChatSessionRegistry(dataSource: DataSource) {
  import ChatSessionRegistry._

  /**
   * Initialize the registry and ensure tables, indexes and constraints exist.
   *
   * Runs in one transaction under an advisory lock, so instances starting together do not race
   * on the DDL. Adds `UNIQUE(session_id, message_index)` if missing; sessions whose messages got
   * duplicate indexes from the old read-count-then-insert race are renumbered first (in their
   * existing order), so the constraint can be added.
   */
  def initialize(): IO[Unit] = ConnectionPool.withTransaction(dataSource) { conn =>
    val stmt = conn.createStatement()
    try {
      stmt.execute("SELECT pg_advisory_xact_lock(hashtext('ragbox.chat_sessions.schema'))")
      stmt.execute(
        """CREATE TABLE IF NOT EXISTS chat_sessions (
          |    id VARCHAR(64) PRIMARY KEY,
          |    title VARCHAR(255),
          |    collection_pattern VARCHAR(255) DEFAULT '*',
          |    message_count INTEGER NOT NULL DEFAULT 0,
          |    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          |    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          |    is_archived BOOLEAN NOT NULL DEFAULT FALSE
          |)""".stripMargin
      )
      stmt.execute(
        """CREATE INDEX IF NOT EXISTS idx_chat_sessions_updated_at
          |    ON chat_sessions(updated_at DESC)""".stripMargin
      )
      stmt.execute(
        """CREATE INDEX IF NOT EXISTS idx_chat_sessions_archived
          |    ON chat_sessions(is_archived)""".stripMargin
      )
      // Session owner (the authenticated principal's id). Sessions created before owners
      // existed keep NULL and are visible to administrators only.
      stmt.execute("ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS owner_id VARCHAR(255)")
      stmt.execute(
        """CREATE INDEX IF NOT EXISTS idx_chat_sessions_owner
          |    ON chat_sessions(owner_id, updated_at DESC)""".stripMargin
      )
      stmt.execute(
        """CREATE TABLE IF NOT EXISTS chat_messages (
          |    id VARCHAR(64) PRIMARY KEY,
          |    session_id VARCHAR(64) NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
          |    role VARCHAR(16) NOT NULL,
          |    content TEXT NOT NULL,
          |    contexts JSONB,
          |    usage_info JSONB,
          |    rating INTEGER,
          |    query_log_id VARCHAR(64),
          |    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          |    message_index INTEGER NOT NULL
          |)""".stripMargin
      )
      stmt.execute(
        """CREATE INDEX IF NOT EXISTS idx_chat_messages_session_id
          |    ON chat_messages(session_id, message_index)""".stripMargin
      )
      stmt.execute(
        """CREATE INDEX IF NOT EXISTS idx_chat_messages_query_log_id
          |    ON chat_messages(query_log_id)""".stripMargin
      )
      stmt.execute(
        s"""DO $$$$
           |BEGIN
           |    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = '$MessageIndexConstraint') THEN
           |        -- Renumber sessions that have duplicate indexes, keeping their current order
           |        UPDATE chat_messages m SET message_index = r.rn - 1
           |        FROM (
           |            SELECT id, ROW_NUMBER() OVER (
           |                       PARTITION BY session_id ORDER BY message_index, created_at, id) AS rn
           |            FROM chat_messages
           |            WHERE session_id IN (
           |                SELECT session_id FROM chat_messages
           |                GROUP BY session_id, message_index HAVING COUNT(*) > 1)
           |        ) r
           |        WHERE m.id = r.id AND m.message_index <> r.rn - 1;
           |        ALTER TABLE chat_messages
           |            ADD CONSTRAINT $MessageIndexConstraint UNIQUE (session_id, message_index);
           |    END IF;
           |END $$$$""".stripMargin
      )
    } finally {
      stmt.close()
    }
  }

  // ============================================================
  // Session Operations
  // ============================================================

  /**
   * Create a new chat session.
   *
   * @param ownerId the owning principal's id; the session is only visible to its owner and to
   *                administrators
   */
  def createSession(request: CreateChatSessionRequest, ownerId: Option[String] = None): IO[ChatSession] = ConnectionPool.withConnection(dataSource) { conn =>
    val id = s"chat-${UUID.randomUUID()}"
    val now = Instant.now()

    val sql =
      """INSERT INTO chat_sessions
        |    (id, title, collection_pattern, message_count, created_at, updated_at, is_archived, owner_id)
        |VALUES (?, ?, ?, 0, ?, ?, FALSE, ?)""".stripMargin
    val stmt = conn.prepareStatement(sql)
    try {
      stmt.setString(1, id)
      stmt.setString(2, request.title.orNull)
      stmt.setString(3, request.collectionPattern.getOrElse("*"))
      stmt.setTimestamp(4, Timestamp.from(now))
      stmt.setTimestamp(5, Timestamp.from(now))
      stmt.setString(6, ownerId.orNull)
      stmt.executeUpdate()

      ChatSession(
        id = id,
        title = request.title,
        collectionPattern = request.collectionPattern.getOrElse("*"),
        messageCount = 0,
        createdAt = now,
        updatedAt = now,
        isArchived = false,
        ownerId = ownerId
      )
    } finally {
      stmt.close()
    }
  }

  /**
   * Get a session by ID.
   */
  def getSession(id: String): IO[Option[ChatSession]] =
    ConnectionPool.withConnection(dataSource)(conn => selectSession(conn, id))

  /**
   * List sessions, most recently updated first.
   *
   * @param limit   maximum number of sessions to return
   * @param offset  number of sessions to skip
   * @param ownerId only this owner's sessions; None lists every session (administrators)
   */
  def listSessions(
    includeArchived: Boolean = false,
    limit: Int = DefaultSessionLimit,
    offset: Int = 0,
    ownerId: Option[String] = None
  ): IO[Seq[ChatSession]] = ConnectionPool.withConnection(dataSource) { conn =>
    val sql =
      s"""SELECT $SessionColumns
         |FROM chat_sessions ${whereClause(includeArchived, ownerId)}
         |ORDER BY updated_at DESC, id
         |LIMIT ? OFFSET ?""".stripMargin
    val stmt = conn.prepareStatement(sql)
    try {
      val next = ownerId.fold(1) { o => stmt.setString(1, o); 2 }
      stmt.setInt(next, math.max(0, limit))
      stmt.setInt(next + 1, math.max(0, offset))
      collect(stmt.executeQuery())(rowToSession)
    } finally {
      stmt.close()
    }
  }

  /**
   * Count sessions (the `total` for [[listSessions]] pages).
   */
  def countSessions(includeArchived: Boolean = false, ownerId: Option[String] = None): IO[Int] = ConnectionPool.withConnection(dataSource) { conn =>
    val stmt = conn.prepareStatement(s"SELECT COUNT(*) FROM chat_sessions ${whereClause(includeArchived, ownerId)}")
    try {
      ownerId.foreach(stmt.setString(1, _))
      val rs = stmt.executeQuery()
      if (rs.next()) rs.getInt(1) else 0
    } finally {
      stmt.close()
    }
  }

  /**
   * Update a session. Returns the updated session, or None if it does not exist.
   *
   * One statement on one connection (`UPDATE ... RETURNING`), so it never waits for a second
   * pooled connection while holding the first.
   */
  def updateSession(id: String, request: UpdateChatSessionRequest): IO[Option[ChatSession]] =
    ConnectionPool.withConnection(dataSource) { conn =>
      val updates = scala.collection.mutable.ArrayBuffer.empty[(PreparedStatement, Int) => Unit]
      val columns = scala.collection.mutable.ArrayBuffer.empty[String]

      request.title.foreach { t =>
        columns += "title = ?"
        updates += ((s, i) => s.setString(i, t))
      }
      request.collectionPattern.foreach { c =>
        columns += "collection_pattern = ?"
        updates += ((s, i) => s.setString(i, c))
      }
      request.isArchived.foreach { a =>
        columns += "is_archived = ?"
        updates += ((s, i) => s.setBoolean(i, a))
      }

      if (columns.isEmpty) {
        selectSession(conn, id)
      } else {
        val now = Timestamp.from(Instant.now())
        columns += "updated_at = ?"
        updates += ((s, i) => s.setTimestamp(i, now))

        val sql = s"UPDATE chat_sessions SET ${columns.mkString(", ")} WHERE id = ? RETURNING $SessionColumns"
        val stmt = conn.prepareStatement(sql)
        try {
          updates.zipWithIndex.foreach { case (set, i) => set(stmt, i + 1) }
          stmt.setString(updates.size + 1, id)
          val rs = stmt.executeQuery()
          if (rs.next()) Some(rowToSession(rs)) else None
        } finally {
          stmt.close()
        }
      }
    }

  /**
   * Delete a session and all its messages.
   */
  def deleteSession(id: String): IO[Boolean] = ConnectionPool.withConnection(dataSource) { conn =>
    val sql = "DELETE FROM chat_sessions WHERE id = ?"
    val stmt = conn.prepareStatement(sql)
    try {
      stmt.setString(1, id)
      stmt.executeUpdate() > 0
    } finally {
      stmt.close()
    }
  }

  /**
   * Update the title of a session (auto-generate from first message).
   */
  def updateSessionTitle(sessionId: String, title: String): IO[Unit] = ConnectionPool.withConnection(dataSource) { conn =>
    val sql = "UPDATE chat_sessions SET title = ?, updated_at = ? WHERE id = ? AND title IS NULL"
    val stmt = conn.prepareStatement(sql)
    try {
      stmt.setString(1, title.take(50) + (if (title.length > 50) "..." else ""))
      stmt.setTimestamp(2, Timestamp.from(Instant.now()))
      stmt.setString(3, sessionId)
      stmt.executeUpdate()
      ()
    } finally {
      stmt.close()
    }
  }

  // ============================================================
  // Message Operations
  // ============================================================

  /**
   * Add a message to a session.
   *
   * Claims the next index and inserts the message in one transaction: the
   * `UPDATE ... RETURNING` increments the session's message count and row-locks the session,
   * so concurrent sends to the same session get distinct, gap-free indexes (also enforced by
   * `UNIQUE(session_id, message_index)`). Fails with [[ChatSessionNotFoundError]] if the
   * session does not exist.
   */
  def addMessage(
    sessionId: String,
    role: String,
    content: String,
    contexts: Option[Seq[ContextItem]] = None,
    usage: Option[UsageInfo] = None,
    queryLogId: Option[String] = None
  ): IO[ChatMessageRecord] = ConnectionPool.withTransaction(dataSource) { conn =>
    val id = s"msg-${UUID.randomUUID()}"
    val now = Instant.now()

    val claimStmt = conn.prepareStatement(
      """UPDATE chat_sessions SET message_count = message_count + 1, updated_at = ?
        |WHERE id = ?
        |RETURNING message_count - 1""".stripMargin
    )
    val messageIndex = try {
      claimStmt.setTimestamp(1, Timestamp.from(now))
      claimStmt.setString(2, sessionId)
      val rs = claimStmt.executeQuery()
      if (rs.next()) rs.getInt(1) else throw ChatSessionNotFoundError(sessionId)
    } finally {
      claimStmt.close()
    }

    val sql =
      """INSERT INTO chat_messages
        |    (id, session_id, role, content, contexts, usage_info, query_log_id, created_at, message_index)
        |VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)""".stripMargin
    val stmt = conn.prepareStatement(sql)
    try {
      stmt.setString(1, id)
      stmt.setString(2, sessionId)
      stmt.setString(3, role)
      stmt.setString(4, content)
      stmt.setString(5, contexts.map(_.asJson.noSpaces).orNull)
      stmt.setString(6, usage.map(_.asJson.noSpaces).orNull)
      stmt.setString(7, queryLogId.orNull)
      stmt.setTimestamp(8, Timestamp.from(now))
      stmt.setInt(9, messageIndex)
      stmt.executeUpdate()
    } finally {
      stmt.close()
    }

    ChatMessageRecord(
      id = id,
      sessionId = sessionId,
      role = role,
      content = content,
      contexts = contexts,
      usage = usage,
      rating = None,
      queryLogId = queryLogId,
      createdAt = now,
      messageIndex = messageIndex
    )
  }

  /**
   * Get a session's messages in conversation order.
   *
   * Returns the most recent `limit` messages (after skipping the `offset` most recent ones), so
   * the default page of a long conversation is its latest part. Sessions with at most
   * [[DefaultMessageLimit]] messages come back whole, as before pagination was added.
   */
  def getMessages(
    sessionId: String,
    limit: Int = DefaultMessageLimit,
    offset: Int = 0
  ): IO[Seq[ChatMessageRecord]] = ConnectionPool.withConnection(dataSource) { conn =>
    val sql =
      s"""SELECT * FROM (
         |    SELECT $MessageColumns
         |    FROM chat_messages WHERE session_id = ?
         |    ORDER BY message_index DESC
         |    LIMIT ? OFFSET ?
         |) latest
         |ORDER BY message_index ASC""".stripMargin
    val stmt = conn.prepareStatement(sql)
    try {
      stmt.setString(1, sessionId)
      stmt.setInt(2, math.max(0, limit))
      stmt.setInt(3, math.max(0, offset))
      collect(stmt.executeQuery())(rowToMessage)
    } finally {
      stmt.close()
    }
  }

  /**
   * Get a message by ID.
   */
  def getMessage(id: String): IO[Option[ChatMessageRecord]] = ConnectionPool.withConnection(dataSource) { conn =>
    val stmt = conn.prepareStatement(s"SELECT $MessageColumns FROM chat_messages WHERE id = ?")
    try {
      stmt.setString(1, id)
      val rs = stmt.executeQuery()
      if (rs.next()) Some(rowToMessage(rs)) else None
    } finally {
      stmt.close()
    }
  }

  /**
   * Rate a message.
   */
  def rateMessage(messageId: String, rating: Int): IO[Boolean] = ConnectionPool.withConnection(dataSource) { conn =>
    val sql = "UPDATE chat_messages SET rating = ? WHERE id = ?"
    val stmt = conn.prepareStatement(sql)
    try {
      stmt.setInt(1, rating)
      stmt.setString(2, messageId)
      stmt.executeUpdate() > 0
    } finally {
      stmt.close()
    }
  }

  /**
   * Close the registry. The connection pool is owned externally, so there
   * is nothing to release here.
   */
  def close(): IO[Unit] = IO.unit

  private def selectSession(conn: Connection, id: String): Option[ChatSession] = {
    val stmt = conn.prepareStatement(s"SELECT $SessionColumns FROM chat_sessions WHERE id = ?")
    try {
      stmt.setString(1, id)
      val rs = stmt.executeQuery()
      if (rs.next()) Some(rowToSession(rs)) else None
    } finally {
      stmt.close()
    }
  }

  private def whereClause(includeArchived: Boolean, ownerId: Option[String]): String = {
    val conditions = (if (includeArchived) Nil else List("is_archived = FALSE")) ++
      ownerId.map(_ => "owner_id = ?").toList
    if (conditions.isEmpty) "" else conditions.mkString("WHERE ", " AND ", "")
  }

  private def collect[A](rs: ResultSet)(row: ResultSet => A): Seq[A] = {
    val out = Vector.newBuilder[A]
    while (rs.next()) out += row(rs)
    out.result()
  }

  private def rowToSession(rs: ResultSet): ChatSession = {
    ChatSession(
      id = rs.getString("id"),
      title = Option(rs.getString("title")),
      collectionPattern = rs.getString("collection_pattern"),
      messageCount = rs.getInt("message_count"),
      createdAt = rs.getTimestamp("created_at").toInstant,
      updatedAt = rs.getTimestamp("updated_at").toInstant,
      isArchived = rs.getBoolean("is_archived"),
      ownerId = Option(rs.getString("owner_id"))
    )
  }

  private def rowToMessage(rs: ResultSet): ChatMessageRecord = {
    val contextsJson = Option(rs.getString("contexts"))
    val usageJson = Option(rs.getString("usage_info"))

    val contexts = contextsJson.flatMap { json =>
      decode[Seq[ContextItem]](json).toOption
    }
    val usage = usageJson.flatMap { json =>
      decode[UsageInfo](json).toOption
    }

    ChatMessageRecord(
      id = rs.getString("id"),
      sessionId = rs.getString("session_id"),
      role = rs.getString("role"),
      content = rs.getString("content"),
      contexts = contexts,
      usage = usage,
      rating = Option(rs.getInt("rating")).filter(_ => !rs.wasNull()),
      queryLogId = Option(rs.getString("query_log_id")),
      createdAt = rs.getTimestamp("created_at").toInstant,
      messageIndex = rs.getInt("message_index")
    )
  }
}

object ChatSessionRegistry {

  /** Default (and maximum) page size for session listings. */
  val DefaultSessionLimit: Int = 100
  val MaxSessionLimit: Int = 1000

  /** Default (and maximum) number of messages returned with a session. */
  val DefaultMessageLimit: Int = 1000
  val MaxMessageLimit: Int = 1000

  private val MessageIndexConstraint = "uq_chat_messages_session_index"

  private val SessionColumns =
    "id, title, collection_pattern, message_count, created_at, updated_at, is_archived, owner_id"

  private val MessageColumns =
    "id, session_id, role, content, contexts, usage_info, rating, query_log_id, created_at, message_index"

  /**
   * Create and initialize a chat session registry backed by a connection pool.
   */
  def apply(dataSource: DataSource): IO[ChatSessionRegistry] = {
    val registry = new ChatSessionRegistry(dataSource)
    registry.initialize().as(registry)
  }
}

package org.llm4s.ragbox.registry

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax._
import io.circe.parser._
import org.llm4s.ragbox.config.DatabaseConfig
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.model.Codecs._

import java.sql.{Connection, DriverManager, ResultSet, Timestamp}
import java.time.Instant
import java.util.{Properties, UUID}
import scala.compiletime.uninitialized

/**
 * PostgreSQL-backed chat session registry.
 *
 * Stores and manages chat sessions and messages for persistent conversation history.
 */
class ChatSessionRegistry(dbConfig: DatabaseConfig) {

  private var connection: Connection = uninitialized

  private def getConnection(): Connection = connection
  private def releaseConnection(conn: Connection): Unit = {
    // Connection is held for the lifetime of the registry
  }

  /**
   * Initialize the registry and ensure tables exist.
   */
  def initialize(): IO[Unit] = IO {
    Class.forName("org.postgresql.Driver")

    val props = new Properties()
    props.setProperty("user", dbConfig.effectiveUser)
    props.setProperty("password", dbConfig.effectivePassword)

    connection = DriverManager.getConnection(dbConfig.connectionString, props)

    val conn = getConnection()
    try {
      val stmt = conn.createStatement()
      try {
        // Create chat_sessions table
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

        // Create chat_messages table
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
      } finally {
        stmt.close()
      }
    } finally {
      releaseConnection(conn)
    }
  }

  // ============================================================
  // Session Operations
  // ============================================================

  /**
   * Create a new chat session.
   */
  def createSession(request: CreateChatSessionRequest): IO[ChatSession] = IO {
    val conn = getConnection()
    try {
      val id = s"chat-${UUID.randomUUID().toString.take(8)}"
      val now = Instant.now()

      val sql =
        """INSERT INTO chat_sessions
          |    (id, title, collection_pattern, message_count, created_at, updated_at, is_archived)
          |VALUES (?, ?, ?, 0, ?, ?, FALSE)""".stripMargin
      val stmt = conn.prepareStatement(sql)
      try {
        stmt.setString(1, id)
        stmt.setString(2, request.title.orNull)
        stmt.setString(3, request.collectionPattern.getOrElse("*"))
        stmt.setTimestamp(4, Timestamp.from(now))
        stmt.setTimestamp(5, Timestamp.from(now))
        stmt.executeUpdate()

        ChatSession(
          id = id,
          title = request.title,
          collectionPattern = request.collectionPattern.getOrElse("*"),
          messageCount = 0,
          createdAt = now,
          updatedAt = now,
          isArchived = false
        )
      } finally {
        stmt.close()
      }
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * Get a session by ID.
   */
  def getSession(id: String): IO[Option[ChatSession]] = IO {
    val conn = getConnection()
    try {
      val sql =
        """SELECT id, title, collection_pattern, message_count, created_at, updated_at, is_archived
          |FROM chat_sessions WHERE id = ?""".stripMargin
      val stmt = conn.prepareStatement(sql)
      try {
        stmt.setString(1, id)
        val rs = stmt.executeQuery()
        if (rs.next()) Some(rowToSession(rs)) else None
      } finally {
        stmt.close()
      }
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * List all sessions.
   */
  def listSessions(includeArchived: Boolean = false): IO[Seq[ChatSession]] = IO {
    val conn = getConnection()
    try {
      val whereClause = if (includeArchived) "" else "WHERE is_archived = FALSE"
      val sql =
        s"""SELECT id, title, collection_pattern, message_count, created_at, updated_at, is_archived
           |FROM chat_sessions $whereClause
           |ORDER BY updated_at DESC""".stripMargin
      val stmt = conn.prepareStatement(sql)
      try {
        val rs = stmt.executeQuery()
        val sessions = scala.collection.mutable.ArrayBuffer.empty[ChatSession]
        while (rs.next()) {
          sessions += rowToSession(rs)
        }
        sessions.toSeq
      } finally {
        stmt.close()
      }
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * Update a session.
   */
  def updateSession(id: String, request: UpdateChatSessionRequest): IO[Option[ChatSession]] = IO {
    val conn = getConnection()
    try {
      val updates = scala.collection.mutable.ArrayBuffer.empty[String]
      val values = scala.collection.mutable.ArrayBuffer.empty[Any]

      request.title.foreach { t =>
        updates += "title = ?"
        values += t
      }
      request.collectionPattern.foreach { c =>
        updates += "collection_pattern = ?"
        values += c
      }
      request.isArchived.foreach { a =>
        updates += "is_archived = ?"
        values += a
      }

      if (updates.isEmpty) {
        getSession(id).unsafeRunSync()
      } else {
        updates += "updated_at = ?"
        values += Timestamp.from(Instant.now())

        val sql = s"UPDATE chat_sessions SET ${updates.mkString(", ")} WHERE id = ?"
        val stmt = conn.prepareStatement(sql)
        try {
          values.zipWithIndex.foreach { case (v, i) =>
            v match {
              case s: String => stmt.setString(i + 1, s)
              case b: Boolean => stmt.setBoolean(i + 1, b)
              case t: Timestamp => stmt.setTimestamp(i + 1, t)
              case _ => ()
            }
          }
          stmt.setString(values.size + 1, id)
          val updated = stmt.executeUpdate()
          if (updated > 0) {
            getSession(id).unsafeRunSync()
          } else {
            None
          }
        } finally {
          stmt.close()
        }
      }
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * Delete a session and all its messages.
   */
  def deleteSession(id: String): IO[Boolean] = IO {
    val conn = getConnection()
    try {
      val sql = "DELETE FROM chat_sessions WHERE id = ?"
      val stmt = conn.prepareStatement(sql)
      try {
        stmt.setString(1, id)
        stmt.executeUpdate() > 0
      } finally {
        stmt.close()
      }
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * Update the title of a session (auto-generate from first message).
   */
  def updateSessionTitle(sessionId: String, title: String): IO[Unit] = IO {
    val conn = getConnection()
    try {
      val sql = "UPDATE chat_sessions SET title = ?, updated_at = ? WHERE id = ? AND title IS NULL"
      val stmt = conn.prepareStatement(sql)
      try {
        stmt.setString(1, title.take(50) + (if (title.length > 50) "..." else ""))
        stmt.setTimestamp(2, Timestamp.from(Instant.now()))
        stmt.setString(3, sessionId)
        stmt.executeUpdate()
      } finally {
        stmt.close()
      }
    } finally {
      releaseConnection(conn)
    }
  }

  // ============================================================
  // Message Operations
  // ============================================================

  /**
   * Add a message to a session.
   */
  def addMessage(
    sessionId: String,
    role: String,
    content: String,
    contexts: Option[Seq[ContextItem]] = None,
    usage: Option[UsageInfo] = None,
    queryLogId: Option[String] = None
  ): IO[ChatMessageRecord] = IO {
    val conn = getConnection()
    try {
      val id = s"msg-${UUID.randomUUID().toString.take(8)}"
      val now = Instant.now()

      // Get current message count for ordering
      val countSql = "SELECT message_count FROM chat_sessions WHERE id = ?"
      val countStmt = conn.prepareStatement(countSql)
      val messageIndex = try {
        countStmt.setString(1, sessionId)
        val rs = countStmt.executeQuery()
        if (rs.next()) rs.getInt("message_count") else 0
      } finally {
        countStmt.close()
      }

      // Insert message
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

      // Update session message count and updated_at
      val updateSql = "UPDATE chat_sessions SET message_count = message_count + 1, updated_at = ? WHERE id = ?"
      val updateStmt = conn.prepareStatement(updateSql)
      try {
        updateStmt.setTimestamp(1, Timestamp.from(now))
        updateStmt.setString(2, sessionId)
        updateStmt.executeUpdate()
      } finally {
        updateStmt.close()
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
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * Get all messages for a session.
   */
  def getMessages(sessionId: String): IO[Seq[ChatMessageRecord]] = IO {
    val conn = getConnection()
    try {
      val sql =
        """SELECT id, session_id, role, content, contexts, usage_info, rating, query_log_id, created_at, message_index
          |FROM chat_messages WHERE session_id = ?
          |ORDER BY message_index ASC""".stripMargin
      val stmt = conn.prepareStatement(sql)
      try {
        stmt.setString(1, sessionId)
        val rs = stmt.executeQuery()
        val messages = scala.collection.mutable.ArrayBuffer.empty[ChatMessageRecord]
        while (rs.next()) {
          messages += rowToMessage(rs)
        }
        messages.toSeq
      } finally {
        stmt.close()
      }
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * Get a message by ID.
   */
  def getMessage(id: String): IO[Option[ChatMessageRecord]] = IO {
    val conn = getConnection()
    try {
      val sql =
        """SELECT id, session_id, role, content, contexts, usage_info, rating, query_log_id, created_at, message_index
          |FROM chat_messages WHERE id = ?""".stripMargin
      val stmt = conn.prepareStatement(sql)
      try {
        stmt.setString(1, id)
        val rs = stmt.executeQuery()
        if (rs.next()) Some(rowToMessage(rs)) else None
      } finally {
        stmt.close()
      }
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * Rate a message.
   */
  def rateMessage(messageId: String, rating: Int): IO[Boolean] = IO {
    val conn = getConnection()
    try {
      val sql = "UPDATE chat_messages SET rating = ? WHERE id = ?"
      val stmt = conn.prepareStatement(sql)
      try {
        stmt.setInt(1, rating)
        stmt.setString(2, messageId)
        stmt.executeUpdate() > 0
      } finally {
        stmt.close()
      }
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * Close the registry.
   */
  def close(): IO[Unit] = IO {
    if (connection != null && !connection.isClosed) {
      connection.close()
    }
  }

  private def rowToSession(rs: ResultSet): ChatSession = {
    ChatSession(
      id = rs.getString("id"),
      title = Option(rs.getString("title")),
      collectionPattern = rs.getString("collection_pattern"),
      messageCount = rs.getInt("message_count"),
      createdAt = rs.getTimestamp("created_at").toInstant,
      updatedAt = rs.getTimestamp("updated_at").toInstant,
      isArchived = rs.getBoolean("is_archived")
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

  /**
   * Create and initialize a chat session registry.
   */
  def apply(dbConfig: DatabaseConfig): IO[ChatSessionRegistry] = {
    val registry = new ChatSessionRegistry(dbConfig)
    registry.initialize().as(registry)
  }
}

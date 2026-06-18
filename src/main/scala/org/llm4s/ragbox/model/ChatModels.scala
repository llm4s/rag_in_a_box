package org.llm4s.ragbox.model

import java.time.Instant

/**
 * Models for the chat session persistence feature.
 *
 * Supports maintaining multiple chat conversations with
 * backend-persisted history and analytics integration.
 */

/**
 * A chat session containing multiple messages.
 *
 * @param id Unique session identifier
 * @param title Optional title (auto-generated from first message)
 * @param collectionPattern Collection filter for queries in this session
 * @param messageCount Number of messages in the session
 * @param createdAt When the session was created
 * @param updatedAt When the session was last updated
 * @param isArchived Whether the session is archived
 */
final case class ChatSession(
  id: String,
  title: Option[String] = None,
  collectionPattern: String = "*",
  messageCount: Int = 0,
  createdAt: Instant,
  updatedAt: Instant,
  isArchived: Boolean = false
)

/**
 * A single chat message stored in the database.
 *
 * @param id Unique message identifier
 * @param sessionId ID of the parent session
 * @param role Message role ("user" or "assistant")
 * @param content Message text content
 * @param contexts Retrieved context chunks (for assistant messages)
 * @param usage Token usage info (for assistant messages)
 * @param rating User rating (1-5)
 * @param queryLogId Link to query_logs for analytics
 * @param createdAt When the message was created
 * @param messageIndex Order within the session
 */
final case class ChatMessageRecord(
  id: String,
  sessionId: String,
  role: String,
  content: String,
  contexts: Option[Seq[ContextItem]] = None,
  usage: Option[UsageInfo] = None,
  rating: Option[Int] = None,
  queryLogId: Option[String] = None,
  createdAt: Instant,
  messageIndex: Int
)

/**
 * Request to create a new chat session.
 */
final case class CreateChatSessionRequest(
  title: Option[String] = None,
  collectionPattern: Option[String] = None
)

/**
 * Response after creating a chat session.
 */
final case class CreateChatSessionResponse(
  session: ChatSession,
  message: String
)

/**
 * Request to update a chat session.
 */
final case class UpdateChatSessionRequest(
  title: Option[String] = None,
  collectionPattern: Option[String] = None,
  isArchived: Option[Boolean] = None
)

/**
 * List response for chat sessions.
 */
final case class ChatSessionListResponse(
  sessions: Seq[ChatSession],
  total: Int
)

/**
 * Response for a chat session with all its messages.
 */
final case class ChatSessionDetailResponse(
  session: ChatSession,
  messages: Seq[ChatMessageRecord]
)

/**
 * Request to send a message in a chat session.
 */
final case class SendChatMessageRequest(
  content: String
)

/**
 * Request to rate a chat message.
 */
final case class RateChatMessageRequest(
  rating: Int
)

/**
 * Response for chat operations.
 */
final case class ChatOperationResponse(
  success: Boolean,
  message: String
)

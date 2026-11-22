package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.syntax.all._
import fs2.Stream
import io.circe.Json
import io.circe.syntax._
import org.http4s._
import org.llm4s.ragbox.auth.{PermissionContext, Principal, TokenScope}
import org.llm4s.ragbox.validation.InputValidation
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Type`
import org.llm4s.ragbox.config.AppConfig
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.model.Codecs._
import org.llm4s.ragbox.registry.{ChatSessionRegistry, QueryLogRegistryBase, QueryLogging}
import org.llm4s.ragbox.service.{Llm4sPipeline, RAGService}

import java.util.UUID

/**
 * HTTP routes for chat session operations.
 *
 * Provides persistent conversation history with backend storage.
 */
object ChatRoutes {

  // Query parameter extractor for includeArchived
  object IncludeArchivedParam extends OptionalQueryParamDecoderMatcher[Boolean]("includeArchived")
  object LimitParam extends OptionalQueryParamDecoderMatcher[Int]("limit")
  object OffsetParam extends OptionalQueryParamDecoderMatcher[Int]("offset")

  private def clamp(value: Option[Int], default: Int, max: Int): Int =
    math.min(math.max(value.getOrElse(default), 0), max)

  /**
   * The session, if `principal` may see it: its owner, or any administrator. Sessions created
   * before owners existed have none and are visible to administrators only. Other principals
   * get None (404), so session ids are not confirmed to exist.
   */
  private def visibleSession(chatRegistry: ChatSessionRegistry, principal: Principal, id: String): IO[Option[ChatSession]] =
    chatRegistry.getSession(id).map(_.filter(s => principal.isAdmin || s.ownerId.contains(principal.id)))

  /** Session listings: administrators see every session, others only their own. */
  private def ownerFilter(principal: Principal): Option[String] =
    if (principal.isAdmin) None else Some(principal.id)

  /**
   * Chat sessions and messages. Every session belongs to the principal that created it; only
   * that principal and administrators can list, read, change, delete or post to it.
   */
  def routes(
    chatRegistry: ChatSessionRegistry,
    ragService: RAGService,
    queryLogRegistry: QueryLogRegistryBase,
    config: Option[AppConfig] = None
  ): AuthedRoutes[Principal, IO] =
    // Access tokens need the `query` scope for chat, and may only chat over their collections
    Authz.scopeForPaths(TokenScope.Query, "/api/v1/chat")(AuthedRoutes.of[Principal, IO] {

    // ============================================================
    // Session Endpoints
    // ============================================================

    // POST /api/v1/chat/sessions - Create new session
    case (req @ POST -> Root / "api" / "v1" / "chat" / "sessions") as principal =>
      for {
        body <- req.as[CreateChatSessionRequest]
        response <- Authz.requirePattern(principal, body.collectionPattern.getOrElse("*")) {
          chatRegistry.createSession(body, Some(principal.id)).flatMap(session =>
            Created(CreateChatSessionResponse(session, "Session created").asJson))
        }
      } yield response

    // GET /api/v1/chat/sessions?limit=&offset= - List sessions, most recently updated first.
    // `total` is the number of sessions, across all pages.
    case GET -> Root / "api" / "v1" / "chat" / "sessions" :? IncludeArchivedParam(includeArchived) +& LimitParam(limit) +& OffsetParam(offset) as principal =>
      val archived = includeArchived.getOrElse(false)
      for {
        sessions <- chatRegistry.listSessions(
          archived,
          clamp(limit, ChatSessionRegistry.DefaultSessionLimit, ChatSessionRegistry.MaxSessionLimit),
          math.max(offset.getOrElse(0), 0),
          ownerFilter(principal)
        )
        total <- chatRegistry.countSessions(archived, ownerFilter(principal))
        response <- Ok(ChatSessionListResponse(sessions, total).asJson)
      } yield response

    // GET /api/v1/chat/sessions/:id?limit=&offset= - Get session with its latest messages
    case GET -> Root / "api" / "v1" / "chat" / "sessions" / id :? LimitParam(limit) +& OffsetParam(offset) as principal =>
      for {
        sessionOpt <- visibleSession(chatRegistry, principal, id)
        response <- sessionOpt match {
          case Some(session) =>
            for {
              messages <- chatRegistry.getMessages(
                id,
                clamp(limit, ChatSessionRegistry.DefaultMessageLimit, ChatSessionRegistry.MaxMessageLimit),
                math.max(offset.getOrElse(0), 0)
              )
              resp <- Ok(ChatSessionDetailResponse(session, messages).asJson)
            } yield resp
          case None =>
            NotFound(ErrorResponse.notFound(s"Session '$id' not found").asJson)
        }
      } yield response

    // PUT /api/v1/chat/sessions/:id - Update session
    case (req @ PUT -> Root / "api" / "v1" / "chat" / "sessions" / id) as principal =>
      for {
        body <- req.as[UpdateChatSessionRequest]
        visible <- visibleSession(chatRegistry, principal, id)
        response <- visible match {
          case None => NotFound(ErrorResponse.notFound(s"Session '$id' not found").asJson)
          case Some(_) =>
            val update = chatRegistry.updateSession(id, body).flatMap {
              case Some(session) => Ok(session.asJson)
              case None => NotFound(ErrorResponse.notFound(s"Session '$id' not found").asJson)
            }
            body.collectionPattern.fold(update)(p => Authz.requirePattern(principal, p)(update))
        }
      } yield response

    // DELETE /api/v1/chat/sessions/:id - Delete session
    case DELETE -> Root / "api" / "v1" / "chat" / "sessions" / id as principal =>
      for {
        visible <- visibleSession(chatRegistry, principal, id)
        deleted <- visible.fold(IO.pure(false))(_ => chatRegistry.deleteSession(id))
        response <- if (deleted) {
          Ok(ChatOperationResponse(success = true, message = "Session deleted").asJson)
        } else {
          NotFound(ErrorResponse.notFound(s"Session '$id' not found").asJson)
        }
      } yield response

    // ============================================================
    // Message Endpoints
    // ============================================================

    // POST /api/v1/chat/sessions/:id/messages - Send message (non-streaming)
    case (req @ POST -> Root / "api" / "v1" / "chat" / "sessions" / sessionId / "messages") as principal =>
      for {
        sessionOpt <- visibleSession(chatRegistry, principal, sessionId)
        response <- sessionOpt match {
          case None =>
            NotFound(ErrorResponse.notFound(s"Session '$sessionId' not found").asJson)
          case Some(session) if !principal.canAccessPattern(session.collectionPattern) =>
            Authz.requirePattern(principal, session.collectionPattern)(IO.never)
          case Some(session) =>
            req.as[SendChatMessageRequest].flatMap { body =>
            InputValidation.toResponse(InputValidation.validateQuery(body.content)).getOrElse(for {

              // Earlier messages, sent to the LLM as conversation history

              history <- recentHistory(chatRegistry, sessionId, config)


              // Add user message
              userMessage <- chatRegistry.addMessage(
                sessionId = sessionId,
                role = "user",
                content = body.content
              )

              // Update session title if this is the first message
              _ <- if (session.messageCount == 0) {
                chatRegistry.updateSessionTitle(sessionId, body.content)
              } else {
                IO.unit
              }

              userId = Some(PermissionContext.userId(principal))

              startTime <- IO.realTimeInstant

              // Execute RAG query
              queryResult <- ((ragService.hasPermissions, ragService.principals) match {
                case (true, Some(principals)) =>
                  for {
                    auth <- PermissionContext.authorization(principal, principals)
                    response <- ragService.chatAnswer(body.content, history, Some(auth -> session.collectionPattern), topK = None)
                  } yield response
                case _ =>
                  ragService.chatAnswer(body.content, history, permissions = None, topK = None)
              }).attempt

              endTime <- IO.realTimeInstant
              totalLatencyMs = (endTime.toEpochMilli - startTime.toEpochMilli).toInt

              resp <- queryResult match {
                case Right(queryResponse) =>
                  for {
                    // Log the query
                    queryLogId <- QueryLogging.logAnswer(queryLogRegistry, body.content, Some(session.collectionPattern), userId, totalLatencyMs, queryResponse)

                    // Add assistant message with link to query log
                    assistantMessage <- chatRegistry.addMessage(
                      sessionId = sessionId,
                      role = "assistant",
                      content = queryResponse.answer,
                      contexts = Some(queryResponse.contexts),
                      usage = queryResponse.usage,
                      queryLogId = Some(queryLogId)
                    )

                    // Return both messages
                    result <- Ok(Json.obj(
                      "userMessage" -> userMessage.asJson,
                      "assistantMessage" -> assistantMessage.asJson
                    ))
                  } yield result

                case Left(e) =>
                  val errorMessage = if (e.getMessage != null && e.getMessage.contains("LLM client required")) {
                    "LLM not available. Set LLM_MODEL to a registered provider and its API key; the server log says why it could not be built."
                  } else {
                    Option(e.getMessage).getOrElse("Query failed")
                  }

                  for {
                    // Add error message
                    errorAssistant <- chatRegistry.addMessage(
                      sessionId = sessionId,
                      role = "assistant",
                      content = s"$ErrorReplyPrefix $errorMessage"
                    )

                    result <- Ok(Json.obj(
                      "userMessage" -> userMessage.asJson,
                      "assistantMessage" -> errorAssistant.asJson,
                      "error" -> Json.fromString(errorMessage)
                    ))
                  } yield result
              }
            } yield resp)
            }
        }
      } yield response

    // POST /api/v1/chat/sessions/:id/messages/stream - Send message with SSE streaming. The answer
    // arrives as one event once complete: llm4s has no streaming RAG answer API (llm4s/llm4s#2717).
    case (req @ POST -> Root / "api" / "v1" / "chat" / "sessions" / sessionId / "messages" / "stream") as principal =>
      for {
        sessionOpt <- visibleSession(chatRegistry, principal, sessionId)
        response <- sessionOpt match {
          case None =>
            NotFound(ErrorResponse.notFound(s"Session '$sessionId' not found").asJson)
          case Some(session) if !principal.canAccessPattern(session.collectionPattern) =>
            Authz.requirePattern(principal, session.collectionPattern)(IO.never)
          case Some(session) =>
            req.as[SendChatMessageRequest].flatMap { body =>
            InputValidation.toResponse(InputValidation.validateQuery(body.content)).getOrElse(for {

              // Earlier messages, sent to the LLM as conversation history

              history <- recentHistory(chatRegistry, sessionId, config)


              // Add user message first
              userMessage <- chatRegistry.addMessage(
                sessionId = sessionId,
                role = "user",
                content = body.content
              )

              // Update session title if this is the first message
              _ <- if (session.messageCount == 0) {
                chatRegistry.updateSessionTitle(sessionId, body.content)
              } else {
                IO.unit
              }

              // Generate a unique ID for this stream
              queryId = UUID.randomUUID().toString

              userId = Some(PermissionContext.userId(principal))

              // Create SSE stream
              sseStream: Stream[IO, String] = Stream.eval(IO.realTimeInstant).flatMap { startTime =>
                // Start event with user message ID
                val startEvent = formatSSE("start", Json.obj(
                  "queryId" -> Json.fromString(queryId),
                  "userMessageId" -> Json.fromString(userMessage.id)
                ).noSpaces)

                // Execute query and stream results
                val queryStream = Stream.eval {
                  ((ragService.hasPermissions, ragService.principals) match {
                    case (true, Some(principals)) =>
                      for {
                        auth <- PermissionContext.authorization(principal, principals)
                        response <- ragService.chatAnswer(body.content, history, Some(auth -> session.collectionPattern), topK = None)
                      } yield response
                    case _ =>
                      ragService.chatAnswer(body.content, history, permissions = None, topK = None)
                  }).attempt
                }.flatMap {
                  case Right(queryResponse) =>
                    // Stream contexts
                    val contextEvents = Stream.emits(queryResponse.contexts.zipWithIndex.map { case (ctx, idx) =>
                      formatSSE("context", QueryContextEvent(ctx, idx).asJson.noSpaces)
                    })

                    // Answer event
                    val answerEvent = Stream.emit(formatSSE("answer", QueryAnswerEvent(queryResponse.answer).asJson.noSpaces))

                    // Usage event
                    val usageEvent = queryResponse.usage match {
                      case Some(usage) => Stream.emit(formatSSE("usage", QueryUsageEvent(usage).asJson.noSpaces))
                      case None => Stream.empty
                    }

                    // Complete event
                    val completeEvent = Stream.emit(formatSSE("complete", QueryCompleteEvent(queryId, queryResponse.contexts.size).asJson.noSpaces))

                    // Save message and log asynchronously
                    val saveEffect = Stream.eval {
                      for {
                        endTime <- IO.realTimeInstant
                        totalLatencyMs = (endTime.toEpochMilli - startTime.toEpochMilli).toInt

                        // Log to query_logs
                        logId <- QueryLogging.logAnswer(queryLogRegistry, body.content, Some(session.collectionPattern), userId, totalLatencyMs, queryResponse)

                        // Save assistant message
                        _ <- chatRegistry.addMessage(
                          sessionId = sessionId,
                          role = "assistant",
                          content = queryResponse.answer,
                          contexts = Some(queryResponse.contexts),
                          usage = queryResponse.usage,
                          queryLogId = Some(logId)
                        )
                      } yield ()
                    }.drain

                    contextEvents ++ answerEvent ++ usageEvent ++ completeEvent ++ saveEffect

                  case Left(e) =>
                    val errorMessage = if (e.getMessage != null && e.getMessage.contains("LLM client required")) {
                      "LLM not available. Set LLM_MODEL to a registered provider and its API key; the server log says why it could not be built."
                    } else {
                      Option(e.getMessage).getOrElse("Unknown error")
                    }

                    // Save error message
                    val saveError = Stream.eval {
                      chatRegistry.addMessage(
                        sessionId = sessionId,
                        role = "assistant",
                        content = s"$ErrorReplyPrefix $errorMessage"
                      )
                    }.drain

                    Stream.emit(formatSSE("error", QueryErrorEvent("query_failed", errorMessage).asJson.noSpaces)) ++ saveError
                }

                Stream.emit(startEvent) ++ queryStream
              }

              // Raw UTF-8: Ok(sseStream) would pick the circe encoder and JSON-quote every event
              resp <- IO.pure(Response[IO](Status.Ok).withBodyStream(sseStream.through(fs2.text.utf8.encode)).withContentType(`Content-Type`(MediaType.`text/event-stream`)))
            } yield resp)
            }
        }
      } yield response

    // PUT /api/v1/chat/messages/:id/rate - Rate a message
    case (req @ PUT -> Root / "api" / "v1" / "chat" / "messages" / messageId / "rate") as principal =>
      for {
        body <- req.as[RateChatMessageRequest]
        // Validate rating
        response <- if (body.rating < 1 || body.rating > 5) {
          BadRequest(ErrorResponse.badRequest("Rating must be between 1 and 5").asJson)
        } else {
          for {
            // Get message to find queryLogId
            // Only messages in a session the principal may see
            message <- chatRegistry.getMessage(messageId)
            messageOpt <- message.flatTraverse(m => visibleSession(chatRegistry, principal, m.sessionId).map(_.as(m)))
            result <- messageOpt match {
              case None =>
                NotFound(ErrorResponse.notFound(s"Message '$messageId' not found").asJson)
              case Some(message) =>
                for {
                  // Update chat message rating
                  success <- chatRegistry.rateMessage(messageId, body.rating)

                  // Also update query_logs if linked
                  _ <- message.queryLogId match {
                    case Some(qid) =>
                      queryLogRegistry.addFeedback(qid, body.rating, None, None).attempt.void
                    case None =>
                      IO.unit
                  }

                  resp <- if (success) {
                    Ok(ChatOperationResponse(success = true, message = "Rating saved").asJson)
                  } else {
                    InternalServerError(ErrorResponse.internalError("Failed to save rating").asJson)
                  }
                } yield resp
            }
          } yield result
        }
      } yield response
  })

  /** Prefix of the assistant message stored when answering failed. */
  private[routes] val ErrorReplyPrefix: String = "Sorry, I encountered an error:"

  /**
   * The session's latest messages as conversation history for the LLM, bounded by
   * `rag.chat.history-messages`. Only answered exchanges are kept - a question whose answer
   * failed is dropped with its error reply - so the history alternates user and assistant turns.
   */
  private def recentHistory(
    chatRegistry: ChatSessionRegistry,
    sessionId: String,
    config: Option[AppConfig]
  ): IO[Seq[Llm4sPipeline.ChatTurn]] = {
    val limit = config.fold(org.llm4s.ragbox.config.ChatSettings().historyMessages)(_.rag.chat.historyMessages)
    if (limit <= 0) IO.pure(Seq.empty)
    else chatRegistry.getMessages(sessionId, limit).map(messages => historyTurns(messages))
  }

  /** Answered (user, assistant) exchanges from `messages` (oldest first), as conversation turns. */
  private[routes] def historyTurns(messages: Seq[ChatMessageRecord]): Seq[Llm4sPipeline.ChatTurn] =
    messages
      .sliding(2)
      .collect {
        case Seq(question, answer)
            if question.role == "user" && answer.role == "assistant" && !answer.content.startsWith(ErrorReplyPrefix) =>
          Seq(Llm4sPipeline.ChatTurn("user", question.content), Llm4sPipeline.ChatTurn("assistant", answer.content))
      }
      .flatten
      .toSeq

  /**
   * Format a message as a Server-Sent Event.
   */
  private def formatSSE(event: String, data: String): String =
    s"event: $event\ndata: $data\n\n"
}

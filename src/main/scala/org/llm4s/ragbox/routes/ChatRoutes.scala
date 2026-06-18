package org.llm4s.ragbox.routes

import cats.effect.IO
import fs2.Stream
import io.circe.Json
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Type`
import org.llm4s.ragbox.config.AppConfig
import org.llm4s.ragbox.middleware.UserContextMiddleware
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.model.Codecs._
import org.llm4s.ragbox.registry.{ChatSessionRegistry, QueryLogRegistryBase}
import org.llm4s.ragbox.service.RAGService

import java.util.UUID

/**
 * HTTP routes for chat session operations.
 *
 * Provides persistent conversation history with backend storage.
 */
object ChatRoutes {

  // Query parameter extractor for includeArchived
  object IncludeArchivedParam extends OptionalQueryParamDecoderMatcher[Boolean]("includeArchived")

  def routes(
    chatRegistry: ChatSessionRegistry,
    ragService: RAGService,
    queryLogRegistry: QueryLogRegistryBase,
    allowAdminHeader: Boolean = false,
    config: Option[AppConfig] = None
  ): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // ============================================================
    // Session Endpoints
    // ============================================================

    // POST /api/v1/chat/sessions - Create new session
    case req @ POST -> Root / "api" / "v1" / "chat" / "sessions" =>
      for {
        body <- req.as[CreateChatSessionRequest]
        session <- chatRegistry.createSession(body)
        response <- Created(CreateChatSessionResponse(session, "Session created").asJson)
      } yield response

    // GET /api/v1/chat/sessions - List sessions
    case GET -> Root / "api" / "v1" / "chat" / "sessions" :? IncludeArchivedParam(includeArchived) =>
      for {
        sessions <- chatRegistry.listSessions(includeArchived.getOrElse(false))
        response <- Ok(ChatSessionListResponse(sessions, sessions.size).asJson)
      } yield response

    // GET /api/v1/chat/sessions/:id - Get session with messages
    case GET -> Root / "api" / "v1" / "chat" / "sessions" / id =>
      for {
        sessionOpt <- chatRegistry.getSession(id)
        response <- sessionOpt match {
          case Some(session) =>
            for {
              messages <- chatRegistry.getMessages(id)
              resp <- Ok(ChatSessionDetailResponse(session, messages).asJson)
            } yield resp
          case None =>
            NotFound(ErrorResponse.notFound(s"Session '$id' not found").asJson)
        }
      } yield response

    // PUT /api/v1/chat/sessions/:id - Update session
    case req @ PUT -> Root / "api" / "v1" / "chat" / "sessions" / id =>
      for {
        body <- req.as[UpdateChatSessionRequest]
        updated <- chatRegistry.updateSession(id, body)
        response <- updated match {
          case Some(session) => Ok(session.asJson)
          case None => NotFound(ErrorResponse.notFound(s"Session '$id' not found").asJson)
        }
      } yield response

    // DELETE /api/v1/chat/sessions/:id - Delete session
    case DELETE -> Root / "api" / "v1" / "chat" / "sessions" / id =>
      for {
        deleted <- chatRegistry.deleteSession(id)
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
    case req @ POST -> Root / "api" / "v1" / "chat" / "sessions" / sessionId / "messages" =>
      for {
        sessionOpt <- chatRegistry.getSession(sessionId)
        response <- sessionOpt match {
          case None =>
            NotFound(ErrorResponse.notFound(s"Session '$sessionId' not found").asJson)
          case Some(session) =>
            for {
              body <- req.as[SendChatMessageRequest]

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

              // Extract user ID from headers if available
              userId = req.headers.get(org.typelevel.ci.CIString("X-User-Id"))
                .map(_.head.value)

              startTime <- IO.realTimeInstant

              // Execute RAG query
              queryResult <- ((ragService.hasPermissions, ragService.principals) match {
                case (true, Some(principals)) =>
                  for {
                    auth <- UserContextMiddleware.extractAuthWithAdmin(req, principals, allowAdminHeader)
                    response <- ragService.queryWithPermissionsAndAnswer(
                      question = body.content,
                      auth = auth,
                      collectionPattern = session.collectionPattern,
                      topK = None
                    )
                  } yield response
                case _ =>
                  ragService.queryWithAnswer(body.content, None)
              }).attempt

              endTime <- IO.realTimeInstant
              totalLatencyMs = (endTime.toEpochMilli - startTime.toEpochMilli).toInt

              resp <- queryResult match {
                case Right(queryResponse) =>
                  for {
                    // Log the query
                    queryLogId <- queryLogRegistry.logQuery(
                      queryText = body.content,
                      collectionPattern = Some(session.collectionPattern),
                      userId = userId,
                      embeddingLatencyMs = None,
                      searchLatencyMs = None,
                      llmLatencyMs = None,
                      totalLatencyMs = totalLatencyMs,
                      chunksRetrieved = queryResponse.contexts.size,
                      chunksUsed = queryResponse.contexts.size,
                      answerTokens = queryResponse.usage.map(_.completionTokens)
                    )

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
                    "LLM client not configured. Set OPENAI_API_KEY or ANTHROPIC_API_KEY."
                  } else {
                    Option(e.getMessage).getOrElse("Query failed")
                  }

                  for {
                    // Add error message
                    errorAssistant <- chatRegistry.addMessage(
                      sessionId = sessionId,
                      role = "assistant",
                      content = s"Sorry, I encountered an error: $errorMessage"
                    )

                    result <- Ok(Json.obj(
                      "userMessage" -> userMessage.asJson,
                      "assistantMessage" -> errorAssistant.asJson,
                      "error" -> Json.fromString(errorMessage)
                    ))
                  } yield result
              }
            } yield resp
        }
      } yield response

    // POST /api/v1/chat/sessions/:id/messages/stream - Send message with SSE streaming
    case req @ POST -> Root / "api" / "v1" / "chat" / "sessions" / sessionId / "messages" / "stream" =>
      for {
        sessionOpt <- chatRegistry.getSession(sessionId)
        response <- sessionOpt match {
          case None =>
            NotFound(ErrorResponse.notFound(s"Session '$sessionId' not found").asJson)
          case Some(session) =>
            for {
              body <- req.as[SendChatMessageRequest]

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
              assistantMessageId = s"msg-${UUID.randomUUID().toString.take(8)}"

              userId = req.headers.get(org.typelevel.ci.CIString("X-User-Id"))
                .map(_.head.value)

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
                        auth <- UserContextMiddleware.extractAuthWithAdmin(req, principals, allowAdminHeader)
                        response <- ragService.queryWithPermissionsAndAnswer(
                          question = body.content,
                          auth = auth,
                          collectionPattern = session.collectionPattern,
                          topK = None
                        )
                      } yield response
                    case _ =>
                      ragService.queryWithAnswer(body.content, None)
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
                        logId <- queryLogRegistry.logQuery(
                          queryText = body.content,
                          collectionPattern = Some(session.collectionPattern),
                          userId = userId,
                          embeddingLatencyMs = None,
                          searchLatencyMs = None,
                          llmLatencyMs = None,
                          totalLatencyMs = totalLatencyMs,
                          chunksRetrieved = queryResponse.contexts.size,
                          chunksUsed = queryResponse.contexts.size,
                          answerTokens = queryResponse.usage.map(_.completionTokens)
                        )

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
                      "LLM client not configured. Set OPENAI_API_KEY or ANTHROPIC_API_KEY."
                    } else {
                      Option(e.getMessage).getOrElse("Unknown error")
                    }

                    // Save error message
                    val saveError = Stream.eval {
                      chatRegistry.addMessage(
                        sessionId = sessionId,
                        role = "assistant",
                        content = s"Sorry, I encountered an error: $errorMessage"
                      )
                    }.drain

                    Stream.emit(formatSSE("error", QueryErrorEvent("query_failed", errorMessage).asJson.noSpaces)) ++ saveError
                }

                Stream.emit(startEvent) ++ queryStream
              }

              resp <- Ok(sseStream).map(_.withContentType(`Content-Type`(MediaType.`text/event-stream`)))
            } yield resp
        }
      } yield response

    // PUT /api/v1/chat/messages/:id/rate - Rate a message
    case req @ PUT -> Root / "api" / "v1" / "chat" / "messages" / messageId / "rate" =>
      for {
        body <- req.as[RateChatMessageRequest]
        // Validate rating
        response <- if (body.rating < 1 || body.rating > 5) {
          BadRequest(ErrorResponse.badRequest("Rating must be between 1 and 5").asJson)
        } else {
          for {
            // Get message to find queryLogId
            messageOpt <- chatRegistry.getMessage(messageId)
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
  }

  /**
   * Format a message as a Server-Sent Event.
   */
  private def formatSSE(event: String, data: String): String =
    s"event: $event\ndata: $data\n\n"
}

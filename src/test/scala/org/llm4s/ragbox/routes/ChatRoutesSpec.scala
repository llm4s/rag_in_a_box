package org.llm4s.ragbox.routes

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.llm4s.ragbox.auth.Principal
import org.llm4s.ragbox.config.DatabaseConfig
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.ChatSessionRegistry
import org.llm4s.ragbox.service.InvalidRequestError
import org.llm4s.ragbox.service.Llm4sPipeline.ChatTurn
import org.llm4s.ragbox.testkit.{InMemoryQueryLogRegistry, MockRAGService, PgVectorFixture, TestAuth, TestFixtures}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
 * The chat API (`ChatRoutes`): sessions and messages stored in a real database, answers from a
 * [[MockRAGService]]. Session ownership across users and input validation are covered by
 * `AuthorizationStackSpec`, and the history window by `ChatHistorySpec`.
 */
class ChatRoutesSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  private def run[A](io: IO[A]): A = io.timeout(60.seconds).unsafeRunSync()

  private val answer = QueryResponse(
    answer = "Zebras are striped.",
    contexts = Seq(
      ContextItem("Zebras have stripes.", 0.9, Map.empty, Some("zebra"), Some(0)),
      ContextItem("Stripes confuse flies.", 0.7, Map.empty, Some("flies"), Some(2))
    ),
    usage = Some(UsageInfo(10, 5, 15))
  )

  private final case class Chat(registry: ChatSessionRegistry, rag: MockRAGService, logs: InMemoryQueryLogRegistry, historyMessages: Int) {
    private val config = TestFixtures.testAppConfig.copy(rag = TestFixtures.testAppConfig.rag.copy(
      chat = TestFixtures.testAppConfig.rag.chat.copy(historyMessages = historyMessages)
    ))

    def app(principal: Principal): HttpApp[IO] =
      TestAuth.as(principal)(ChatRoutes.routes(registry, rag, logs, Some(config))).orNotFound

    def call(method: Method, path: String, body: Option[Json] = None, as: Principal = TestAuth.Admin): IO[(Status, Json)] =
      raw(method, path, body, as).flatMap(r => r.as[Json].map(r.status -> _))

    def raw(method: Method, path: String, body: Option[Json] = None, as: Principal = TestAuth.Admin): IO[Response[IO]] = {
      val req = Request[IO](method, Uri.unsafeFromString(path))
      app(as).run(body.fold(req)(req.withEntity(_)))
    }

    def newSession(title: Option[String] = None, pattern: Option[String] = None, as: Principal = TestAuth.Admin): IO[String] =
      call(Method.POST, "/api/v1/chat/sessions", Some(CreateChatSessionRequest(title, pattern).asJson), as)
        .map(_._2.hcursor.downField("session").get[String]("id").toOption.get)

    def send(sessionId: String, content: String, as: Principal = TestAuth.Admin): IO[(Status, Json)] =
      call(Method.POST, s"/api/v1/chat/sessions/$sessionId/messages", Some(Json.obj("content" -> content.asJson)), as)
  }

  private def chat(db: DatabaseConfig, historyMessages: Int = 10): Resource[IO, Chat] =
    pool(db).evalMap(ds => ChatSessionRegistry(ds)).map { registry =>
      Chat(registry, new MockRAGService().setQueryResponse(answer), InMemoryQueryLogRegistry(), historyMessages)
    }

  // ------------------------------------------------------------ sessions

  "POST /api/v1/chat/sessions" should "create a session owned by the caller" in withContainers { pg =>
    val (status, body, stored) = run(chat(freshDatabase(pg)).use { c =>
      for {
        created <- c.call(Method.POST, "/api/v1/chat/sessions", Some(Json.obj("title" -> "t".asJson, "collectionPattern" -> "docs/*".asJson)), TestAuth.user("ann"))
        stored <- c.registry.getSession(created._2.hcursor.downField("session").get[String]("id").toOption.get)
      } yield (created._1, created._2, stored)
    })
    status shouldBe Status.Created
    body.hcursor.get[String]("message") shouldBe Right("Session created")
    stored.map(s => (s.title, s.collectionPattern, s.ownerId, s.messageCount)) shouldBe Some((Some("t"), "docs/*", Some("user:ann"), 0))
  }

  it should "require the query scope of a token, and the session's collections to be in its reach" in withContainers { pg =>
    val (noScope, outside, inside) = run(chat(freshDatabase(pg)).use { c =>
      val body = (p: String) => Some(Json.obj("collectionPattern" -> p.asJson))
      for {
        noScope <- c.call(Method.POST, "/api/v1/chat/sessions", body("docs"), TestAuth.token(Set("ingest")))
        outside <- c.call(Method.POST, "/api/v1/chat/sessions", body("*"), TestAuth.token(Set("query"), Some(Set("docs"))))
        inside <- c.call(Method.POST, "/api/v1/chat/sessions", body("docs"), TestAuth.token(Set("query"), Some(Set("docs"))))
      } yield (noScope._1, outside._1, inside._1)
    })
    (noScope, outside, inside) shouldBe (Status.Forbidden, Status.Forbidden, Status.Created)
  }

  "GET /api/v1/chat/sessions" should "page sessions with a total, and leave archived ones out unless asked" in withContainers { pg =>
    val (page, total, active, withArchived, invalid) = run(chat(freshDatabase(pg)).use { c =>
      def ids(j: Json) = j.hcursor.downField("sessions").values.toList.flatten.flatMap(_.hcursor.get[String]("id").toOption)
      for {
        a <- c.newSession(Some("a"))
        b <- c.newSession(Some("b"))
        archived <- c.newSession(Some("old"))
        _ <- c.call(Method.PUT, s"/api/v1/chat/sessions/$archived", Some(Json.obj("isArchived" -> true.asJson)))
        page <- c.call(Method.GET, "/api/v1/chat/sessions?limit=1")
        active <- c.call(Method.GET, "/api/v1/chat/sessions")
        withArchived <- c.call(Method.GET, "/api/v1/chat/sessions?includeArchived=true")
        invalid <- c.call(Method.GET, "/api/v1/chat/sessions?includeArchived=maybe")
      } yield (ids(page._2).size, page._2.hcursor.get[Int]("total"), ids(active._2).toSet == Set(a, b), withArchived._2.hcursor.get[Int]("total"), invalid._1)
    })
    page shouldBe 1
    total shouldBe Right(2)
    active shouldBe true
    withArchived shouldBe Right(3)
    invalid shouldBe Status.BadRequest
  }

  "PUT and DELETE /api/v1/chat/sessions/{id}" should "update and delete a session, and 404 for an unknown one" in withContainers { pg =>
    val results = run(chat(freshDatabase(pg)).use { c =>
      for {
        id <- c.newSession(Some("before"))
        updated <- c.call(Method.PUT, s"/api/v1/chat/sessions/$id", Some(Json.obj("title" -> "after".asJson, "collectionPattern" -> "docs".asJson)))
        unknownPut <- c.call(Method.PUT, "/api/v1/chat/sessions/chat-missing", Some(Json.obj("title" -> "x".asJson)))
        deleted <- c.call(Method.DELETE, s"/api/v1/chat/sessions/$id")
        again <- c.call(Method.DELETE, s"/api/v1/chat/sessions/$id")
        get <- c.call(Method.GET, s"/api/v1/chat/sessions/$id")
      } yield (updated, unknownPut._1, deleted, again._1, get._1)
    })
    val (updated, unknownPut, deleted, again, get) = results
    updated._1 shouldBe Status.Ok
    (updated._2.hcursor.get[String]("title"), updated._2.hcursor.get[String]("collectionPattern")) shouldBe (Right("after"), Right("docs"))
    unknownPut shouldBe Status.NotFound
    deleted._1 shouldBe Status.Ok
    deleted._2.hcursor.get[String]("message") shouldBe Right("Session deleted")
    (again, get) shouldBe (Status.NotFound, Status.NotFound)
  }

  it should "not let a token move a session outside its collections" in withContainers { pg =>
    val token = TestAuth.token(Set("query"), Some(Set("docs")))
    val (status, stored) = run(chat(freshDatabase(pg)).use { c =>
      for {
        id <- c.newSession(pattern = Some("docs"), as = token)
        moved <- c.call(Method.PUT, s"/api/v1/chat/sessions/$id", Some(Json.obj("collectionPattern" -> "*".asJson)), token)
        stored <- c.registry.getSession(id)
      } yield (moved._1, stored.map(_.collectionPattern))
    })
    status shouldBe Status.Forbidden
    stored shouldBe Some("docs")
  }

  // ------------------------------------------------------------ messages

  "POST /api/v1/chat/sessions/{id}/messages" should "store the question and the answer, title the session, and log the query" in withContainers { pg =>
    val (status, body, detail, logged) = run(chat(freshDatabase(pg)).use { c =>
      for {
        id <- c.newSession(pattern = Some("docs"))
        sent <- c.send(id, "Why do zebras have stripes?", TestAuth.Admin)
        detail <- c.call(Method.GET, s"/api/v1/chat/sessions/$id")
        logged <- c.logs.list(None, None, None)
      } yield (sent._1, sent._2, detail._2, logged._1)
    })
    status shouldBe Status.Ok
    body.hcursor.downField("userMessage").get[String]("content") shouldBe Right("Why do zebras have stripes?")
    val assistant = body.hcursor.downField("assistantMessage")
    assistant.get[String]("role") shouldBe Right("assistant")
    assistant.get[String]("content") shouldBe Right("Zebras are striped.")
    assistant.downField("contexts").values.map(_.size) shouldBe Some(2)
    assistant.downField("usage").get[Int]("totalTokens") shouldBe Right(15)
    body.hcursor.get[String]("error").isLeft shouldBe true

    detail.hcursor.downField("session").get[String]("title") shouldBe Right("Why do zebras have stripes?")
    detail.hcursor.downField("session").get[Int]("messageCount") shouldBe Right(2)
    detail.hcursor.downField("messages").values.toList.flatten.map(_.hcursor.get[String]("role").toOption.get) shouldBe Seq("user", "assistant")

    logged.map(e => (e.queryText, e.collectionPattern, e.chunksRetrieved)) shouldBe Seq(("Why do zebras have stripes?", Some("docs"), 2))
    assistant.get[String]("queryLogId") shouldBe Right(logged.head.id)
  }

  it should "send the earlier answered exchanges as history, bounded by the configured window" in withContainers { pg =>
    val (calls, title) = run(chat(freshDatabase(pg), historyMessages = 2).use { c =>
      for {
        id <- c.newSession()
        _ <- c.send(id, "first question")
        _ <- c.send(id, "second question")
        _ <- c.send(id, "third question")
        session <- c.registry.getSession(id)
      } yield (c.rag.getChatCalls, session.flatMap(_.title))
    })
    calls.map(_._1) shouldBe Seq("first question", "second question", "third question")
    calls(0)._2 shouldBe empty
    calls(1)._2 shouldBe Seq(ChatTurn("user", "first question"), ChatTurn("assistant", "Zebras are striped."))
    // only the latest two messages: the second exchange
    calls(2)._2 shouldBe Seq(ChatTurn("user", "second question"), ChatTurn("assistant", "Zebras are striped."))
    // the title is the first message, and is not replaced
    title shouldBe Some("first question")
  }

  it should "send no history when the window is 0" in withContainers { pg =>
    val calls = run(chat(freshDatabase(pg), historyMessages = 0).use { c =>
      c.newSession().flatMap(id => c.send(id, "one") *> c.send(id, "two")) *> IO(c.rag.getChatCalls)
    })
    calls.map(_._2) shouldBe Seq(Nil, Nil)
  }

  it should "store a failed answer as an error reply, not log it, and leave it out of the next history" in withContainers { pg =>
    val (failed, hidden, next, logCount, nextHistory) = run(chat(freshDatabase(pg)).use { c =>
      for {
        id <- c.newSession()
        _ <- IO(c.rag.setError(InvalidRequestError("Unknown fusion strategy")))
        failed <- c.send(id, "bad")
        _ <- IO(c.rag.setError(new RuntimeException("connection string with a password")))
        hidden <- c.send(id, "worse")
        _ <- IO(c.rag.clearError())
        next <- c.send(id, "good")
      } yield (failed, hidden, next, c.logs.logCount, c.rag.getChatCalls.last._2)
    })
    nextHistory shouldBe empty
    failed._1 shouldBe Status.Ok
    failed._2.hcursor.get[String]("error") shouldBe Right("Unknown fusion strategy")
    failed._2.hcursor.downField("assistantMessage").get[String]("content") shouldBe
      Right(s"${ChatRoutes.ErrorReplyPrefix} Unknown fusion strategy")
    // an unexpected error's message is not shown to the client
    hidden._2.hcursor.get[String]("error") shouldBe Right("Query failed")
    next._2.hcursor.get[String]("error").isLeft shouldBe true
    logCount shouldBe 1
  }

  it should "answer 404 for an unknown session without asking the RAG service" in withContainers { pg =>
    val (status, calls) = run(chat(freshDatabase(pg)).use(c => c.send("chat-missing", "hello").map(r => (r._1, c.rag.getChatCalls))))
    status shouldBe Status.NotFound
    calls shouldBe empty
  }

  it should "refuse a token whose collections no longer cover the session" in withContainers { pg =>
    val token = TestAuth.token(Set("query"), Some(Set("docs")))
    val (status, calls) = run(chat(freshDatabase(pg)).use { c =>
      for {
        id <- c.newSession(pattern = Some("docs"), as = token)
        // an administrator widens the token's session
        _ <- c.call(Method.PUT, s"/api/v1/chat/sessions/$id", Some(Json.obj("collectionPattern" -> "*".asJson)))
        sent <- c.send(id, "hello", token)
        streamed <- c.raw(Method.POST, s"/api/v1/chat/sessions/$id/messages/stream", Some(Json.obj("content" -> "hello".asJson)), token)
      } yield ((sent._1, streamed.status), c.rag.getChatCalls)
    })
    status shouldBe (Status.Forbidden, Status.Forbidden)
    calls shouldBe empty
  }

  // ------------------------------------------------------------ streaming

  private def events(body: String): Seq[(String, Json)] =
    body.split("\n\n").toSeq.filter(_.nonEmpty).map { block =>
      val lines = block.split("\n")
      val event = lines.find(_.startsWith("event: ")).get.stripPrefix("event: ")
      val data = io.circe.parser.parse(lines.find(_.startsWith("data: ")).get.stripPrefix("data: ")).toOption.get
      event -> data
    }

  "POST /api/v1/chat/sessions/{id}/messages/stream" should "stream start, contexts, answer, usage and complete, then store the answer" in withContainers { pg =>
    val (response, body, messages, logCount) = run(chat(freshDatabase(pg)).use { c =>
      for {
        id <- c.newSession()
        response <- c.raw(Method.POST, s"/api/v1/chat/sessions/$id/messages/stream", Some(Json.obj("content" -> "stripes?".asJson)))
        body <- response.bodyText.compile.string
        messages <- c.registry.getMessages(id)
      } yield (response, body, messages, c.logs.logCount)
    })
    response.status shouldBe Status.Ok
    response.contentType.map(_.mediaType) shouldBe Some(MediaType.`text/event-stream`)
    val evs = events(body)
    evs.map(_._1) shouldBe Seq("start", "context", "context", "answer", "usage", "complete")
    evs.head._2.hcursor.get[String]("userMessageId") shouldBe Right(messages.head.id)
    val queryId = evs.head._2.hcursor.get[String]("queryId")
    evs.last._2.hcursor.get[String]("queryId") shouldBe queryId
    evs(1)._2.hcursor.get[Int]("index") shouldBe Right(0)
    evs(2)._2.hcursor.get[Int]("index") shouldBe Right(1)
    evs(3)._2.hcursor.get[String]("answer") shouldBe Right("Zebras are striped.")

    messages.map(m => (m.role, m.content)) shouldBe Seq("user" -> "stripes?", "assistant" -> "Zebras are striped.")
    messages(1).queryLogId should not be empty
    logCount shouldBe 1
  }

  it should "send no context or usage events when there are none" in withContainers { pg =>
    val names = run(chat(freshDatabase(pg)).use { c =>
      for {
        id <- c.newSession()
        _ <- IO(c.rag.setQueryResponse(QueryResponse("No idea.", Nil, None)))
        response <- c.raw(Method.POST, s"/api/v1/chat/sessions/$id/messages/stream", Some(Json.obj("content" -> "q".asJson)))
        body <- response.bodyText.compile.string
      } yield events(body).map(_._1)
    })
    names shouldBe Seq("start", "answer", "complete")
  }

  it should "stream an error event and store the error reply when answering fails" in withContainers { pg =>
    val (events_, messages) = run(chat(freshDatabase(pg)).use { c =>
      for {
        id <- c.newSession()
        _ <- IO(c.rag.setError(InvalidRequestError("No such reranker")))
        response <- c.raw(Method.POST, s"/api/v1/chat/sessions/$id/messages/stream", Some(Json.obj("content" -> "q".asJson)))
        body <- response.bodyText.compile.string
        messages <- c.registry.getMessages(id)
      } yield (events(body), messages)
    })
    events_.map(_._1) shouldBe Seq("start", "error")
    events_(1)._2.hcursor.get[String]("message") shouldBe Right("No such reranker")
    messages.map(_.content) shouldBe Seq("q", s"${ChatRoutes.ErrorReplyPrefix} No such reranker")
  }

  it should "answer 404 for an unknown session and 400 for an empty message" in withContainers { pg =>
    val (unknown, empty) = run(chat(freshDatabase(pg)).use { c =>
      for {
        unknown <- c.raw(Method.POST, "/api/v1/chat/sessions/chat-missing/messages/stream", Some(Json.obj("content" -> "q".asJson)))
        id <- c.newSession()
        empty <- c.raw(Method.POST, s"/api/v1/chat/sessions/$id/messages/stream", Some(Json.obj("content" -> " ".asJson)))
      } yield (unknown.status, empty.status)
    })
    (unknown, empty) shouldBe (Status.NotFound, Status.BadRequest)
  }

  // ------------------------------------------------------------ ratings

  "PUT /api/v1/chat/messages/{id}/rate" should "rate the message and its logged query" in withContainers { pg =>
    val (rated, invalid, unknown, stored, feedback) = run(chat(freshDatabase(pg)).use { c =>
      for {
        id <- c.newSession()
        sent <- c.send(id, "q")
        assistant = sent._2.hcursor.downField("assistantMessage")
        messageId = assistant.get[String]("id").toOption.get
        logId = assistant.get[String]("queryLogId").toOption.get
        rated <- c.call(Method.PUT, s"/api/v1/chat/messages/$messageId/rate", Some(Json.obj("rating" -> 4.asJson)))
        invalid <- c.call(Method.PUT, s"/api/v1/chat/messages/$messageId/rate", Some(Json.obj("rating" -> 6.asJson)))
        unknown <- c.call(Method.PUT, "/api/v1/chat/messages/msg-missing/rate", Some(Json.obj("rating" -> 3.asJson)))
        stored <- c.registry.getMessage(messageId)
      } yield (rated, invalid._1, unknown._1, stored.flatMap(_.rating), c.logs.getFeedback(logId).map(_._1))
    })
    rated._1 shouldBe Status.Ok
    rated._2.hcursor.get[String]("message") shouldBe Right("Rating saved")
    invalid shouldBe Status.BadRequest
    unknown shouldBe Status.NotFound
    stored shouldBe Some(4)
    feedback shouldBe Some(4)
  }

  it should "not let another user rate a message in a session they cannot see" in withContainers { pg =>
    val (status, stored) = run(chat(freshDatabase(pg)).use { c =>
      for {
        id <- c.newSession(as = TestAuth.user("ann"))
        sent <- c.send(id, "q", TestAuth.user("ann"))
        messageId = sent._2.hcursor.downField("userMessage").get[String]("id").toOption.get
        rated <- c.call(Method.PUT, s"/api/v1/chat/messages/$messageId/rate", Some(Json.obj("rating" -> 1.asJson)), TestAuth.user("bob"))
        stored <- c.registry.getMessage(messageId)
      } yield (rated._1, stored.flatMap(_.rating))
    })
    status shouldBe Status.NotFound
    stored shouldBe None
  }
}

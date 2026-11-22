package org.llm4s.ragbox.integration

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.llm4s.llmconnect.model.{AssistantMessage, SystemMessage, UserMessage}
import org.llm4s.ragbox.config.{AppConfig, DatabaseConfig}
import org.llm4s.ragbox.model.{QueryMetrics, QueryResponse}
import org.llm4s.ragbox.registry.{QueryLogRegistry, QueryLogging}
import org.llm4s.ragbox.service.Llm4sPipeline.ChatTurn
import org.llm4s.ragbox.service.SuggestionService
import org.llm4s.ragbox.service.RAGService
import org.llm4s.ragbox.testkit.{FakeEmbeddings, FakeLLM, PgVectorFixture}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.util.Using

/**
 * The llm4s pipeline as ragbox wires it, against a real pgvector database (issue #21): one
 * chunking for index and chunk store, PostgreSQL keyword search, tracing-based query metrics,
 * chat history, reranking and query rewriting through the answer LLM.
 */
class Llm4sPipelineIntegrationSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  private def run[A](io: IO[A]): A = io.timeout(120.seconds).unsafeRunSync()

  /** A document long enough to make several chunks at the test chunk size (500). */
  private val longDocument: String =
    (1 to 40).map(i => s"Sentence number $i describes subject $i with a little detail.").mkString(" ")

  private def keywordOnly(c: AppConfig): AppConfig =
    c.copy(rag = c.rag.copy(search = c.rag.search.copy(fusionStrategy = "keyword_only")))

  /** (id, content) of the vectors llm4s stored whose id starts with `prefix`, in chunk order. */
  private def indexedChunks(db: DatabaseConfig, prefix: String): Seq[(String, String)] =
    run(connect(db).use { conn =>
      IO.blocking {
        Using.resource(conn.prepareStatement(
          s"SELECT id, content FROM ${db.tableName} WHERE id LIKE ? ORDER BY (metadata->>'chunkIndex')::int"
        )) { stmt =>
          stmt.setString(1, prefix + "%")
          val rs = stmt.executeQuery()
          Iterator.continually(rs).takeWhile(_.next()).map(r => r.getString("id") -> r.getString("content")).toList
        }
      }
    })

  "Ingesting a document" should "keep exactly the indexed chunks in the chunk store, under llm4s's ids" in withContainers { pg =>
    val db = freshDatabase(pg)
    val chunks = run(plainService(db).use { service =>
      service.ingestDocument(longDocument, "doc-a", Map("filename" -> "a.txt")) *> service.getDocumentChunks("doc-a")
    }).getOrElse(fail("no chunks stored")).chunks

    val indexed = indexedChunks(db, "doc-a-chunk-")
    chunks.size should be > 1
    chunks.map(c => c.id -> c.content) shouldBe indexed
    chunks.map(_.id) shouldBe chunks.indices.map(i => s"doc-a-chunk-$i")
    // No collection is recorded as no collection (NULL), not ""
    chunks.foreach(_.metadata.get("collection") shouldBe None)
  }

  it should "write the chunk store in permission mode too, under the collection-qualified ids" in withContainers { pg =>
    val db = freshDatabase(pg)
    val chunks = run(permissionService(db).use { service =>
      service.ingestWithPermissions(RAGService.DefaultCollection, "doc-p", longDocument) *>
        service.getDocumentChunks("doc-p").map(_.map(_.chunks).getOrElse(Nil))
    })

    val indexed = indexedChunks(db, "coll-")
    chunks.size should be > 1
    chunks.map(c => c.id -> c.content) shouldBe indexed
    chunks.head.id should fullyMatch regex "coll-\\d+-doc-p-chunk-0"
    // Default-collection chunks are stored with a NULL collection, which the default-collection
    // statistics count
    val nullCollection = run(connect(db).use { conn =>
      IO.blocking(Using.resource(conn.createStatement()) { stmt =>
        val rs = stmt.executeQuery("SELECT COUNT(*) FROM chunk_registry WHERE collection IS NULL")
        rs.next()
        rs.getInt(1)
      })
    })
    nullCollection shouldBe chunks.size
  }

  "Keyword search" should "survive a restart, since BM25 is kept in PostgreSQL" in withContainers { pg =>
    val db = freshDatabase(pg)
    val embeddings = FakeEmbeddings()
    run(plainService(db, embeddings, configure = keywordOnly).use { service =>
      service.ingestDocument("Zebras have black and white stripes.", "zebra", Map.empty) *>
        service.ingestDocument("Penguins live in the southern hemisphere.", "penguin", Map.empty)
    })

    val results = run(plainService(db, embeddings, configure = keywordOnly).use(_.search("stripes", Some(5))))
    results.results.flatMap(_.documentId) shouldBe Seq("zebra")
  }

  "An answered query" should "carry the latencies, tokens and cost llm4s traced" in withContainers { pg =>
    val db = freshDatabase(pg)
    val llm = FakeLLM()
    val response = run(plainService(db, llm = Some(llm)).use { service =>
      service.ingestDocument(longDocument, "doc-m", Map.empty) *> service.queryWithAnswer("subject 7 detail", Some(3))
    })

    response.answer shouldBe FakeLLM.Answer
    val metrics = response.metrics.getOrElse(fail("no metrics"))
    metrics.embeddingLatencyMs shouldBe defined
    metrics.searchLatencyMs shouldBe defined
    metrics.llmLatencyMs shouldBe defined
    metrics.embeddingTokens.exists(_ > 0) shouldBe true
    metrics.promptTokens shouldBe Some(100)
    metrics.completionTokens shouldBe Some(10)
    metrics.chunksRetrieved shouldBe response.contexts.size
  }

  "A chat answer" should "send the conversation history and llm.temperature to the LLM" in withContainers { pg =>
    val db = freshDatabase(pg)
    val llm = FakeLLM()
    val history = Seq(ChatTurn("user", "What do zebras look like?"), ChatTurn("assistant", "They are striped."))
    val (response, temperature) = run(plainService(db, llm = Some(llm)).use { service =>
      for {
        _ <- service.ingestDocument("Zebras have black and white stripes.", "zebra", Map.empty)
        answer <- service.chatAnswer("Why are they striped?", history, permissions = None, topK = Some(2))
      } yield (answer, testConfig(db, Some(llm)).llm.temperature)
    })

    response.answer shouldBe FakeLLM.Answer
    val (conversation, options) = llm.requests.last
    conversation.messages.map(_.getClass) shouldBe Seq(
      classOf[SystemMessage], classOf[UserMessage], classOf[AssistantMessage], classOf[UserMessage]
    )
    conversation.messages(1).content shouldBe "What do zebras look like?"
    conversation.messages(2).content shouldBe "They are striped."
    conversation.messages.last.content should include("Question: Why are they striped?")
    conversation.messages.last.content should include("[1] Zebras have black and white stripes.")
    options.temperature shouldBe temperature
  }

  it should "work in permission mode, filtering retrieval by the user's access" in withContainers { pg =>
    val db = freshDatabase(pg)
    val llm = FakeLLM()
    val response = run(permissionService(db, llm = Some(llm)).use { service =>
      for {
        _ <- service.ingestWithPermissions(RAGService.DefaultCollection, "zebra", "Zebras have black and white stripes.")
        answer <- service.chatAnswer(
          "zebras stripes",
          Seq.empty,
          permissions = Some(org.llm4s.rag.permissions.UserAuthorization.Anonymous -> "*"),
          topK = Some(2)
        )
      } yield answer
    })
    response.contexts.flatMap(_.documentId) shouldBe Seq("zebra")
  }

  "Reranking and query rewriting" should "run through the answer LLM when configured" in withContainers { pg =>
    val db = freshDatabase(pg)
    val llm = FakeLLM()
    val configure: AppConfig => AppConfig = c =>
      c.copy(rag = c.rag.copy(
        reranking = c.rag.reranking.copy(strategy = "llm", candidates = 10),
        queryTransform = c.rag.queryTransform.copy(strategy = "llm-rewrite")
      ))
    val response = run(plainService(db, llm = Some(llm), configure = configure).use { service =>
      service.ingestDocument("Zebras have black and white stripes.", "zebra", Map.empty) *>
        service.ingestDocument("Penguins live in the southern hemisphere.", "penguin", Map.empty) *>
        service.queryWithAnswer("zebra stripes", Some(2))
    })

    val prompts = llm.requests.map { case (conversation, _) => conversation.messages.map(_.content).mkString("\n") }
    prompts.head should include("query rewriting")
    prompts.exists(_.contains("Respond with ONLY a JSON array of scores")) shouldBe true
    // The rerank prompt carries the rewritten query
    prompts.find(_.contains("JSON array of scores")).get should include("rewritten: zebra stripes")
    response.answer shouldBe FakeLLM.Answer
    // Rewrite, rerank and answer completions are all accounted for
    response.metrics.flatMap(_.promptTokens) shouldBe Some(100 * llm.requests.size)
  }

  "The config info" should "say that fusion does not apply in permission mode" in withContainers { pg =>
    val db = freshDatabase(pg)
    val (permission, plain) = run(
      permissionService(db).use(_.getConfigInfo).flatMap(p => plainService(freshDatabase(pg)).use(_.getConfigInfo).map(p -> _))
    )
    permission.rag.searchMode shouldBe "vector"
    permission.rag.fusionApplies shouldBe false
    permission.rag.limitations.exists(_.contains("llm4s/llm4s#2716")) shouldBe true
    plain.rag.searchMode shouldBe "hybrid"
    plain.rag.fusionApplies shouldBe true
  }

  "The provider list" should "come from the llm4s provider registry" in withContainers { pg =>
    val db = freshDatabase(pg)
    val providers = run(plainService(db, llm = Some(FakeLLM())).use(_.getProviders))
    providers.llmProviders.map(_.name) shouldBe Seq("fake")
    providers.llmProviders.head.configured shouldBe true
    providers.embeddingProviders.map(_.name) shouldBe Seq("openai")
    providers.embeddingProviders.head.models shouldBe Seq(FakeEmbeddings.Model)
    providers.chunkingStrategies should contain allOf ("simple", "sentence", "markdown", "semantic")
  }

  "The query log" should "record traced tokens and cost, and judge latency without answer generation" in withContainers { pg =>
    val db = freshDatabase(pg)
    // Slow answers (3 s of LLM time) but fast retrieval: the latency suggestion must not fire
    def answered(cost: Double): QueryResponse =
      QueryResponse("a", Seq.empty, None, Some(QueryMetrics(
        embeddingLatencyMs = Some(20), searchLatencyMs = Some(30), llmLatencyMs = Some(3000), chunksRetrieved = 5,
        chunksUsed = 5, embeddingTokens = Some(4), promptTokens = Some(900), completionTokens = Some(50), costUsd = Some(cost)
      )))
    val (entry, summary, suggestions) = run(pool(db).use { ds =>
      for {
        registry <- QueryLogRegistry(ds)
        id <- QueryLogging.logAnswer(registry, "q1", None, None, totalLatencyMs = 3050, answered(0.01))
        _ <- QueryLogging.logAnswer(registry, "q2", None, None, totalLatencyMs = 3060, answered(0.02))
        entry <- registry.get(id)
        now = java.time.Instant.now()
        summary <- registry.getSummary(now.minusSeconds(3600), now.plusSeconds(60))
        suggestions <- SuggestionService(registry, testConfig(db)).generateSuggestions()
      } yield (entry, summary, suggestions)
    })

    val logged = entry.getOrElse(fail("query not logged"))
    logged.llmLatencyMs shouldBe Some(3000)
    logged.promptTokens shouldBe Some(900)
    logged.embeddingTokens shouldBe Some(4)
    logged.costUsd shouldBe Some(0.01)
    summary.p95LatencyMs should be > 3000
    summary.p95RetrievalLatencyMs.exists(_ <= 60) shouldBe true
    summary.totalCostUsd.map(c => math.round(c * 100)) shouldBe Some(3L)
    suggestions.suggestions.map(_.title) should not contain "High Retrieval Latency"
  }

  "Without an LLM" should "still search, and fail answers with a configuration error" in withContainers { pg =>
    val db = freshDatabase(pg)
    val (search, answer) = run(plainService(db).use { service =>
      for {
        _ <- service.ingestDocument("Zebras have black and white stripes.", "zebra", Map.empty)
        found <- service.search("zebras", Some(1))
        answered <- service.queryWithAnswer("zebras", Some(1)).attempt
      } yield (found, answered)
    })
    search.results.flatMap(_.documentId) shouldBe Seq("zebra")
    answer.left.map(_.getMessage).left.getOrElse("") should include("LLM client required")
  }
}

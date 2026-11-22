package org.llm4s.ragbox.integration

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.zaxxer.hikari.HikariDataSource
import org.llm4s.ragbox.config.{DatabaseConfig, RuntimeConfigManager}
import org.llm4s.ragbox.db.{ConnectionPool, Migrations}
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry._
import org.llm4s.ragbox.service.{ExperimentService, RAGService}
import org.llm4s.ragbox.store.PgChunkStore
import org.llm4s.ragbox.testkit.{FakeEmbeddings, PgVectorFixture, TestFixtures}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration._
import scala.util.Using

/**
 * Persistence layer against a real PostgreSQL (issue #17): pooled connections that survive a
 * dropped backend, atomic writes, and no lost updates or deadlocks under concurrency.
 */
class PersistenceSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  private val UuidPattern = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

  private def run[A](io: IO[A]): A = io.timeout(60.seconds).unsafeRunSync()

  /** Terminate every other backend connected to `db`, as a Postgres restart or failover would. */
  private def terminateBackends(db: DatabaseConfig): Int =
    run(connect(db).use { conn =>
      IO.blocking {
        Using.resource(conn.prepareStatement(
          """SELECT COUNT(pg_terminate_backend(pid)) FROM pg_stat_activity
            |WHERE datname = ? AND pid <> pg_backend_pid()""".stripMargin
        )) { stmt =>
          stmt.setString(1, db.database)
          val rs = stmt.executeQuery()
          rs.next()
          rs.getInt(1)
        }
      }
    })

  private def query[A](db: DatabaseConfig, sql: String)(read: java.sql.ResultSet => A): A =
    run(connect(db).use(conn => IO.blocking(Using.resource(conn.createStatement())(s => read(s.executeQuery(sql))))))

  private def execute(db: DatabaseConfig, sql: String): Unit =
    run(connect(db).use(conn => IO.blocking(Using.resource(conn.createStatement())(_.execute(sql))).void))

  private def chunk(documentId: String, index: Int, content: String): ChunkInfo =
    ChunkInfo(
      id = s"$documentId-$index",
      documentId = documentId,
      index = index,
      content = content,
      contentLength = content.length,
      metadata = Map.empty,
      chunkMetadata = ChunkMetadataInfo(headings = Seq.empty, isCodeBlock = false, language = None, startOffset = None, endOffset = None)
    )

  private def entry(id: String): DocumentEntry =
    DocumentEntry(id, "hash", 1, Map.empty, None, Instant.now(), Instant.now())

  private def draft(name: String): CreateExperimentRequest =
    CreateExperimentRequest(name = name, variantConfig = ExperimentConfigSnapshot(topK = Some(3)))

  private def activeConnections(ds: HikariDataSource): Int = ds.getHikariPoolMXBean.getActiveConnections

  // ============================================================
  // Pooling and reconnects
  // ============================================================

  "Registries" should "keep working after their database connections are terminated" in withContainers { pg =>
    val db = freshDatabase(pg)
    run(pool(db).use { ds =>
      for {
        docs <- PgDocumentRegistry(ds)
        chats <- ChatSessionRegistry(ds)
        _ <- docs.put(entry("before"))
        session <- chats.createSession(CreateChatSessionRequest(title = Some("t")))
        killed <- IO.blocking(terminateBackends(db))
        // Hikari validates a connection that has been idle on borrow and replaces a dead one
        _ <- IO.sleep(1.second)
        found <- docs.get("before")
        _ <- docs.put(entry("after"))
        count <- docs.count()
        reread <- chats.getSession(session.id)
      } yield {
        killed should be > 0
        found.map(_.documentId) shouldBe Some("before")
        count shouldBe 2
        reread.map(_.id) shouldBe Some(session.id)
      }
    })
  }

  "ConnectionPool.withTransaction" should "roll back, re-raise the original error and return the connection" in withContainers { pg =>
    val db = freshDatabase(pg)
    run(pool(db, maxPoolSize = 1).use { ds =>
      val boom = new IllegalStateException("boom")
      for {
        _ <- ConnectionPool.withConnection(ds)(c => Using.resource(c.createStatement())(_.execute("CREATE TABLE t (v INT)")))
        failed <- ConnectionPool.withTransaction(ds) { c =>
          Using.resource(c.createStatement())(_.execute("INSERT INTO t VALUES (1)"))
          throw boom
        }.attempt
        // A one-connection pool: this only succeeds if the failed transaction released it
        rows <- ConnectionPool.withConnection(ds) { c =>
          Using.resource(c.createStatement()) { s =>
            val rs = s.executeQuery("SELECT COUNT(*) FROM t")
            rs.next()
            rs.getInt(1)
          }
        }
        autoCommit <- ConnectionPool.withConnection(ds)(_.getAutoCommit)
      } yield {
        failed shouldBe Left(boom)
        rows shouldBe 0
        autoCommit shouldBe true
        activeConnections(ds) shouldBe 0
      }
    })
  }

  // ============================================================
  // Atomic writes
  // ============================================================

  "PgChunkStore.store" should "leave the previous chunks in place when replacing them fails" in withContainers { pg =>
    val db = freshDatabase(pg)
    run(pool(db).use { ds =>
      for {
        store <- PgChunkStore(ds)
        _ <- store.store("doc", Seq(chunk("doc", 0, "first"), chunk("doc", 1, "second")))
        // Two chunks with the same index violate UNIQUE(document_id, chunk_index) mid-batch
        failed <- store.store("doc", Seq(chunk("doc", 0, "new"), chunk("doc", 0, "dup"))).attempt
        chunks <- store.getChunks("doc")
      } yield {
        failed.isLeft shouldBe true
        chunks.map(_.content) shouldBe Seq("first", "second")
      }
    })
  }

  it should "let concurrent re-ingests of the same document all succeed" in withContainers { pg =>
    val db = freshDatabase(pg)
    run(pool(db).use { ds =>
      for {
        store <- PgChunkStore(ds)
        results <- (1 to 12).toList.parTraverse { n =>
          store.store("doc", (0 until 5).map(i => chunk("doc", i, s"v$n-$i"))).attempt
        }
        chunks <- store.getChunks("doc")
      } yield {
        results.collect { case Left(e) => e } shouldBe empty
        chunks.map(_.index) shouldBe (0 until 5)
        // All five chunks come from one store, never a mix of two
        chunks.map(_.content.takeWhile(_ != '-')).distinct should have size 1
      }
    })
  }

  "ChatSessionRegistry.addMessage" should "give concurrent messages distinct, consecutive indexes" in withContainers { pg =>
    val db = freshDatabase(pg)
    run(pool(db).use { ds =>
      for {
        chats <- ChatSessionRegistry(ds)
        session <- chats.createSession(CreateChatSessionRequest())
        added <- (0 until 25).toList.parTraverse(i => chats.addMessage(session.id, "user", s"message $i"))
        messages <- chats.getMessages(session.id)
        reread <- chats.getSession(session.id)
        missing <- chats.addMessage("chat-does-not-exist", "user", "hello").attempt
      } yield {
        added.map(_.messageIndex).sorted shouldBe (0 until 25)
        messages.map(_.messageIndex) shouldBe (0 until 25)
        reread.map(_.messageCount) shouldBe Some(25)
        missing shouldBe Left(ChatSessionNotFoundError("chat-does-not-exist"))
        session.id should fullyMatch regex s"chat-$UuidPattern"
        added.foreach(_.id should fullyMatch regex s"msg-$UuidPattern")
      }
    })
  }

  it should "page messages from the latest, and sessions with a total" in withContainers { pg =>
    val db = freshDatabase(pg)
    run(pool(db).use { ds =>
      for {
        chats <- ChatSessionRegistry(ds)
        session <- chats.createSession(CreateChatSessionRequest())
        _ <- (0 until 10).toList.traverse_(i => chats.addMessage(session.id, "user", s"m$i"))
        latest <- chats.getMessages(session.id, limit = 3)
        earlier <- chats.getMessages(session.id, limit = 3, offset = 3)
        _ <- (0 until 4).toList.traverse_(_ => chats.createSession(CreateChatSessionRequest()))
        page <- chats.listSessions(limit = 2)
        total <- chats.countSessions()
      } yield {
        latest.map(_.content) shouldBe Seq("m7", "m8", "m9")
        earlier.map(_.content) shouldBe Seq("m4", "m5", "m6")
        page should have size 2
        total shouldBe 5
      }
    })
  }

  "The migrations" should "renumber duplicate message indexes left by older versions and add the constraint" in withContainers { pg =>
    val db = freshDatabase(pg, migrate = false)
    run(pool(db).use { ds =>
      for {
        // An older schema without the constraint, holding the duplicates the old race produced
        _ <- IO.blocking {
          execute(db, "CREATE TABLE chat_sessions (id VARCHAR(64) PRIMARY KEY, title VARCHAR(255), collection_pattern VARCHAR(255) DEFAULT '*', message_count INTEGER NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), is_archived BOOLEAN NOT NULL DEFAULT FALSE)")
          execute(db, "CREATE TABLE chat_messages (id VARCHAR(64) PRIMARY KEY, session_id VARCHAR(64) NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE, role VARCHAR(16) NOT NULL, content TEXT NOT NULL, contexts JSONB, usage_info JSONB, rating INTEGER, query_log_id VARCHAR(64), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), message_index INTEGER NOT NULL)")
          execute(db, "INSERT INTO chat_sessions (id, message_count) VALUES ('chat-1a2b3c4d', 3)")
          execute(db, "INSERT INTO chat_messages (id, session_id, role, content, message_index, created_at) VALUES ('msg-00000001', 'chat-1a2b3c4d', 'user', 'a', 0, NOW() - INTERVAL '3 seconds'), ('msg-00000002', 'chat-1a2b3c4d', 'assistant', 'b', 1, NOW() - INTERVAL '2 seconds'), ('msg-00000003', 'chat-1a2b3c4d', 'user', 'c', 1, NOW() - INTERVAL '1 second')")
        }
        _ <- Migrations.migrate(ds)
        chats <- ChatSessionRegistry(ds)
        // Old short ids are still found
        session <- chats.getSession("chat-1a2b3c4d")
        message <- chats.getMessage("msg-00000003")
        messages <- chats.getMessages("chat-1a2b3c4d")
        next <- chats.addMessage("chat-1a2b3c4d", "assistant", "d")
        duplicate <- IO.blocking(execute(db, "INSERT INTO chat_messages (id, session_id, role, content, message_index) VALUES ('msg-x', 'chat-1a2b3c4d', 'user', 'x', 0)")).attempt
      } yield {
        session.map(_.id) shouldBe Some("chat-1a2b3c4d")
        message.map(_.content) shouldBe Some("c")
        messages.map(m => m.content -> m.messageIndex) shouldBe Seq("a" -> 0, "b" -> 1, "c" -> 2)
        next.messageIndex shouldBe 3
        duplicate.left.toOption.exists(ConnectionPool.isUniqueViolation) shouldBe true
      }
    })
  }

  // ============================================================
  // Experiments
  // ============================================================

  "ExperimentService.startExperiment" should "let exactly one of several concurrent starts run" in withContainers { pg =>
    val db = freshDatabase(pg)
    run(pool(db).use { ds =>
      for {
        registry <- ExperimentRegistry(ds)
        service = ExperimentService(registry, ds)
        drafts <- (1 to 8).toList.traverse(i => registry.create(draft(s"exp $i")))
        // Bypass the service's up-front check so the database constraint is what decides
        results <- drafts.parTraverse(e => registry.start(e.id).attempt)
        running <- registry.list(Some(ExperimentStatus.Running))
        viaService <- drafts.filterNot(e => running.exists(_.id == e.id)).parTraverse(e => service.startExperiment(e.id).attempt)
      } yield {
        results.count(_.exists(_.isDefined)) shouldBe 1
        results.collect { case Left(e) => e }.foreach(_ shouldBe a[ExperimentAlreadyRunningError])
        results.count(_.isLeft) shouldBe 7
        running should have size 1
        // Once one runs, every further start is refused as a conflict
        viaService.foreach(_.left.toOption.get shouldBe a[ExperimentAlreadyRunningError])
        drafts.foreach(_.id should fullyMatch regex s"exp-$UuidPattern")
      }
    })
  }

  "ExperimentRegistry" should "not deadlock under many concurrent start/stop/archive calls on a tiny pool" in withContainers { pg =>
    val db = freshDatabase(pg)
    run(pool(db, maxPoolSize = 2).use { ds =>
      for {
        registry <- ExperimentRegistry(ds)
        chats <- ChatSessionRegistry(ds)
        session <- chats.createSession(CreateChatSessionRequest())
        drafts <- (1 to 10).toList.traverse(i => registry.create(draft(s"exp $i")))
        // Before #17 each of these held one pooled connection while waiting for a second
        _ <- (1 to 3).toList.traverse_ { _ =>
          (drafts.parTraverse_(e => registry.start(e.id).attempt *> registry.stop(e.id) *> registry.archive(e.id)),
            (1 to 10).toList.parTraverse_(i => chats.updateSession(session.id, UpdateChatSessionRequest(title = Some(s"t$i")))))
            .parTupled
        }.timeout(30.seconds)
        statuses <- drafts.traverse(e => registry.get(e.id).map(_.map(_.status)))
      } yield {
        statuses.flatten should have size 10
        activeConnections(ds) shouldBe 0
      }
    })
  }

  it should "keep only the latest running experiment when an older schema had several" in withContainers { pg =>
    val db = freshDatabase(pg)
    run(pool(db).use { ds =>
      for {
        first <- ExperimentRegistry(ds)
        a <- first.create(draft("a"))
        b <- first.create(draft("b"))
        _ <- IO.blocking {
          execute(db, "DROP INDEX uq_experiments_single_running")
          execute(db, s"UPDATE experiments SET status = 'running', started_at = NOW() - INTERVAL '1 hour' WHERE id = '${a.id}'")
          execute(db, s"UPDATE experiments SET status = 'running', started_at = NOW() WHERE id = '${b.id}'")
          // ...as a database created before Flyway, which the migrations adopt and upgrade
          execute(db, "DROP TABLE flyway_schema_history")
        }
        _ <- Migrations.migrate(ds)
        second <- ExperimentRegistry(ds)
        running <- second.getRunning()
        aNow <- second.get(a.id)
        total <- second.count()
        page <- second.list(limit = 1)
      } yield {
        running.map(_.id) shouldBe Some(b.id)
        aNow.map(_.status) shouldBe Some(ExperimentStatus.Completed)
        total shouldBe 2
        page should have size 1
      }
    })
  }

  // ============================================================
  // RAG instance lifecycle
  // ============================================================

  "RAGService" should "retry building the RAG pipeline after a startup failure instead of caching it" in withContainers { pg =>
    val db = freshDatabase(pg)
    // The embedding provider is unavailable for the startup (warm-up) build only
    run(plainService(db, FakeEmbeddings(failFirstBuilds = 1)).use { service =>
      for {
        withinCooldown <- service.search("anything", None).attempt
        notReady <- service.readinessCheck
        _ <- IO.sleep((RAGService.RetryCooldownMillis + 500).millis)
        afterCooldown <- service.search("anything", None).attempt
        ready <- service.readinessCheck
      } yield {
        withinCooldown.isLeft shouldBe true
        notReady.ready shouldBe false
        afterCooldown.isRight shouldBe true
        ready.ready shouldBe true
      }
    })
  }

  // ============================================================
  // Runtime config
  // ============================================================

  "RuntimeConfigManager.update" should "not lose concurrent updates to different settings" in withContainers { pg =>
    val db = freshDatabase(pg)
    run(pool(db).use { ds =>
      val updates = List(
        RuntimeConfigUpdateRequest(topK = Some(17)),
        RuntimeConfigUpdateRequest(fusionStrategy = Some("weighted")),
        RuntimeConfigUpdateRequest(rrfK = Some(42)),
        RuntimeConfigUpdateRequest(systemPrompt = Some("Be terse.")),
        RuntimeConfigUpdateRequest(llmTemperature = Some(0.25)),
        RuntimeConfigUpdateRequest(chunkingStrategy = Some("markdown")),
        RuntimeConfigUpdateRequest(chunkSize = Some(1234)),
        RuntimeConfigUpdateRequest(chunkOverlap = Some(77))
      )
      for {
        manager <- RuntimeConfigManager(TestFixtures.testAppConfig, ds)
        _ <- updates.parTraverse_(manager.update)
        current <- manager.get
        history <- manager.getHistory(1, 100)
      } yield {
        val c = current.config
        (c.topK, c.fusionStrategy, c.rrfK, c.systemPrompt) shouldBe (17, "weighted", 42, "Be terse.")
        (c.llmTemperature, c.chunkingStrategy, c.chunkSize, c.chunkOverlap) shouldBe (0.25, "markdown", 1234, 77)
        history.total shouldBe 8
        history.changes.map(_.field).toSet should have size 8
      }
    })
  }

  it should "record a consistent history for concurrent updates of the same setting" in withContainers { pg =>
    val db = freshDatabase(pg)
    run(pool(db).use { ds =>
      for {
        manager <- RuntimeConfigManager(TestFixtures.testAppConfig, ds)
        initial <- manager.get.map(_.config.topK)
        _ <- (1 to 10).toList.parTraverse_(k => manager.update(RuntimeConfigUpdateRequest(topK = Some(initial + k))))
        current <- manager.get.map(_.config.topK)
        rows <- IO.blocking(query(db, "SELECT old_value, new_value FROM config_history ORDER BY id") { rs =>
          val out = Vector.newBuilder[(String, String)]
          while (rs.next()) out += rs.getString(1) -> rs.getString(2)
          out.result()
        })
      } yield {
        rows should have size 10
        // Each change starts from the value the previous one set: a single unbroken chain
        rows.head._1 shouldBe initial.toString
        rows.zip(rows.tail).foreach { case ((_, newer), (older, _)) => older shouldBe newer }
        rows.last._2 shouldBe current.toString
      }
    })
  }

}

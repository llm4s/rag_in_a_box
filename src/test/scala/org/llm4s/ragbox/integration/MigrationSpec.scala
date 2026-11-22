package org.llm4s.ragbox.integration

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import org.llm4s.ragbox.config.DatabaseConfig
import org.llm4s.ragbox.db.Migrations
import org.llm4s.ragbox.registry.{ChatSessionRegistry, ExperimentRegistry, QueryLogRegistry}
import org.llm4s.ragbox.testkit.PgVectorFixture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.ResultSet
import scala.concurrent.duration._
import scala.io.Source
import scala.util.Using

/**
 * The Flyway migrations (issue #18) against a real PostgreSQL: a fresh database gets the full
 * schema; a database created by the pre-Flyway startup DDL, at its latest or an early state, is
 * adopted and ends with exactly the fresh schema, its data kept (and fixed where the old startup
 * code would have fixed it); migrating again, or from several instances at once, changes nothing.
 */
class MigrationSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  private def run[A](io: IO[A]): A = io.timeout(120.seconds).unsafeRunSync()

  private def resource(path: String): String =
    Using.resource(Source.fromResource(path))(_.mkString)

  private def execute(db: DatabaseConfig, sql: String): Unit =
    run(connect(db).use(conn => IO.blocking(Using.resource(conn.createStatement())(_.execute(sql))).void))

  private def rows[A](db: DatabaseConfig, sql: String)(read: ResultSet => A): Seq[A] =
    run(connect(db).use { conn =>
      IO.blocking(Using.resource(conn.createStatement()) { stmt =>
        val rs = stmt.executeQuery(sql)
        val out = Seq.newBuilder[A]
        while (rs.next()) out += read(rs)
        out.result()
      })
    })

  private def migrate(db: DatabaseConfig): Migrations.Result = run(Migrations.migrate(dataSource(db)))

  /** Everything about ragbox's tables that a migration could get wrong, order-independent. */
  private final case class Schema(
    tables: Set[String],
    columns: Set[(String, String, String, String, String, String)],
    indexes: Set[(String, String, String)],
    constraints: Set[(String, String, String)]
  )

  private val Excluded = "('flyway_schema_history')"

  private def schema(db: DatabaseConfig): Schema = Schema(
    tables = rows(db,
      s"""SELECT table_name FROM information_schema.tables
         |WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name NOT IN $Excluded""".stripMargin
    )(_.getString(1)).toSet,
    columns = rows(db,
      s"""SELECT table_name, column_name, udt_name, COALESCE(character_maximum_length::text, ''),
         |       is_nullable, COALESCE(column_default, '')
         |FROM information_schema.columns
         |WHERE table_schema = 'public' AND table_name NOT IN $Excluded""".stripMargin
    )(rs => (rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))).toSet,
    indexes = rows(db,
      s"""SELECT tablename, indexname, indexdef FROM pg_indexes
         |WHERE schemaname = 'public' AND tablename NOT IN $Excluded""".stripMargin
    )(rs => (rs.getString(1), rs.getString(2), rs.getString(3))).toSet,
    constraints = rows(db,
      s"""SELECT c.conrelid::regclass::text, c.conname, pg_get_constraintdef(c.oid)
         |FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
         |WHERE n.nspname = 'public' AND c.conrelid <> 0
         |  AND c.conrelid::regclass::text NOT IN $Excluded""".stripMargin
    )(rs => (rs.getString(1), rs.getString(2), rs.getString(3))).toSet
  )

  /** (version, type, success) of each row in Flyway's history, in order. */
  private def history(db: DatabaseConfig): Seq[(String, String, Boolean)] =
    rows(db, "SELECT version, type, success FROM flyway_schema_history ORDER BY installed_rank")(rs =>
      (rs.getString(1), rs.getString(2), rs.getBoolean(3))
    )

  private val RagboxTables = Set(
    "document_registry", "sync_status", "chunk_registry", "config_history", "runtime_config",
    "collection_configs", "query_logs", "users", "access_tokens", "experiments", "chat_sessions",
    "chat_messages", "oauth_sessions", "oauth_auth_state"
  )

  /** The schema of a freshly migrated database, computed once. */
  private lazy val freshSchema: Schema = withContainers(pg => schema(freshDatabase(pg)))

  // ============================================================
  // Fresh database
  // ============================================================

  "Migrating an empty database" should "create ragbox's full schema" in withContainers { pg =>
    val db = freshDatabase(pg, migrate = false)
    val result = migrate(db)
    result.applied.map(_.takeWhile(_ != ' ')) shouldBe Seq("V1", "V2")
    result.schemaVersion shouldBe Some("2")
    history(db) shouldBe Seq(("1", "SQL", true), ("2", "SQL", true))

    val s = schema(db)
    s.tables shouldBe RagboxTables
    s shouldBe freshSchema

    val columns = s.columns.map(c => (c._1, c._2))
    columns should contain allOf (
      ("document_registry", "readable_by"), ("document_registry", "source_id"), ("chunk_registry", "chunk_id"),
      ("query_logs", "variant"), ("query_logs", "failed"), ("query_logs", "cost_usd"),
      ("chat_sessions", "owner_id"), ("oauth_auth_state", "nonce"), ("oauth_auth_state", "browser_binding_hash")
    )
    s.columns.find(c => c._1 == "oauth_sessions" && c._2 == "groups").map(c => (c._3, c._5)) shouldBe Some(("_text", "NO"))

    val indexes = s.indexes.map(_._2)
    indexes should contain allOf (
      "idx_document_registry_updated_at", "idx_document_registry_source_id", "idx_chat_sessions_archived_updated_at",
      "idx_chat_sessions_owner", "idx_query_logs_experiment_variant", "uq_experiments_single_running"
    )
    // V2: redundant / unselective indexes are gone
    indexes should contain noneOf ("idx_chunk_registry_document", "idx_chat_sessions_archived")
    s.indexes.find(_._2 == "uq_experiments_single_running").map(_._3).get should include("WHERE ((status)::text = 'running'::text)")

    val constraints = s.constraints.map(c => (c._1, c._2))
    constraints should contain allOf (
      ("chat_messages", "uq_chat_messages_session_index"), ("chunk_registry", "chunk_registry_document_id_chunk_index_key"),
      ("sync_status", "single_row"), ("users", "users_username_key")
    )
    s.constraints.find(_._2 == "chat_messages_session_id_fkey").map(_._3) shouldBe
      Some("FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE")
    s.constraints.find(_._2 == "access_tokens_created_by_fkey").map(_._3) shouldBe
      Some("FOREIGN KEY (created_by) REFERENCES users(id)")

    // The seed row the sync status relies on
    rows(db, "SELECT id, last_sync_time FROM sync_status")(rs => (rs.getInt(1), Option(rs.getTimestamp(2)))) shouldBe Seq((1, None))
  }

  it should "be a no-op the second time" in withContainers { pg =>
    val db = freshDatabase(pg)
    val before = schema(db)
    val again = migrate(db)
    again.applied shouldBe empty
    again.schemaVersion shouldBe Some("2")
    history(db) should have size 2
    schema(db) shouldBe before
  }

  it should "apply each migration once when several instances start together" in withContainers { pg =>
    val db = freshDatabase(pg, migrate = false)
    val results = run((1 to 4).toList.parTraverse(_ => Migrations.migrate(dataSource(db))))
    results.flatMap(_.applied).map(_.takeWhile(_ != ' ')).sorted shouldBe Seq("V1", "V2")
    history(db) shouldBe Seq(("1", "SQL", true), ("2", "SQL", true))
    schema(db) shouldBe freshSchema
  }

  // ============================================================
  // Databases created before Flyway
  // ============================================================

  "Migrating a database created by the pre-Flyway startup DDL" should
    "adopt it, keep its data and end with the fresh schema" in withContainers { pg =>
      val db = freshDatabase(pg, migrate = false)
      // What the last release before Flyway created at startup, then its llm4s tables and data
      execute(db, resource("db/legacy/in-code-ddl.sql"))
      run(plainService(db).use(_.ingestDocument("Legacy content about pelicans.", "legacy-doc", Map("collection" -> "docs"))))
      execute(db,
        """INSERT INTO users (username, password_hash, role) VALUES ('admin', 'hash', 'admin');
          |INSERT INTO access_tokens (name, token_hash, token_prefix, scopes, created_by)
          |  VALUES ('ci', 'th', 'rat_abc', '{query}', 1);
          |INSERT INTO chat_sessions (id, title, owner_id) VALUES ('chat-1', 'Hi', 'user-1');
          |INSERT INTO chat_messages (id, session_id, role, content, message_index) VALUES
          |  ('msg-1', 'chat-1', 'user', 'q', 0), ('msg-2', 'chat-1', 'assistant', 'a', 1);
          |INSERT INTO query_logs (query_text, total_latency_ms, experiment_id, variant)
          |  VALUES ('what?', 12, 'exp-1', 'variant');
          |INSERT INTO experiments (id, name, status, baseline_config, variant_config, started_at)
          |  VALUES ('exp-1', 'e', 'running', '{}', '{}', NOW());
          |INSERT INTO runtime_config (field, value) VALUES ('topK', '7');
          |INSERT INTO collection_configs (collection, strategy) VALUES ('docs', 'markdown');
          |""".stripMargin)

      val tables = RagboxTables.toSeq.sorted
      def contents(): Seq[String] =
        tables.map(t => rows(db, s"SELECT COALESCE(string_agg(t::text, '|' ORDER BY t::text), '') FROM $t t")(_.getString(1)).head)
      val before = contents()
      val llm4sTables = rows(db,
        s"""SELECT table_name FROM information_schema.tables
           |WHERE table_schema = 'public' AND table_name NOT IN ${RagboxTables.mkString("('", "','", "')")}""".stripMargin
      )(_.getString(1)).toSet
      llm4sTables should not be empty // llm4s's own tables, which the migrations leave alone

      val result = migrate(db)
      result.applied.map(_.takeWhile(_ != ' ')) shouldBe Seq("V1", "V2")
      history(db) shouldBe Seq(("0", "BASELINE", true), ("1", "SQL", true), ("2", "SQL", true))

      // Same schema as a fresh database, same data; llm4s's tables untouched
      val adopted = schema(db)
      Schema(
        tables = adopted.tables -- llm4sTables,
        columns = adopted.columns.filterNot(c => llm4sTables(c._1)),
        indexes = adopted.indexes.filterNot(i => llm4sTables(i._1)),
        constraints = adopted.constraints.filterNot(c => llm4sTables(c._1))
      ) shouldBe freshSchema
      contents() shouldBe before

      // The application works on the adopted database
      val (entry, hits) = run(plainService(db).use { service =>
        for {
          entry <- service.getDocumentRegistry.get("legacy-doc")
          hits <- service.search("pelicans", Some(3))
        } yield (entry, hits)
      })
      entry.flatMap(_.collection) shouldBe Some("docs")
      hits.results.map(_.content).mkString should include("pelicans")

      migrate(db).applied shouldBe empty
    }

  it should "upgrade an early release's schema and fix its data as the old startup code did" in withContainers { pg =>
    val db = freshDatabase(pg, migrate = false)
    execute(db, resource("db/legacy/early-release.sql"))
    execute(db,
      """INSERT INTO document_registry (document_id, content_hash, chunk_count) VALUES ('doc-1', 'h1', 2);
        |INSERT INTO chunk_registry (document_id, chunk_index, content, content_length, collection) VALUES
        |  ('doc-1', 0, 'first', 5, ''), ('doc-1', 1, 'second', 6, 'docs');
        |INSERT INTO query_logs (query_text, total_latency_ms, experiment_id) VALUES
        |  ('packed', 10, 'exp-old:variant'), ('plain', 10, 'exp-new'), ('none', 10, NULL);
        |INSERT INTO experiments (id, name, status, baseline_config, variant_config, started_at) VALUES
        |  ('exp-a', 'a', 'running', '{}', '{}', NOW() - INTERVAL '2 days'),
        |  ('exp-b', 'b', 'running', '{}', '{}', NOW() - INTERVAL '1 day'),
        |  ('exp-c', 'c', 'draft', '{}', '{}', NULL);
        |INSERT INTO chat_sessions (id, title) VALUES ('chat-1', 'raced'), ('chat-2', 'fine');
        |INSERT INTO chat_messages (id, session_id, role, content, message_index, created_at) VALUES
        |  ('m1', 'chat-1', 'user', 'q1', 0, NOW() - INTERVAL '4 minutes'),
        |  ('m2', 'chat-1', 'assistant', 'a1', 1, NOW() - INTERVAL '3 minutes'),
        |  ('m3', 'chat-1', 'user', 'q2', 1, NOW() - INTERVAL '2 minutes'),
        |  ('m4', 'chat-1', 'assistant', 'a2', 2, NOW() - INTERVAL '1 minute'),
        |  ('m5', 'chat-2', 'user', 'only', 0, NOW());
        |INSERT INTO oauth_sessions (session_id, user_id, groups, provider, expires_at) VALUES
        |  ('s1', 'u1', 'a,b', 'p', NOW() + INTERVAL '1 hour'),
        |  ('s2', 'u2', '', 'p', NOW() + INTERVAL '1 hour'),
        |  ('s3', 'u3', NULL, 'p', NOW() + INTERVAL '1 hour');
        |INSERT INTO oauth_auth_state (state, code_verifier, expires_at) VALUES ('stale', 'v', NOW() + INTERVAL '1 hour');
        |""".stripMargin)

    migrate(db).applied.map(_.takeWhile(_ != ' ')) shouldBe Seq("V1", "V2")
    schema(db) shouldBe freshSchema

    // New columns get their defaults on existing rows
    rows(db, "SELECT document_id, readable_by::text, source_id, collection FROM document_registry")(rs =>
      (rs.getString(1), rs.getString(2), Option(rs.getString(3)), Option(rs.getString(4)))
    ) shouldBe Seq(("doc-1", "{}", None, None))
    rows(db, "SELECT failed FROM query_logs")(_.getBoolean(1)).distinct shouldBe Seq(false)

    // '' collections became NULL (the default collection)
    rows(db, "SELECT chunk_index, collection FROM chunk_registry ORDER BY chunk_index")(rs =>
      (rs.getInt(1), Option(rs.getString(2)))
    ) shouldBe Seq((0, None), (1, Some("docs")))

    // Experiment groups packed into the id were split out
    val logs = run(QueryLogRegistry(dataSource(db)).flatMap(_.list(None, None, None)))._1
    logs.map(e => (e.queryText, e.experimentId, e.variant)).sortBy(_._1) shouldBe Seq(
      ("none", None, None), ("packed", Some("exp-old"), Some("variant")), ("plain", Some("exp-new"), None)
    )

    // Only the most recently started experiment is still running
    val experiments = ExperimentRegistry(dataSource(db))
    run(experiments.flatMap(_.getRunning())).map(_.id) shouldBe Some("exp-b")
    rows(db, "SELECT id, status, ended_at IS NOT NULL FROM experiments ORDER BY id")(rs =>
      (rs.getString(1), rs.getString(2), rs.getBoolean(3))
    ) shouldBe Seq(("exp-a", "completed", true), ("exp-b", "running", false), ("exp-c", "draft", false))

    // Raced duplicate message indexes were renumbered in their existing order
    val chats = run(ChatSessionRegistry(dataSource(db)).flatMap(r => (r.getMessages("chat-1"), r.getMessages("chat-2")).tupled))
    chats._1.map(m => (m.id, m.messageIndex)) shouldBe Seq(("m1", 0), ("m2", 1), ("m3", 2), ("m4", 3))
    chats._2.map(m => (m.id, m.messageIndex)) shouldBe Seq(("m5", 0))

    // Comma-joined groups became arrays; login states without a nonce were dropped
    rows(db, "SELECT session_id, array_to_string(groups, '|') FROM oauth_sessions ORDER BY session_id")(rs =>
      (rs.getString(1), rs.getString(2))
    ) shouldBe Seq(("s1", "a|b"), ("s2", ""), ("s3", ""))
    rows(db, "SELECT COUNT(*) FROM oauth_auth_state")(_.getInt(1)) shouldBe Seq(0)

    // And a second run changes nothing
    migrate(db).applied shouldBe empty
    schema(db) shouldBe freshSchema
  }
}

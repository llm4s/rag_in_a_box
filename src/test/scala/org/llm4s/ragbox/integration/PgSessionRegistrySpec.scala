package org.llm4s.ragbox.integration

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.llm4s.ragbox.auth.*
import org.llm4s.ragbox.db.{ConnectionPool, Migrations}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.testcontainers.containers.wait.strategy.Wait

import java.sql.Timestamp
import java.time.Duration

/**
 * Tests PgSessionRegistry against a real PostgreSQL (Docker via Testcontainers).
 *
 * To run only these tests:
 *   sbt "testOnly *PgSessionRegistrySpec"
 */
class PgSessionRegistrySpec
    extends AsyncWordSpec
    with AsyncIOSpec
    with Matchers
    with TestContainerForAll:

  override val containerDef: GenericContainer.Def[GenericContainer] = GenericContainer.Def(
    dockerImage = "postgres:16",
    exposedPorts = Seq(5432),
    env = Map("POSTGRES_PASSWORD" -> "test", "POSTGRES_USER" -> "test", "POSTGRES_DB" -> "ragbox"),
    waitStrategy = Wait
      .forLogMessage(".*database system is ready to accept connections.*\\n", 2)
      .withStartupTimeout(Duration.ofMinutes(2))
  )

  private def withPool[A](f: HikariDataSource => IO[A]): IO[A] =
    withContainers { container =>
      val cfg = new HikariConfig()
      cfg.setJdbcUrl(
        s"jdbc:postgresql://${container.container.getHost}:${container.container.getMappedPort(5432)}/ragbox"
      )
      cfg.setUsername("test")
      cfg.setPassword("test")
      cfg.setMaximumPoolSize(2)
      cats.effect.Resource.make(IO(new HikariDataSource(cfg)))(ds => IO(ds.close())).use(f)
    }

  private def exec(pool: HikariDataSource, sql: String): IO[Unit] =
    ConnectionPool.withConnection(pool) { conn =>
      val stmt = conn.createStatement()
      try stmt.execute(sql)
      finally stmt.close()
      ()
    }

  /** An empty schema: no ragbox tables, no migration history. */
  private def emptySchema(pool: HikariDataSource): IO[Unit] =
    exec(pool, "DROP SCHEMA public CASCADE; CREATE SCHEMA public")

  /** A freshly migrated schema, as `Main` leaves it at startup. */
  private def migratedSchema(pool: HikariDataSource): IO[Unit] =
    emptySchema(pool) *> Migrations.migrate(pool).void

  private def session(id: String, groups: List[String]): OAuthSessionData =
    val now = System.currentTimeMillis()
    OAuthSessionData(
      sessionId = id,
      userId = "https://idp|user-1",
      email = Some("user@example.com"),
      name = Some("User"),
      groups = groups,
      provider = "https://idp",
      expiresAt = now + 60000,
      createdAt = now
    )

  "PgSessionRegistry" should {

    "store groups as TEXT[] so a group name containing a comma is not split" in {
      withPool { pool =>
        for
          _ <- migratedSchema(pool)
          registry <- PgSessionRegistry.create(pool)
          _ <- registry.createSession(session("s-comma", List("x,admins", "users")))
          _ <- registry.createSession(session("s-empty", Nil))
          loaded <- registry.getSession("s-comma")
          empty <- registry.getSession("s-empty")
          columnType <- ConnectionPool.withConnection(pool) { conn =>
            val rs = conn.createStatement().executeQuery(
              "SELECT data_type FROM information_schema.columns " +
                "WHERE table_name = 'oauth_sessions' AND column_name = 'groups'"
            )
            rs.next()
            rs.getString(1)
          }
        yield
          columnType shouldBe "ARRAY"
          loaded.map(_.groups) shouldBe Some(List("x,admins", "users"))
          empty.map(_.groups) shouldBe Some(Nil)
      }
    }

    "migrate a legacy comma-joined groups column, preserving existing rows" in {
      withPool { pool =>
        val now = new Timestamp(System.currentTimeMillis() + 60000)
        for
          _ <- emptySchema(pool)
          // Legacy schema as created by earlier releases (before Flyway)
          _ <- exec(
            pool,
            """CREATE TABLE oauth_sessions (
              |  session_id VARCHAR(64) PRIMARY KEY,
              |  user_id VARCHAR(255) NOT NULL,
              |  email VARCHAR(255),
              |  name VARCHAR(255),
              |  groups TEXT,
              |  provider VARCHAR(50) NOT NULL,
              |  expires_at TIMESTAMP NOT NULL,
              |  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
              |);
              |CREATE TABLE oauth_auth_state (
              |  state VARCHAR(64) PRIMARY KEY,
              |  code_verifier VARCHAR(128) NOT NULL,
              |  redirect_after_login VARCHAR(512),
              |  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              |  expires_at TIMESTAMP NOT NULL
              |)""".stripMargin
          )
          _ <- exec(
            pool,
            s"""INSERT INTO oauth_sessions (session_id, user_id, groups, provider, expires_at) VALUES
               |  ('legacy-1', 'u1', 'a,b', 'p', '$now'),
               |  ('legacy-2', 'u2', '', 'p', '$now'),
               |  ('legacy-3', 'u3', NULL, 'p', '$now');
               |INSERT INTO oauth_auth_state (state, code_verifier, expires_at) VALUES ('old-state', 'v', '$now')
               |""".stripMargin
          )
          // Flyway adopts the pre-Flyway database (baseline 0) and V1 converts it in place
          _ <- Migrations.migrate(pool)
          // Running the migrations again must be harmless
          _ <- Migrations.migrate(pool)
          registry <- PgSessionRegistry.create(pool)
          l1 <- registry.getSession("legacy-1")
          l2 <- registry.getSession("legacy-2")
          l3 <- registry.getSession("legacy-3")
          oldState <- registry.getAndRemoveAuthState("old-state")
          _ <- registry.createSession(session("new", List("x,admins")))
          fresh <- registry.getSession("new")
        yield
          l1.map(_.groups) shouldBe Some(List("a", "b"))
          l2.map(_.groups) shouldBe Some(Nil)
          l3.map(_.groups) shouldBe Some(Nil)
          // In-flight legacy login states have no nonce/binding and are discarded
          oldState shouldBe None
          fresh.map(_.groups) shouldBe Some(List("x,admins"))
      }
    }

    "round-trip auth state including nonce and browser binding hash" in {
      withPool { pool =>
        val state = AuthorizationState(
          state = "st-1",
          codeVerifier = "verifier",
          redirectAfterLogin = Some("/admin/"),
          createdAt = System.currentTimeMillis(),
          nonce = "nonce-1",
          browserBindingHash = "hash-1"
        )
        for
          _ <- migratedSchema(pool)
          registry <- PgSessionRegistry.create(pool)
          _ <- registry.storeAuthState(state)
          first <- registry.getAndRemoveAuthState("st-1")
          second <- registry.getAndRemoveAuthState("st-1")
        yield
          first shouldBe Some(state.copy(createdAt = first.get.createdAt))
          second shouldBe None
      }
    }
  }

  "SessionRegistry.create" should {

    "build a PostgreSQL registry when storage is postgres" in {
      withPool { pool =>
        for
          _ <- migratedSchema(pool)
          registry <- SessionRegistry.create(OAuthSessionStorage.Postgres, Some(pool))
          _ <- registry.createSession(session("factory", List("g")))
          loaded <- registry.getSession("factory")
        yield
          registry shouldBe a[PgSessionRegistry]
          loaded.map(_.groups) shouldBe Some(List("g"))
      }
    }
  }

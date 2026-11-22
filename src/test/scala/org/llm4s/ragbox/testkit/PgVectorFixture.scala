package org.llm4s.ragbox.testkit

import cats.effect.{IO, Resource}
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.llm4s.rag.permissions.pg.PgSearchIndex
import org.llm4s.ragbox.config.{ApiKeysConfig, AppConfig, DatabaseConfig, EmbeddingConfig}
import org.llm4s.ragbox.service.RAGService
import org.llm4s.security.Secret
import org.scalatest.TestSuite
import org.testcontainers.containers.wait.strategy.Wait

import java.sql.{Connection, DriverManager}
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import scala.util.Using

/**
 * A pgvector PostgreSQL container shared by a suite, for integration tests against a real
 * database. Requires Docker.
 *
 * Each test should take its own database with [[freshDatabase]], so tests never see each
 * other's documents, collections or registry rows and can run in any order.
 *
 * {{{
 * class MySpec extends AnyFlatSpec with Matchers with PgVectorFixture {
 *   "x" should "y" in withContainers { pg =>
 *     val db = freshDatabase(pg)
 *     permissionService(db).use { service => ... }.unsafeRunSync()
 *   }
 * }
 * }}}
 */
trait PgVectorFixture extends TestContainerForAll { self: TestSuite =>

  private val User = "ragbox"
  private val Password = "ragbox"

  override val containerDef: GenericContainer.Def[GenericContainer] = GenericContainer.Def(
    dockerImage = "pgvector/pgvector:pg16",
    exposedPorts = Seq(5432),
    env = Map(
      "POSTGRES_USER" -> User,
      "POSTGRES_PASSWORD" -> Password,
      "POSTGRES_DB" -> "postgres"
    ),
    // Postgres logs this line twice: once for the init-time server, once for the real one
    waitStrategy = Wait
      .forLogMessage(".*database system is ready to accept connections.*", 2)
      .withStartupTimeout(Duration.ofMinutes(2))
  )

  private val databaseCounter = new AtomicInteger(0)

  /** Create an empty database in the container and return a config pointing at it. */
  def freshDatabase(container: GenericContainer, tableName: String = "vectors"): DatabaseConfig = {
    val host = container.container.getHost
    val port = container.container.getMappedPort(5432).intValue
    val name = s"test_${databaseCounter.incrementAndGet()}"
    Using.resource(DriverManager.getConnection(s"jdbc:postgresql://$host:$port/postgres", User, Password)) { conn =>
      Using.resource(conn.createStatement())(_.execute(s"CREATE DATABASE $name"))
    }
    DatabaseConfig(
      host = host,
      port = port,
      database = name,
      user = User,
      password = Secret(Password),
      tableName = tableName,
      url = None
    )
  }

  /** Open a JDBC connection to `db`, for assertions on what is actually stored. */
  def connect(db: DatabaseConfig): Resource[IO, Connection] =
    Resource.fromAutoCloseable(IO.blocking(DriverManager.getConnection(db.connectionString, User, Password)))

  /**
   * An app config over `db` that embeds with [[FakeEmbeddings]] and has no LLM configured.
   */
  def testConfig(db: DatabaseConfig): AppConfig =
    TestFixtures.testAppConfig.copy(
      database = db,
      embedding = EmbeddingConfig(provider = "openai", model = FakeEmbeddings.Model, dimensions = Some(FakeEmbeddings.Dimensions)),
      apiKeys = ApiKeysConfig(openai = None, anthropic = None, voyage = None)
    )

  /**
   * A permission-mode RAGService over `db`, built the way `Main` builds one: a `PgSearchIndex`
   * with its schema initialised, then `RAGService.createWithPermissions`.
   */
  def permissionService(db: DatabaseConfig): Resource[IO, RAGService] =
    for {
      index <- Resource.make(
        IO.blocking {
          val si = PgSearchIndex
            .fromJdbcUrl(db.connectionString, db.effectiveUser, db.effectivePassword, db.tableName)
            .fold(e => throw new IllegalStateException(e.message), identity)
          si.initializeSchema().fold(e => throw new IllegalStateException(e.message), identity)
          si
        }
      )(si => IO.blocking(si.close()))
      service <- Resource.make(
        RAGService.createWithPermissions(testConfig(db), index, FakeEmbeddings.registry)
      )(_.close())
    } yield service

  /** A RAGService over `db` without permissions (no `SearchIndex`), as `Main` falls back to. */
  def plainService(db: DatabaseConfig): Resource[IO, RAGService] =
    Resource.make(RAGService.create(testConfig(db), FakeEmbeddings.registry))(_.close())
}

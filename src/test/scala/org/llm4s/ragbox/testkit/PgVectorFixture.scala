package org.llm4s.ragbox.testkit

import cats.effect.{IO, Resource}
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.llm4s.rag.permissions.pg.PgSearchIndex
import com.zaxxer.hikari.HikariDataSource
import org.llm4s.ragbox.config.{ApiKeysConfig, AppConfig, DatabaseConfig, EmbeddingConfig, RuntimeConfigManager}
import org.llm4s.ragbox.registry.PgCollectionConfigRegistry
import org.llm4s.ragbox.db.{ConnectionPool, Migrations}
import cats.effect.unsafe.implicits.global
import org.postgresql.ds.PGSimpleDataSource
import org.llm4s.ragbox.service.RAGService
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.security.Secret
import org.scalatest.TestSuite
import org.testcontainers.containers.wait.strategy.Wait

import java.sql.{Connection, DriverManager}
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
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

  /**
   * Create a database in the container and return a config pointing at it. The database has
   * ragbox's schema, applied by the real Flyway migrations as `Main` applies them at startup,
   * unless `migrate` is false (an empty database, for migration tests).
   */
  def freshDatabase(container: GenericContainer, tableName: String = "vectors", migrate: Boolean = true): DatabaseConfig = {
    val host = container.container.getHost
    val port = container.container.getMappedPort(5432).intValue
    val name = s"test_${databaseCounter.incrementAndGet()}"
    // DriverManager only sees drivers its caller's class loader has loaded; a suite whose first
    // database access is this one would otherwise find no driver under a layered sbt class loader
    Class.forName("org.postgresql.Driver")
    Using.resource(DriverManager.getConnection(s"jdbc:postgresql://$host:$port/postgres", User, Password)) { conn =>
      Using.resource(conn.createStatement())(_.execute(s"CREATE DATABASE $name"))
    }
    val db = DatabaseConfig(
      host = host,
      port = port,
      database = name,
      user = User,
      password = Secret(Password),
      tableName = tableName,
      url = None
    )
    if (migrate) Migrations.migrate(dataSource(db)).unsafeRunSync()
    db
  }

  /** A plain (unpooled) data source over `db`, e.g. for running migrations. */
  def dataSource(db: DatabaseConfig): DataSource = {
    val ds = new PGSimpleDataSource()
    ds.setURL(db.connectionString)
    ds.setUser(User)
    ds.setPassword(Password)
    ds
  }

  /** Open a JDBC connection to `db`, for assertions on what is actually stored. */
  def connect(db: DatabaseConfig): Resource[IO, Connection] =
    Resource.fromAutoCloseable(IO.blocking(DriverManager.getConnection(db.connectionString, User, Password)))

  /**
   * The application's connection pool over `db`, as `Main` creates it. Pass `maxPoolSize` to
   * test behaviour under a small pool.
   */
  def pool(db: DatabaseConfig, maxPoolSize: Int = DatabaseConfig.DefaultPoolSize): Resource[IO, HikariDataSource] =
    ConnectionPool.create(
      db,
      Some(ConnectionPool.PoolConfig(
        maximumPoolSize = maxPoolSize,
        minimumIdle = math.min(1, maxPoolSize),
        connectionTimeoutMs = 10000
      ))
    )

  /**
   * An app config over `db` that embeds with [[FakeEmbeddings]]. With `llm`, answers come from
   * that [[FakeLLM]] (`fake/fake-model`); without one, no LLM is available.
   */
  def testConfig(db: DatabaseConfig, llm: Option[FakeLLM] = None): AppConfig = {
    val base = TestFixtures.testAppConfig
    base.copy(
      database = db,
      embedding = EmbeddingConfig(provider = "openai", model = FakeEmbeddings.Model, dimensions = Some(FakeEmbeddings.Dimensions)),
      llm = llm.fold(base.llm)(_ => base.llm.copy(model = s"${FakeLLM.Id.asString}/${FakeLLM.Model}")),
      apiKeys = ApiKeysConfig()
    )
  }

  /** The fake embedding provider's registry, with the fake LLM provider when there is one. */
  def registryFor(embeddings: FakeEmbeddings, llm: Option[FakeLLM]): ProviderRegistry =
    llm.fold(embeddings.registry)(l => embeddings.registry.withProvider(l.descriptor))

  /**
   * A permission-mode RAGService over `db`, built the way `Main` builds one: a connection pool,
   * a `PgSearchIndex` over that pool with its schema initialised, then
   * `RAGService.createWithPermissions`.
   *
   * Pass the same `embeddings` to several services to count embedding work across them.
   */
  def permissionService(
    db: DatabaseConfig,
    embeddings: FakeEmbeddings = FakeEmbeddings(),
    llm: Option[FakeLLM] = None,
    configure: AppConfig => AppConfig = identity
  ): Resource[IO, RAGService] =
    for {
      ds <- pool(db)
      index <- Resource.make(
        IO.blocking {
          val si = PgSearchIndex
            .fromDataSource(ds, db.tableName)
            .fold(e => throw new IllegalStateException(e.message), identity)
          si.initializeSchema().fold(e => throw new IllegalStateException(e.message), identity)
          si
        }
      )(si => IO.blocking(si.close()))
      service <- Resource.make(
        RAGService.createWithPermissions(configure(testConfig(db, llm)), ds, index, registryFor(embeddings, llm))
      )(_.close())
    } yield service

  /** A RAGService over `db` without permissions (no `SearchIndex`), as `Main` falls back to. */
  def plainService(
    db: DatabaseConfig,
    embeddings: FakeEmbeddings = FakeEmbeddings(),
    llm: Option[FakeLLM] = None,
    configure: AppConfig => AppConfig = identity
  ): Resource[IO, RAGService] =
    pool(db).flatMap(ds =>
      Resource.make(RAGService.create(configure(testConfig(db, llm)), ds, registryFor(embeddings, llm)))(_.close())
    )

  /**
   * A RAGService wired as `Main` wires it: over a [[RuntimeConfigManager]] (runtime settings
   * restored from `db`) and a [[PgCollectionConfigRegistry]], with or without permissions.
   */
  def liveService(
    db: DatabaseConfig,
    permissions: Boolean,
    embeddings: FakeEmbeddings = FakeEmbeddings(),
    llm: Option[FakeLLM] = None,
    configure: AppConfig => AppConfig = identity
  ): Resource[IO, LiveService] = {
    val config = configure(testConfig(db, llm))
    for {
      ds <- pool(db)
      runtime <- Resource.eval(RuntimeConfigManager(config, ds, permissionMode = permissions))
      collections <- Resource.eval(PgCollectionConfigRegistry(ds))
      index <-
        if (!permissions) Resource.pure[IO, Option[PgSearchIndex]](None)
        else
          Resource.make(IO.blocking {
            val si = PgSearchIndex.fromDataSource(ds, db.tableName).fold(e => throw new IllegalStateException(e.message), identity)
            si.initializeSchema().fold(e => throw new IllegalStateException(e.message), identity)
            Option(si)
          })(si => IO.blocking(si.foreach(_.close())))
      service <- Resource.make(index match {
        case Some(si) =>
          RAGService.createWithPermissions(config, ds, si, registryFor(embeddings, llm), Some(runtime), Some(collections))
        case None =>
          RAGService.create(config, ds, registryFor(embeddings, llm), Some(runtime), Some(collections))
      })(_.close())
    } yield LiveService(service, runtime, collections, ds)
  }
}

/** A [[PgVectorFixture.liveService]]: the service and the runtime config it reads. */
final case class LiveService(
  service: RAGService,
  runtime: RuntimeConfigManager,
  collectionConfigs: PgCollectionConfigRegistry,
  dataSource: javax.sql.DataSource
)

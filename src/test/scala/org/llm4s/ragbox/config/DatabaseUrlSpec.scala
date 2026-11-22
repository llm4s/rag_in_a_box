package org.llm4s.ragbox.config

import com.typesafe.config.ConfigFactory
import org.llm4s.security.Secret
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

class DatabaseUrlSpec extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {

  // ============================================================
  // DatabaseUrl.parse - valid inputs
  // ============================================================

  private val validUrls = Table(
    ("input", "jdbcUrl", "user", "password", "host", "port", "database"),
    // Documented form
    ("postgresql://rag:secret@db.example.com:5432/ragdb",
      "jdbc:postgresql://db.example.com:5432/ragdb", Some("rag"), Some("secret"), "db.example.com", Some(5432), Some("ragdb")),
    // Heroku-style scheme
    ("postgres://rag:secret@db:6543/ragdb",
      "jdbc:postgresql://db:6543/ragdb", Some("rag"), Some("secret"), "db", Some(6543), Some("ragdb")),
    // JDBC form, with and without userinfo
    ("jdbc:postgresql://db:5432/ragdb",
      "jdbc:postgresql://db:5432/ragdb", None, None, "db", Some(5432), Some("ragdb")),
    ("jdbc:postgresql://rag:secret@db:5432/ragdb",
      "jdbc:postgresql://db:5432/ragdb", Some("rag"), Some("secret"), "db", Some(5432), Some("ragdb")),
    // Scheme is case-insensitive
    ("JDBC:PostgreSQL://db/ragdb",
      "jdbc:postgresql://db:5432/ragdb", None, None, "db", None, Some("ragdb")),
    // No port: defaults to 5432
    ("postgresql://rag:secret@db/ragdb",
      "jdbc:postgresql://db:5432/ragdb", Some("rag"), Some("secret"), "db", None, Some("ragdb")),
    // No database: falls back to the configured default ("fallbackdb")
    ("postgresql://rag:secret@db:5432",
      "jdbc:postgresql://db:5432/fallbackdb", Some("rag"), Some("secret"), "db", Some(5432), None),
    ("postgresql://db:5432/",
      "jdbc:postgresql://db:5432/fallbackdb", None, None, "db", Some(5432), None),
    // User without password
    ("postgresql://rag@db/ragdb",
      "jdbc:postgresql://db:5432/ragdb", Some("rag"), None, "db", None, Some("ragdb")),
    // Empty password
    ("postgresql://rag:@db/ragdb",
      "jdbc:postgresql://db:5432/ragdb", Some("rag"), Some(""), "db", None, Some("ragdb")),
    // Query params are passed through
    ("postgresql://rag:secret@db:5432/ragdb?sslmode=require&connectTimeout=10",
      "jdbc:postgresql://db:5432/ragdb?sslmode=require&connectTimeout=10", Some("rag"), Some("secret"), "db", Some(5432), Some("ragdb")),
    ("postgresql://db/ragdb?",
      "jdbc:postgresql://db:5432/ragdb", None, None, "db", None, Some("ragdb")),
    // Percent-encoded credentials are decoded; '+' is literal, not a space
    ("postgresql://us%40er:p%40ss%3Aw%2Frd%25@db/ragdb",
      "jdbc:postgresql://db:5432/ragdb", Some("us@er"), Some("p@ss:w/rd%"), "db", None, Some("ragdb")),
    ("postgresql://rag:a+b@db/ragdb",
      "jdbc:postgresql://db:5432/ragdb", Some("rag"), Some("a+b"), "db", None, Some("ragdb")),
    // Unencoded '@', ':' and '/' in the password are tolerated (last '@' wins)
    ("postgresql://rag:p@ss:w/rd@db:5432/ragdb",
      "jdbc:postgresql://db:5432/ragdb", Some("rag"), Some("p@ss:w/rd"), "db", Some(5432), Some("ragdb")),
    // Percent-encoded database name is decoded, then re-encoded for JDBC
    ("postgresql://db/my%20db",
      "jdbc:postgresql://db:5432/my%20db", None, None, "db", None, Some("my db")),
    // IPv6 literal
    ("postgresql://rag:secret@[::1]:5433/ragdb",
      "jdbc:postgresql://[::1]:5433/ragdb", Some("rag"), Some("secret"), "[::1]", Some(5433), Some("ragdb")),
    ("postgresql://[2001:db8::1]/ragdb",
      "jdbc:postgresql://[2001:db8::1]:5432/ragdb", None, None, "[2001:db8::1]", None, Some("ragdb")),
    // Surrounding whitespace is ignored
    ("  postgresql://db/ragdb \n",
      "jdbc:postgresql://db:5432/ragdb", None, None, "db", None, Some("ragdb"))
  )

  "DatabaseUrl.parse" should "parse valid URLs" in {
    forAll(validUrls) { (input, jdbcUrl, user, password, host, port, database) =>
      withClue(s"input: $input") {
        val parsed = DatabaseUrl.parse(input)
        parsed shouldBe a[Right[?, ?]]
        val url = parsed.toOption.get
        url.jdbcUrl(DatabaseUrl.DefaultPort, "fallbackdb") shouldBe jdbcUrl
        url.user shouldBe user
        url.password shouldBe password
        url.host shouldBe host
        url.port shouldBe port
        url.database shouldBe database
      }
    }
  }

  it should "never include credentials in the JDBC URL" in {
    forAll(validUrls) { (input, _, _, password, _, _, _) =>
      val jdbc = DatabaseUrl.parse(input).toOption.get.jdbcUrl(DatabaseUrl.DefaultPort, "fallbackdb")
      jdbc should not include "@"
      password.filter(_.nonEmpty).foreach(p => jdbc should not include p)
    }
  }

  // ============================================================
  // DatabaseUrl.parse - invalid inputs
  // ============================================================

  private val invalidUrls = Table(
    ("input", "expectedErrorFragment"),
    ("", "must look like"),
    ("db.example.com:5432/ragdb", "must look like"),
    ("mysql://rag:secret@db/ragdb", "unsupported scheme 'mysql'"),
    ("jdbc:mysql://db/ragdb", "unsupported scheme 'mysql'"),
    ("http://db/ragdb", "unsupported scheme 'http'"),
    ("postgresql://", "missing a host"),
    ("postgresql:///ragdb", "missing a host"),
    ("postgresql://rag:secret@/ragdb", "missing a host"),
    ("postgresql://:5432/ragdb", "missing a host"),
    ("postgresql://db:abc/ragdb", "invalid port"),
    ("postgresql://db:/ragdb", "invalid port"),
    ("postgresql://db:0/ragdb", "invalid port"),
    ("postgresql://db:70000/ragdb", "invalid port"),
    ("postgresql://db:+5432/ragdb", "invalid port"),
    ("postgresql://db1:5432,db2:5432/ragdb", "multiple hosts"),
    ("postgresql://::1/ragdb", "more than one ':'"),
    ("postgresql://[::1/ragdb", "unterminated IPv6"),
    ("postgresql://[::1]x/ragdb", "unexpected characters"),
    ("postgresql://db/rag/db", "invalid database path"),
    ("postgresql://rag:bad%zzpass@db/ragdb", "invalid percent-encoding in the password"),
    ("postgresql://bad%zz:secret@db/ragdb", "invalid percent-encoding in the user name")
  )

  it should "reject invalid URLs with a helpful error" in {
    forAll(invalidUrls) { (input, fragment) =>
      withClue(s"input: $input") {
        val result = DatabaseUrl.parse(input)
        result shouldBe a[Left[?, ?]]
        result.left.toOption.get should include(fragment)
      }
    }
  }

  it should "not leak the password in error messages" in {
    val result = DatabaseUrl.parse("postgresql://rag:supersecret@db:notaport/ragdb")
    result.left.toOption.get should not include "supersecret"
  }

  it should "not leak the password via toString" in {
    DatabaseUrl.parse("postgresql://rag:supersecret@db/ragdb").toOption.get.toString should not include "supersecret"
  }

  // ============================================================
  // DatabaseConfig effective values
  // ============================================================

  private val baseConfig = DatabaseConfig(
    host = "localhost",
    port = 15432,
    database = "ragdb",
    user = "rag",
    password = Secret("rag"),
    tableName = "rag_embeddings",
    url = None
  )

  "DatabaseConfig" should "use the individual settings when DATABASE_URL is not set" in {
    baseConfig.connectionString shouldBe "jdbc:postgresql://localhost:15432/ragdb"
    baseConfig.effectiveUser shouldBe "rag"
    baseConfig.effectivePassword.reveal shouldBe "rag"
    baseConfig.effectiveHost shouldBe "localhost"
    baseConfig.effectivePort shouldBe 15432
    baseConfig.effectiveDatabase shouldBe "ragdb"
    baseConfig.urlError shouldBe None
  }

  it should "treat a blank DATABASE_URL as unset" in {
    val cfg = baseConfig.copy(url = Some("   "))
    cfg.parsedUrl shouldBe None
    cfg.connectionString shouldBe "jdbc:postgresql://localhost:15432/ragdb"
  }

  it should "derive JDBC URL and credentials from DATABASE_URL" in {
    val cfg = baseConfig.copy(url = Some("postgresql://alice:p%40ss@prod-db:6000/prod?sslmode=require"))
    cfg.connectionString shouldBe "jdbc:postgresql://prod-db:6000/prod?sslmode=require"
    cfg.effectiveUser shouldBe "alice"
    cfg.effectivePassword.reveal shouldBe "p@ss"
    cfg.effectiveHost shouldBe "prod-db"
    cfg.effectivePort shouldBe 6000
    cfg.effectiveDatabase shouldBe "prod"
  }

  it should "default the port to 5432 rather than the configured port when DATABASE_URL omits it" in {
    val cfg = baseConfig.copy(url = Some("postgresql://prod-db/prod"))
    cfg.connectionString shouldBe "jdbc:postgresql://prod-db:5432/prod"
    cfg.effectivePort shouldBe 5432
  }

  it should "fall back to configured user, password and database when DATABASE_URL omits them" in {
    val cfg = baseConfig.copy(url = Some("jdbc:postgresql://prod-db:5432"))
    cfg.connectionString shouldBe "jdbc:postgresql://prod-db:5432/ragdb"
    cfg.effectiveUser shouldBe "rag"
    cfg.effectivePassword.reveal shouldBe "rag"
    cfg.effectiveDatabase shouldBe "ragdb"
  }

  it should "report an invalid DATABASE_URL and refuse to build a connection string" in {
    val cfg = baseConfig.copy(url = Some("mysql://db/ragdb"))
    cfg.urlError.get should include("unsupported scheme")
    an[IllegalArgumentException] should be thrownBy cfg.connectionString
    an[IllegalArgumentException] should be thrownBy cfg.effectiveUser
  }

  // ============================================================
  // AppConfig loading and validation
  // ============================================================

  private def load(overrides: String) =
    AppConfig.parseConfig(ConfigFactory.parseString(overrides).withFallback(ConfigFactory.load()).resolve())

  "AppConfig" should "fail validation for an invalid DATABASE_URL" in {
    val cfg = load("""database.url = "postgresql://db:notaport/ragdb" """)
    cfg.validate().exists(_.startsWith("Invalid DATABASE_URL")) shouldBe true
  }

  it should "pass DATABASE_URL validation for a valid URL" in {
    val cfg = load("""database.url = "postgresql://rag:rag@db:5432/ragdb" """)
    cfg.validate().exists(_.contains("DATABASE_URL")) shouldBe false
    cfg.database.connectionString shouldBe "jdbc:postgresql://db:5432/ragdb"
  }

  it should "default permissions.required to false" in {
    load("").permissions.required shouldBe false
  }

  it should "read permissions.required" in {
    load("permissions.required = true").permissions.required shouldBe true
  }

  it should "fail loudly on an invalid permissions.required value" in {
    a[com.typesafe.config.ConfigException] should be thrownBy load("""permissions.required = "maybe" """)
  }
}

package org.llm4s.ragbox.ingestion

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.typesafe.config.ConfigFactory

class IngestionConfigSpec extends AnyFlatSpec with Matchers {

  "DirectorySourceConfig" should "convert patterns to extensions" in {
    val config = DirectorySourceConfig(
      name = "test",
      path = "/data",
      patterns = Set("*.md", "*.txt", "*.pdf")
    )

    config.extensions should contain allOf ("md", "txt", "pdf")
  }

  it should "handle extensions with dots" in {
    val config = DirectorySourceConfig(
      name = "test",
      path = "/data",
      patterns = Set(".md", ".txt")
    )

    config.extensions should contain allOf ("md", "txt")
  }

  "UrlSourceConfig" should "store URLs" in {
    val config = UrlSourceConfig(
      name = "web",
      urls = Seq("https://example.com", "https://test.com")
    )

    config.urls.size `shouldBe` 2
    config.sourceType `shouldBe` "url"
  }

  private implicit class SourceConfigOps(sc: SourceConfig) {
    def sourceType: String = sc match {
      case _: DirectorySourceConfig => "directory"
      case _: UrlSourceConfig => "url"
      case _: DatabaseSourceConfig => "database"
      case _: WebCrawlerSourceConfig => "web"
      case _: S3SourceConfig => "s3"
    }
  }

  "DatabaseSourceConfig" should "store connection details" in {
    val config = DatabaseSourceConfig(
      name = "mydb",
      url = "jdbc:postgresql://localhost:5432/test",
      user = "user",
      password = "pass",
      query = "SELECT id, content FROM docs"
    )

    config.url should include ("postgresql")
    config.idColumn `shouldBe` "id"
    config.contentColumn `shouldBe` "content"
  }

  "IngestionConfig" should "load from typesafe config" in {
    val configString =
      """
        |ingestion {
        |  enabled = true
        |  run-on-startup = true
        |  schedule = "0 */6 * * *"
        |  sources = [
        |    {
        |      type = "directory"
        |      name = "docs"
        |      path = "/data/docs"
        |      patterns = ["*.md", "*.txt"]
        |      recursive = true
        |    }
        |  ]
        |}
        |""".stripMargin

    val config = ConfigFactory.parseString(configString)
    val ingestionConfig = IngestionConfig.fromConfig(config)

    ingestionConfig.enabled `shouldBe` true
    ingestionConfig.runOnStartup `shouldBe` true
    ingestionConfig.schedule `shouldBe` Some("0 */6 * * *")
    ingestionConfig.sources.size `shouldBe` 1

    ingestionConfig.sources.head match {
      case dir: DirectorySourceConfig =>
        dir.name `shouldBe` "docs"
        dir.path `shouldBe` "/data/docs"
        dir.recursive `shouldBe` true
      case _ => fail("Expected DirectorySourceConfig")
    }
  }

  it should "load URL sources" in {
    val configString =
      """
        |ingestion {
        |  enabled = true
        |  sources = [
        |    {
        |      type = "url"
        |      name = "web"
        |      urls = ["https://example.com/doc1", "https://example.com/doc2"]
        |    }
        |  ]
        |}
        |""".stripMargin

    val config = ConfigFactory.parseString(configString)
    val ingestionConfig = IngestionConfig.fromConfig(config)

    ingestionConfig.sources.head match {
      case url: UrlSourceConfig =>
        url.urls.size `shouldBe` 2
      case _ => fail("Expected UrlSourceConfig")
    }
  }

  it should "load database sources" in {
    val configString =
      """
        |ingestion {
        |  enabled = true
        |  sources = [
        |    {
        |      type = "database"
        |      name = "mydb"
        |      url = "jdbc:postgresql://localhost/test"
        |      user = "testuser"
        |      password = "testpass"
        |      query = "SELECT id, content FROM documents"
        |      id-column = "doc_id"
        |      content-column = "body"
        |    }
        |  ]
        |}
        |""".stripMargin

    val config = ConfigFactory.parseString(configString)
    val ingestionConfig = IngestionConfig.fromConfig(config)

    ingestionConfig.sources.head match {
      case db: DatabaseSourceConfig =>
        db.url should include ("postgresql")
        db.user `shouldBe` "testuser"
        db.idColumn `shouldBe` "doc_id"
        db.contentColumn `shouldBe` "body"
      case _ => fail("Expected DatabaseSourceConfig")
    }
  }

  it should "load web crawler sources" in {
    val configString =
      """
        |ingestion {
        |  enabled = true
        |  sources = [
        |    {
        |      type = "web"
        |      name = "company-docs"
        |      seed-urls = ["https://docs.example.com", "https://help.example.com"]
        |      max-depth = 3
        |      max-pages = 100
        |      follow-patterns = ["docs.example.com/*", "help.example.com/*"]
        |      exclude-patterns = ["*/api/*", "*/changelog/*"]
        |      respect-robots-txt = true
        |      delay-ms = 1000
        |      timeout-ms = 60000
        |      same-domain-only = true
        |    }
        |  ]
        |}
        |""".stripMargin

    val config = ConfigFactory.parseString(configString)
    val ingestionConfig = IngestionConfig.fromConfig(config)

    ingestionConfig.sources.head match {
      case web: WebCrawlerSourceConfig =>
        web.name `shouldBe` "company-docs"
        web.seedUrls.size `shouldBe` 2
        web.maxDepth `shouldBe` 3
        web.maxPages `shouldBe` 100
        web.followPatterns.size `shouldBe` 2
        web.excludePatterns.size `shouldBe` 2
        web.respectRobotsTxt `shouldBe` true
        web.delayMs `shouldBe` 1000
        web.timeoutMs `shouldBe` 60000
        web.sameDomainOnly `shouldBe` true
      case _ => fail("Expected WebCrawlerSourceConfig")
    }
  }

  it should "load web crawler with default values" in {
    val configString =
      """
        |ingestion {
        |  enabled = true
        |  sources = [
        |    {
        |      type = "web"
        |      name = "simple-crawler"
        |      seed-urls = ["https://example.com"]
        |    }
        |  ]
        |}
        |""".stripMargin

    val config = ConfigFactory.parseString(configString)
    val ingestionConfig = IngestionConfig.fromConfig(config)

    ingestionConfig.sources.head match {
      case web: WebCrawlerSourceConfig =>
        web.name `shouldBe` "simple-crawler"
        web.seedUrls.size `shouldBe` 1
        web.maxDepth `shouldBe` 3  // default
        web.maxPages `shouldBe` 500  // default
        web.followPatterns `shouldBe` Seq("*")  // default
        web.excludePatterns `shouldBe` Seq.empty  // default
        web.respectRobotsTxt `shouldBe` true  // default
        web.delayMs `shouldBe` 500  // default
        web.timeoutMs `shouldBe` 30000  // default
        web.sameDomainOnly `shouldBe` true  // default
      case _ => fail("Expected WebCrawlerSourceConfig")
    }
  }

  it should "return empty config when ingestion section missing" in {
    val config = ConfigFactory.parseString("{}")
    val ingestionConfig = IngestionConfig.fromConfig(config)

    ingestionConfig.enabled `shouldBe` false
    ingestionConfig.sources `shouldBe` empty
  }

  it should "load metadata for sources" in {
    val configString =
      """
        |ingestion {
        |  enabled = true
        |  sources = [
        |    {
        |      type = "directory"
        |      name = "docs"
        |      path = "/data"
        |      metadata {
        |        source = "internal"
        |        team = "engineering"
        |      }
        |    }
        |  ]
        |}
        |""".stripMargin

    val config = ConfigFactory.parseString(configString)
    val ingestionConfig = IngestionConfig.fromConfig(config)

    ingestionConfig.sources.head.metadata should contain ("source" -> "internal")
    ingestionConfig.sources.head.metadata should contain ("team" -> "engineering")
  }
}

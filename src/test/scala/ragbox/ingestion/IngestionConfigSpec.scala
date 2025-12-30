package ragbox.ingestion

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

package org.llm4s.ragbox.config

import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.llm4s.security.Secret
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ApiKeysConfigSpec extends AnyFlatSpec with Matchers {

  private def load(hocon: String, env: Map[String, String] = Map.empty): ApiKeysConfig =
    ApiKeysConfig.load(ConfigFactory.parseString(hocon).resolve(), env)

  "ApiKeysConfig.load" should "take any provider's key from llm4s's shared credentials" in {
    val keys = load("""llm4s.credentials { mistral.apiKey = "m-key", openai.apiKey = "o-key", deepseek {} }""")
    keys.getKey("mistral").map(_.reveal) shouldBe Some("m-key")
    keys.getKey("OpenAI").map(_.reveal) shouldBe Some("o-key")
    keys.getKey("deepseek") shouldBe None
  }

  it should "prefer ragbox's api-keys entry over llm4s's" in {
    val keys = load("""llm4s.credentials.openai.apiKey = "shared"
                      |api-keys.openai = "ragbox"""".stripMargin)
    keys.getKey("openai").map(_.reveal) shouldBe Some("ragbox")
  }

  it should "fall back to <ID>_API_KEY for a provider no module binds" in {
    val keys = load("", Map("ACME_AI_API_KEY" -> "a-key", "API_KEY" -> "ragbox-admin", "EMPTY_API_KEY" -> " "))
    ApiKeysConfig.envVarFor("acme-ai") shouldBe "ACME_AI_API_KEY"
    keys.getKey("acme-ai").map(_.reveal) shouldBe Some("a-key")
    keys.getKey("empty") shouldBe None
    keys.envKeys.keySet shouldBe Set("ACME_AI_API_KEY") // ragbox's own API_KEY is not a provider key
    keys.hasAnyKey shouldBe true
  }

  it should "ignore blank keys" in {
    val keys = load("""api-keys.openai = "  "
                      |llm4s.credentials.anthropic.apiKey = """"".stripMargin)
    keys.getKey("openai") shouldBe None
    keys.getKey("anthropic") shouldBe None
    keys.hasAnyKey shouldBe false
  }

  "llm4s's provider modules" should "bind each vendor's variable, so keys need no ragbox mapping" in {
    import scala.jdk.CollectionConverters._
    // The modules' reference.conf files, resolved against a fake environment
    val env = ConfigFactory.parseMap(Map(
      "OPENAI_API_KEY" -> "o", "ANTHROPIC_API_KEY" -> "a", "VOYAGE_API_KEY" -> "v", "COHERE_API_KEY" -> "c"
    ).asJava)
    val credentials = ConfigFactory.parseResources("reference.conf").withOnlyPath("llm4s.credentials")
      .withFallback(env)
      .resolve(ConfigResolveOptions.noSystem())
    val keys = ApiKeysConfig.load(credentials, Map.empty)
    Seq("openai" -> "o", "anthropic" -> "a", "voyage" -> "v", "cohere" -> "c").foreach { case (id, key) =>
      keys.getKey(id).map(_.reveal) shouldBe Some(key)
    }
  }

  "ApiKeysConfig.of" should "drop missing keys" in {
    val keys = ApiKeysConfig.of("openai" -> Some(Secret("k")), "voyage" -> None)
    keys.getKey("openai").map(_.reveal) shouldBe Some("k")
    keys.getKey("voyage") shouldBe None
  }
}

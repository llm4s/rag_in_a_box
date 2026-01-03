package ragbox.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class TokenScopeSpec extends AnyFlatSpec with Matchers {

  "TokenScope" should "define all expected scopes" in {
    TokenScope.DocumentsRead shouldBe "documents:read"
    TokenScope.DocumentsWrite shouldBe "documents:write"
    TokenScope.SyncRead shouldBe "sync:read"
    TokenScope.SyncWrite shouldBe "sync:write"
    TokenScope.Query shouldBe "query"
    TokenScope.Admin shouldBe "admin"
  }

  it should "validate known scopes" in {
    TokenScope.validate("documents:read") shouldBe true
    TokenScope.validate("documents:write") shouldBe true
    TokenScope.validate("sync:read") shouldBe true
    TokenScope.validate("sync:write") shouldBe true
    TokenScope.validate("query") shouldBe true
    TokenScope.validate("admin") shouldBe true
  }

  it should "reject unknown scopes" in {
    TokenScope.validate("unknown") shouldBe false
    TokenScope.validate("documents:delete") shouldBe false
    TokenScope.validate("") shouldBe false
    TokenScope.validate("DOCUMENTS:READ") shouldBe false  // Case sensitive
  }

  it should "have All set containing all scopes" in {
    TokenScope.All should contain("documents:read")
    TokenScope.All should contain("documents:write")
    TokenScope.All should contain("sync:read")
    TokenScope.All should contain("sync:write")
    TokenScope.All should contain("query")
    TokenScope.All should contain("admin")
    TokenScope.All.size shouldBe 6
  }
}

class AccessTokenSpec extends AnyFlatSpec with Matchers {

  "AccessToken" should "be created with all fields" in {
    val now = Instant.now()
    val token = AccessToken(
      id = "token-123",
      name = "confluence-ingester",
      tokenPrefix = "rat_abcd1234",
      scopes = Set("documents:write", "sync:write"),
      collections = Some(Set("confluence/*")),
      createdBy = Some(1),
      expiresAt = Some(now.plusSeconds(3600)),
      lastUsedAt = Some(now),
      createdAt = now
    )

    token.id shouldBe "token-123"
    token.name shouldBe "confluence-ingester"
    token.tokenPrefix shouldBe "rat_abcd1234"
    token.scopes should contain("documents:write")
    token.scopes should contain("sync:write")
    token.collections shouldBe Some(Set("confluence/*"))
    token.createdBy shouldBe Some(1)
    token.expiresAt shouldBe defined
    token.lastUsedAt shouldBe defined
  }

  it should "support tokens without collection restrictions" in {
    val now = Instant.now()
    val token = AccessToken(
      id = "token-456",
      name = "admin-token",
      tokenPrefix = "rat_efgh5678",
      scopes = Set("admin"),
      collections = None,
      createdBy = None,
      expiresAt = None,
      lastUsedAt = None,
      createdAt = now
    )

    token.collections shouldBe None
    token.expiresAt shouldBe None
    token.lastUsedAt shouldBe None
    token.createdBy shouldBe None
  }

  it should "support empty scope set" in {
    val now = Instant.now()
    val token = AccessToken(
      id = "token-789",
      name = "read-only",
      tokenPrefix = "rat_ijkl9012",
      scopes = Set.empty,
      collections = None,
      createdBy = None,
      expiresAt = None,
      lastUsedAt = None,
      createdAt = now
    )

    token.scopes shouldBe empty
  }
}

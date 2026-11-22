package org.llm4s.ragbox.auth

import org.llm4s.ragbox.testkit.TestAuth
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PrincipalSpec extends AnyFlatSpec with Matchers {

  private val restricted = TestAuth.token(Set(TokenScope.Query), Some(Set("eng", "docs/public")))

  "hasScope" should "only limit access tokens, and let the admin scope imply every scope" in {
    TestAuth.user("bob").hasScope(TokenScope.DocumentsWrite) shouldBe true
    restricted.hasScope(TokenScope.Query) shouldBe true
    restricted.hasScope(TokenScope.DocumentsWrite) shouldBe false
    TestAuth.token(Set(TokenScope.Admin)).hasScope(TokenScope.SyncWrite) shouldBe true
  }

  "canAccessCollection" should "allow restricted collections and their descendants only" in {
    restricted.canAccessCollection("eng") shouldBe true
    restricted.canAccessCollection("eng/api") shouldBe true
    restricted.canAccessCollection("engineering") shouldBe false
    restricted.canAccessCollection("docs") shouldBe false
    restricted.canAccessCollection("docs/public/x") shouldBe true
    TestAuth.user("bob").canAccessCollection("anything") shouldBe true
  }

  "canAccessPattern" should "reject patterns that reach outside the restriction" in {
    restricted.canAccessPattern("eng") shouldBe true
    restricted.canAccessPattern("eng/*") shouldBe true
    restricted.canAccessPattern("eng/**") shouldBe true
    restricted.canAccessPattern("*") shouldBe false
    restricted.canAccessPattern("**") shouldBe false
    restricted.canAccessPattern("docs/*") shouldBe false
    restricted.canAccessPattern("e*") shouldBe false
    TestAuth.Admin.canAccessPattern("*") shouldBe true
  }

  "oidcUser" should "be an administrator only in an admin group" in {
    val session = OAuthSessionData("s", "iss|alice", Some("a@x"), None, List("staff", "ragbox-admins"), "p", 0L, 0L)
    Principal.oidcUser(session, Set("ragbox-admins")).isAdmin shouldBe true
    Principal.oidcUser(session, Set.empty).isAdmin shouldBe false
    Principal.oidcUser(session, Set.empty).externalId shouldBe Some("iss|alice")
  }
}

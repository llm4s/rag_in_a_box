package org.llm4s.ragbox.routes

import org.http4s.Uri
import org.http4s.Uri.Path
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The per-collection path matcher (#54): raw and encoded slashes, and the trailing literals. */
class CollectionRouteSpec extends AnyFlatSpec with Matchers {

  /** The part of `/api/v1/collections/<rest>` the routes match, as http4s parses it. */
  private def rest(raw: String): Path = Uri.unsafeFromString(s"/api/v1/collections/$raw").path match {
    case p => Path(p.segments.drop(3))
  }

  private def full(raw: String): Path = Uri.unsafeFromString(s"/api/v1/collections/$raw").path

  "CollectionRoute.Bare" should "match single and nested collections, with raw or encoded slashes" in {
    CollectionRoute.Bare.unapply(rest("docs")) shouldBe Some("docs")
    CollectionRoute.Bare.unapply(rest("eng/backend")) shouldBe Some("eng/backend")
    CollectionRoute.Bare.unapply(rest("eng%2Fbackend")) shouldBe Some("eng/backend")
    CollectionRoute.Bare.unapply(rest("a/b%2Fc/d")) shouldBe Some("a/b/c/d")
  }

  it should "not match sub-resources, list endpoints or empty levels" in {
    Seq("eng/stats", "eng/permissions", "eng/config", "eng/config/preview", "accessible", "configs", "a//b", "a%2F%2Fb", "")
      .foreach(p => withClue(p)(CollectionRoute.Bare.unapply(rest(p)) shouldBe None))
  }

  it should "treat a single segment that is a sub-resource name as a collection" in {
    // Nothing precedes it, so it cannot be a suffix
    CollectionRoute.Bare.unapply(rest("stats")) shouldBe Some("stats")
    CollectionRoute.Bare.unapply(rest("config")) shouldBe Some("config")
  }

  it should "let an encoded slash address a collection whose last level is a sub-resource name" in {
    CollectionRoute.Bare.unapply(rest("eng%2Fstats")) shouldBe Some("eng/stats")
    CollectionRoute.Stats.unapply(rest("eng%2Fstats")) shouldBe None
    CollectionRoute.Stats.unapply(rest("eng%2Fstats/stats")) shouldBe Some("eng/stats")
  }

  "The suffixed extractors" should "take the collection before their trailing segments" in {
    CollectionRoute.Stats.unapply(rest("eng/backend/stats")) shouldBe Some("eng/backend")
    CollectionRoute.Stats.unapply(rest("eng%2Fbackend/stats")) shouldBe Some("eng/backend")
    CollectionRoute.Permissions.unapply(rest("eng/backend/permissions")) shouldBe Some("eng/backend")
    CollectionRoute.Config.unapply(rest("eng/backend/config")) shouldBe Some("eng/backend")
    CollectionRoute.ConfigPreview.unapply(rest("eng/backend/config/preview")) shouldBe Some("eng/backend")
    CollectionRoute.ConfigPreview.unapply(rest("docs/config/preview")) shouldBe Some("docs")
  }

  it should "not confuse one endpoint for another" in {
    CollectionRoute.Config.unapply(rest("eng/config/preview")) shouldBe None
    CollectionRoute.ConfigPreview.unapply(rest("eng/config")) shouldBe None
    CollectionRoute.Stats.unapply(rest("stats")) shouldBe None
    CollectionRoute.Permissions.unapply(rest("eng/permissions/stats")) shouldBe None
    CollectionRoute.Stats.unapply(rest("eng/permissions/stats")) shouldBe Some("eng/permissions")
    // `preview` alone is not a suffix: a collection's last level may be `preview`
    CollectionRoute.Bare.unapply(rest("eng/preview")) shouldBe Some("eng/preview")
  }

  "isConfig / isConfigPreview" should "classify request paths" in {
    CollectionRoute.isConfig(full("eng/backend/config")) shouldBe true
    CollectionRoute.isConfig(full("eng%2Fbackend/config")) shouldBe true
    CollectionRoute.isConfig(full("eng/backend/config/preview")) shouldBe false
    CollectionRoute.isConfigPreview(full("eng/backend/config/preview")) shouldBe true
    CollectionRoute.isConfig(full("configs")) shouldBe false
    CollectionRoute.isConfig(full("eng/backend")) shouldBe false
    CollectionRoute.isConfig(Uri.unsafeFromString("/api/v1/other/x/config").path) shouldBe false
  }
}

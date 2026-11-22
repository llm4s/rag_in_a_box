package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.http4s._
import org.http4s.circe._
import org.http4s.headers.`Content-Type`
import org.http4s.implicits._
import org.llm4s.rag.permissions.CollectionPath
import org.llm4s.ragbox.testkit.{MockCollectionStore, MockPrincipalStore}
import org.llm4s.ragbox.testkit.TestAuth.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** `CreateCollectionRequest.description` is kept in the llm4s collection's metadata (#53). */
class CollectionDescriptionSpec extends AnyFlatSpec with Matchers {

  private def create(body: String): (MockCollectionStore, Response[IO]) = {
    val store = MockCollectionStore()
    val routes = CollectionPermissionRoutes.routes(store, MockPrincipalStore()).asAdmin
    val response = routes.orNotFound.run(
      Request[IO](Method.POST, uri"/api/v1/collections")
        .withEntity(body)
        .withContentType(`Content-Type`(MediaType.application.json))
    ).unsafeRunSync()
    store -> response
  }

  "POST /api/v1/collections" should "store the description in the collection's metadata" in {
    val (store, response) = create("""{"path": "docs", "description": "Product docs", "metadata": {"owner": "team-a"}}""")
    response.status shouldBe Status.Created
    response.as[Json].unsafeRunSync().hcursor.downField("metadata").as[Map[String, String]] shouldBe
      Right(Map("owner" -> "team-a", "description" -> "Product docs"))
    val stored = store.get(CollectionPath.create("docs").toOption.get).toOption.flatten.get
    stored.metadata.get("description") shouldBe Some("Product docs")
  }

  it should "leave the metadata alone without a description" in {
    val (store, _) = create("""{"path": "docs", "metadata": {"owner": "team-a"}}""")
    store.get(CollectionPath.create("docs").toOption.get).toOption.flatten.get.metadata shouldBe Map("owner" -> "team-a")
  }
}

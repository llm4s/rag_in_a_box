package org.llm4s.ragbox.integration

import org.llm4s.ragbox.testkit.TestAuth.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.llm4s.rag.permissions._
import org.llm4s.ragbox.config.DatabaseConfig
import org.llm4s.ragbox.ingestion.{DirectorySourceConfig, IngestionConfig, IngestionService}
import org.llm4s.ragbox.routes.{DocumentRoutes, QueryRoutes}
import org.llm4s.ragbox.service.{InvalidRequestError, RAGService}
import org.llm4s.ragbox.testkit.{InMemoryQueryLogRegistry, PgVectorFixture}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import scala.util.Using

/**
 * Document lifecycle in permission mode, against a real pgvector database (issue #9).
 *
 * Every assertion about retrieval goes through `searchWithPermissions` - what query, chat and
 * search actually use - and every assertion about deletion also checks the vectors table.
 */
class PermissionLifecycleSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  private val anonymous = UserAuthorization.Anonymous

  private val solarText = "Photovoltaic solar panels convert sunlight into electricity for rooftops."
  private val solarQuery = "photovoltaic solar panels sunlight electricity"

  private def searchIds(service: RAGService, pattern: String = "*", auth: UserAuthorization = anonymous): Seq[String] =
    service.searchWithPermissions(solarQuery, auth, pattern, Some(10)).unsafeRunSync().results.flatMap(_.documentId)

  /** Vectors stored for `docId`, by collection path (NULL collection as None). */
  private def storedVectors(db: DatabaseConfig, docId: String): Seq[Option[String]] =
    connect(db).use { conn =>
      IO.blocking {
        Using.resource(conn.prepareStatement(
          s"""SELECT c.path FROM ${db.tableName} v LEFT JOIN llm4s_collections c ON c.id = v.collection_id
             |WHERE v.metadata->>'docId' = ?""".stripMargin
        )) { stmt =>
          stmt.setString(1, docId)
          Using.resource(stmt.executeQuery()) { rs =>
            Iterator.continually(rs).takeWhile(_.next()).map(r => Option(r.getString(1))).toVector
          }
        }
      }
    }.unsafeRunSync()

  /** Number of vectors stored for `docId`, for a table without permission columns. */
  private def vectorCount(db: DatabaseConfig, docId: String): Int =
    connect(db).use { conn =>
      IO.blocking {
        Using.resource(conn.prepareStatement(s"SELECT COUNT(*) FROM ${db.tableName} WHERE metadata->>'docId' = ?")) { stmt =>
          stmt.setString(1, docId)
          Using.resource(stmt.executeQuery())(rs => if (rs.next()) rs.getInt(1) else 0)
        }
      }
    }.unsafeRunSync()

  private def createCollection(service: RAGService, path: String, queryableBy: Set[PrincipalId] = Set.empty): Unit =
    service.collections.get
      .create(CollectionConfig(CollectionPath.unsafe(path), queryableBy = queryableBy))
      .fold(e => fail(e.message), _ => ())

  "Permission mode" should "make a document ingested without a collection retrievable (POST path)" in withContainers { pg =>
    val db = freshDatabase(pg)
    permissionService(db).use { service =>
      IO {
        service.ingestDocument(solarText, "doc-post", Map("filename" -> "solar.txt")).unsafeRunSync()

        searchIds(service) should contain("doc-post")
        storedVectors(db, "doc-post").toSet shouldBe Set(Some(RAGService.DefaultCollection))
      }
    }.unsafeRunSync()
  }

  it should "make a document upserted without a collection retrievable (PUT path)" in withContainers { pg =>
    val db = freshDatabase(pg)
    permissionService(db).use { service =>
      IO {
        service.upsertDocument("doc-put", solarText, Map.empty, None).unsafeRunSync().action shouldBe "created"
        service.upsertDocument("doc-put", solarText, Map.empty, None).unsafeRunSync().action shouldBe "unchanged"

        searchIds(service) should contain("doc-put")
        storedVectors(db, "doc-put").toSet shouldBe Set(Some(RAGService.DefaultCollection))
      }
    }.unsafeRunSync()
  }

  it should "migrate vectors written without a collection into the default collection at startup" in withContainers { pg =>
    val db = freshDatabase(pg)
    // Written by a server that ran without permissions: collection_id stays NULL
    plainService(db).use(_.ingestDocument(solarText, "legacy-doc", Map.empty)).unsafeRunSync()

    permissionService(db).use { service =>
      IO {
        storedVectors(db, "legacy-doc").toSet shouldBe Set(Some(RAGService.DefaultCollection))
        searchIds(service) should contain("legacy-doc")

        // ...and it can be deleted through the permission path
        service.deleteDocument("legacy-doc").unsafeRunSync()
        storedVectors(db, "legacy-doc") shouldBe empty
      }
    }.unsafeRunSync()
  }

  it should "not index a document twice when it was ingested outside the registry before migration" in withContainers { pg =>
    val db = freshDatabase(pg)
    // An ingestion source used to write vectors with RAG.sync: no collection, no registry entry
    plainService(db).use(_.rag.flatMap(r => IO(r.ingestText(solarText, "synced-doc")))).unsafeRunSync()

    permissionService(db).use { service =>
      IO {
        service.getDocumentEntries(Seq("synced-doc")).unsafeRunSync() shouldBe empty
        storedVectors(db, "synced-doc") should not be empty

        service.upsertDocument("synced-doc", solarText, Map.empty, None).unsafeRunSync().action shouldBe "created"

        val chunks = service.getDocumentEntries(Seq("synced-doc")).unsafeRunSync().map(_.chunkCount).sum
        storedVectors(db, "synced-doc") should have size chunks.toLong
        searchIds(service).count(_ == "synced-doc") shouldBe chunks
      }
    }.unsafeRunSync()
  }

  it should "remove a deleted document's vectors from search results" in withContainers { pg =>
    val db = freshDatabase(pg)
    permissionService(db).use { service =>
      IO {
        createCollection(service, "energy")
        service.ingestWithPermissions("energy", "doc-del", solarText).unsafeRunSync()
        service.ingestDocument(solarText, "doc-default", Map.empty).unsafeRunSync()
        searchIds(service) should contain allOf ("doc-del", "doc-default")

        service.deleteDocument("doc-del").unsafeRunSync()
        service.deleteDocument("doc-default").unsafeRunSync()

        searchIds(service) shouldBe empty
        storedVectors(db, "doc-del") shouldBe empty
        storedVectors(db, "doc-default") shouldBe empty
        service.getDocumentEntries(Seq("doc-del", "doc-default")).unsafeRunSync() shouldBe empty
        service.getDocumentChunks("doc-del").unsafeRunSync() shouldBe None
      }
    }.unsafeRunSync()
  }

  it should "clear every collection's documents, and keep the collections, when everything is cleared" in withContainers { pg =>
    val db = freshDatabase(pg)
    permissionService(db).use { service =>
      IO {
        createCollection(service, "energy")
        service.ingestWithPermissions("energy", "doc-energy", solarText).unsafeRunSync()
        service.ingestDocument(solarText, "doc-default", Map.empty).unsafeRunSync()
        searchIds(service) should contain allOf ("doc-energy", "doc-default")

        service.clearAll.unsafeRunSync()

        searchIds(service) shouldBe empty
        storedVectors(db, "doc-energy") shouldBe empty
        storedVectors(db, "doc-default") shouldBe empty
        service.listDocumentEntries.unsafeRunSync() shouldBe empty
        service.collections.get.exists(CollectionPath.unsafe("energy")) shouldBe Right(true)

        // The collections still take documents
        service.ingestWithPermissions("energy", "doc-again", solarText).unsafeRunSync()
        searchIds(service, "energy") shouldBe Seq("doc-again")
      }
    }.unsafeRunSync()
  }

  it should "remove vectors when pruning" in withContainers { pg =>
    val db = freshDatabase(pg)
    permissionService(db).use { service =>
      IO {
        service.upsertDocument("keep", solarText, Map.empty, None).unsafeRunSync()
        service.upsertDocument("drop", solarText, Map.empty, None).unsafeRunSync()

        service.pruneDocuments(Set("keep")).unsafeRunSync() shouldBe 1

        searchIds(service) shouldBe Seq("keep")
        storedVectors(db, "drop") shouldBe empty
      }
    }.unsafeRunSync()
  }

  it should "leave no copy in the old collection when a document moves" in withContainers { pg =>
    val db = freshDatabase(pg)
    permissionService(db).use { service =>
      IO {
        createCollection(service, "old")
        createCollection(service, "new")

        service.upsertWithPermissions("old", "mover", solarText).unsafeRunSync().action shouldBe "created"
        // Same content, different collection: a move, not "unchanged"
        service.upsertWithPermissions("new", "mover", solarText).unsafeRunSync().action shouldBe "updated"

        searchIds(service, "old") shouldBe empty
        searchIds(service, "new") shouldBe Seq("mover")
        storedVectors(db, "mover").toSet shouldBe Set(Some("new"))
        service.getDocumentEntries(Seq("mover")).unsafeRunSync().map(_.collection) shouldBe Seq(Some("new"))
      }
    }.unsafeRunSync()
  }

  it should "apply an access-list-only change" in withContainers { pg =>
    val db = freshDatabase(pg)
    permissionService(db).use { service =>
      IO {
        createCollection(service, "shared")
        val alice = service.principals.get.getOrCreate(ExternalPrincipal.User("alice@example.com"))
          .fold(e => fail(e.message), identity)
        val aliceAuth = UserAuthorization.forUser(alice)

        service.upsertWithPermissions("shared", "acl-doc", solarText, readableBy = Set(alice))
          .unsafeRunSync().action shouldBe "created"
        searchIds(service, "shared", aliceAuth) shouldBe Seq("acl-doc")
        searchIds(service, "shared", anonymous) shouldBe empty

        // Same content and collection; only the access list changes
        service.upsertWithPermissions("shared", "acl-doc", solarText, readableBy = Set.empty)
          .unsafeRunSync().action shouldBe "updated"
        searchIds(service, "shared", anonymous) shouldBe Seq("acl-doc")

        service.upsertWithPermissions("shared", "acl-doc", solarText, readableBy = Set.empty)
          .unsafeRunSync().action shouldBe "unchanged"
      }
    }.unsafeRunSync()
  }

  it should "reject an invalid collection path or pattern with a typed error" in withContainers { pg =>
    val db = freshDatabase(pg)
    permissionService(db).use { service =>
      IO {
        val badPath = service.ingestWithPermissions("not a/valid path!", "x", solarText).attempt.unsafeRunSync()
        badPath.left.toOption.get shouldBe a[InvalidRequestError]

        val badUpsert = service.upsertWithPermissions("bad path!", "x", solarText).attempt.unsafeRunSync()
        badUpsert.left.toOption.get shouldBe a[InvalidRequestError]

        // A malformed pattern used to widen silently to every collection
        val badPattern = service.searchWithPermissions(solarQuery, anonymous, "bad pattern!/**", None).attempt.unsafeRunSync()
        badPattern.left.toOption.get shouldBe a[InvalidRequestError]

        service.getDocumentEntries(Seq("x")).unsafeRunSync() shouldBe empty
      }
    }.unsafeRunSync()
  }

  it should "answer 400 to an invalid collection or pattern over HTTP" in withContainers { pg =>
    val db = freshDatabase(pg)
    permissionService(db).use { service =>
      val documents = DocumentRoutes.routes(service).asAdmin.orNotFound
      val queries = QueryRoutes.routes(service, InMemoryQueryLogRegistry()).asAdmin.orNotFound
      for {
        put <- documents.run(
          Request[IO](Method.PUT, uri"/api/v1/documents/doc-1")
            .withEntity(Json.obj("content" -> solarText.asJson, "collection" -> "bad path!".asJson))
        )
        post <- documents.run(
          Request[IO](Method.POST, uri"/api/v1/documents")
            .withEntity(Json.obj(
              "content" -> solarText.asJson,
              "filename" -> "a.txt".asJson,
              "collection" -> "bad path!".asJson
            ))
        )
        search <- queries.run(
          Request[IO](Method.POST, uri"/api/v1/search")
            .withEntity(Json.obj("query" -> solarQuery.asJson, "collection" -> "bad pattern!/**".asJson))
        )
        okPut <- documents.run(
          Request[IO](Method.PUT, uri"/api/v1/documents/doc-ok").withEntity(Json.obj("content" -> solarText.asJson))
        )
      } yield {
        put.status shouldBe Status.BadRequest
        post.status shouldBe Status.BadRequest
        search.status shouldBe Status.BadRequest
        okPut.status shouldBe Status.Created
      }
    }.unsafeRunSync()
  }

  it should "put documents from ingestion sources in the default collection" in withContainers { pg =>
    val db = freshDatabase(pg)
    val dir = Files.createTempDirectory("ragbox-ingest")
    Files.writeString(dir.resolve("solar.txt"), solarText)
    val ingestion = IngestionConfig(
      enabled = true,
      sources = Seq(DirectorySourceConfig(name = "docs", path = dir.toString, patterns = Set("*.txt")))
    )
    permissionService(db).use { service =>
      IO {
        val results = IngestionService(service, ingestion).runAll().unsafeRunSync()
        results.map(r => (r.error, r.documentsAdded)) shouldBe Seq((None, 1))

        val found = service.searchWithPermissions(solarQuery, anonymous, "*", Some(5)).unsafeRunSync().results
        found should not be empty
        val docId = found.head.documentId.get
        storedVectors(db, docId).toSet shouldBe Set(Some(RAGService.DefaultCollection))

        // A second run finds it unchanged rather than re-embedding it
        IngestionService(service, ingestion).runAll().unsafeRunSync().map(_.documentsUnchanged) shouldBe Seq(1)
      }
    }.unsafeRunSync()
  }

  "Plain mode (no SearchIndex)" should "still ingest, retrieve and delete as before" in withContainers { pg =>
    val db = freshDatabase(pg)
    plainService(db).use { service =>
      IO {
        service.upsertDocument("plain", solarText, Map.empty, None).unsafeRunSync().action shouldBe "created"
        service.search(solarQuery, Some(5)).unsafeRunSync().results.flatMap(_.documentId) should contain("plain")
        vectorCount(db, "plain") should be > 0

        service.deleteDocument("plain").unsafeRunSync()
        vectorCount(db, "plain") shouldBe 0
      }
    }.unsafeRunSync()
  }
}

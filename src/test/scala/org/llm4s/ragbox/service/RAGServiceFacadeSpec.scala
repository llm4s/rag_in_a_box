package org.llm4s.ragbox.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.llm4s.error.LLMError
import org.llm4s.rag.permissions._
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.service.Llm4sPipeline.ChatTurn
import org.llm4s.ragbox.testkit.{InMemoryChunkStore, InMemoryDocumentRegistry, MockCollectionStore, MockPrincipalStore, TestAuth, TestFixtures}
import org.llm4s.vectorstore.{MetadataFilter, ScoredRecord}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/**
 * [[RAGService]]'s own decisions, with the services it delegates to stubbed out: which path a
 * request takes in plain and in permission mode, the authorization a caller gets, and how
 * access lists are resolved. The paths themselves run against real stores in the integration
 * specs (`PermissionLifecycleSpec`, `Llm4sPipelineIntegrationSpec`).
 */
class RAGServiceFacadeSpec extends AnyFlatSpec with Matchers {

  /** A search index whose principal store is in memory; searching or ingesting through it is not expected. */
  private class StubIndex extends SearchIndex {
    val principalStore = new MockPrincipalStore
    def principals: PrincipalStore = principalStore
    def collections: CollectionStore = new MockCollectionStore
    def query(auth: UserAuthorization, pattern: CollectionPattern, embedding: Array[Float], topK: Int, filter: Option[MetadataFilter]): Either[LLMError, Seq[ScoredRecord]] =
      fail("query")
    def ingest(path: CollectionPath, documentId: String, chunks: Seq[ChunkWithEmbedding], metadata: Map[String, String], readableBy: Set[PrincipalId]): Either[LLMError, Seq[String]] =
      fail("ingest")
    def deleteDocument(path: CollectionPath, documentId: String): Either[LLMError, Long] = fail("deleteDocument")
    def clearCollection(path: CollectionPath): Either[LLMError, Long] = fail("clearCollection")
    def initializeSchema(): Either[LLMError, Unit] = Right(())
    def dropSchema(): Either[LLMError, Unit] = Right(())
    def close(): Unit = ()
  }

  private val answered = QueryResponse("answer", Nil)
  private val found = SearchResponse(Nil, 0)
  private val uploaded = DocumentUploadResponse("doc", 1, "ok")
  private val upserted = DocumentUpsertResponse("doc", 1, "created", "ok")

  /** Records which delegate each facade call reached, and with what. */
  private class Recording(index: Option[StubIndex]) extends RAGService(
    TestFixtures.testAppConfig, new InMemoryDocumentRegistry, new InMemoryChunkStore, index
  ) {
    val calls = mutable.ListBuffer.empty[(String, Any)]

    override def queryWithAnswer(question: String, topK: Option[Int], overrides: RAGService.SettingsOverride): IO[QueryResponse] =
      IO { calls += "queryWithAnswer" -> (question, topK, overrides) }.as(answered)
    override def queryWithPermissionsAndAnswer(question: String, auth: UserAuthorization, pattern: String, topK: Option[Int], overrides: RAGService.SettingsOverride): IO[QueryResponse] =
      IO { calls += "queryWithPermissionsAndAnswer" -> (question, auth, pattern, topK, overrides) }.as(answered)
    override def search(query: String, topK: Option[Int]): IO[SearchResponse] =
      IO { calls += "search" -> (query, topK) }.as(found)
    override def searchWithPermissions(query: String, auth: UserAuthorization, pattern: String, topK: Option[Int]): IO[SearchResponse] =
      IO { calls += "searchWithPermissions" -> (query, auth, pattern, topK) }.as(found)
    override def chatAnswer(question: String, history: Seq[ChatTurn], permissions: Option[(UserAuthorization, String)], topK: Option[Int]): IO[QueryResponse] =
      IO { calls += "chatAnswer" -> (question, history, permissions, topK) }.as(answered)
    override def ingestDocument(content: String, documentId: String, metadata: Map[String, String]): IO[DocumentUploadResponse] =
      IO { calls += "ingestDocument" -> (documentId, metadata) }.as(uploaded)
    override def ingestWithPermissions(path: String, documentId: String, content: String, metadata: Map[String, String], readableBy: Set[PrincipalId]): IO[DocumentUploadResponse] =
      IO { calls += "ingestWithPermissions" -> (path, documentId, readableBy) }.as(uploaded)
    override def upsertDocument(documentId: String, content: String, metadata: Map[String, String], hash: Option[String], sourceId: Option[String]): IO[DocumentUpsertResponse] =
      IO { calls += "upsertDocument" -> (documentId, hash) }.as(upserted)
    override def upsertWithPermissions(
      path: String, documentId: String, content: String, metadata: Map[String, String], readableBy: Set[PrincipalId],
      hash: Option[String], sourceId: Option[String]
    ): IO[DocumentUpsertResponse] =
      IO { calls += "upsertWithPermissions" -> (path, documentId, readableBy, hash) }.as(upserted)
  }

  private val overrides = RAGService.SettingsOverride(fusionStrategy = Some("keyword_only"))
  private val history = Seq(ChatTurn("user", "q0"), ChatTurn("assistant", "a0"))
  private val alice = TestAuth.user("alice", groups = Set("eng", "admins-not"))

  // ------------------------------------------------------------ plain mode

  "Without a search index" should "answer, search and chat without permission filtering" in {
    val rag = new Recording(None)
    (for {
      auth <- rag.authorizationFor(alice)
      _ <- rag.answer(alice, "q", "docs/*", Some(3), overrides)
      _ <- rag.searchAs(alice, "s", "docs/*", Some(4))
      _ <- rag.chatAs(alice, "c", history, "docs/*")
    } yield auth shouldBe None).unsafeRunSync()

    rag.hasPermissions shouldBe false
    rag.principals shouldBe None
    rag.calls.toList shouldBe List(
      "queryWithAnswer" -> ("q", Some(3), overrides),
      "search" -> ("s", Some(4)),
      "chatAnswer" -> ("c", history, None, None)
    )
  }

  it should "ingest and upsert plainly, ignoring collections and access lists" in {
    val rag = new Recording(None)
    (rag.createDocument("d1", "text", Map("k" -> "v"), Some("docs"), Some(Seq("not a principal"))) *>
      rag.upsertAs("d2", "text", Map.empty, Some("docs"), Some(Seq("user:bob")), Some("hash"))).unsafeRunSync()
    rag.calls.toList shouldBe List("ingestDocument" -> ("d1", Map("k" -> "v")), "upsertDocument" -> ("d2", Some("hash")))
  }

  // ------------------------------------------------------------ permission mode

  "With a search index" should "filter a user's answers, searches and chats by their user and groups" in {
    val index = new StubIndex
    val rag = new Recording(Some(index))
    val auth = (for {
      auth <- rag.authorizationFor(alice)
      _ <- rag.answer(alice, "q", "docs/*", Some(3), overrides)
      _ <- rag.searchAs(alice, "s", "docs", None)
      _ <- rag.chatAs(alice, "c", history, "eng/**")
    } yield auth.get).unsafeRunSync()

    val user = index.principalStore.lookup(ExternalPrincipal.User("alice")).toOption.flatten.get
    val groups = Set("eng", "admins-not").map(g => index.principalStore.lookup(ExternalPrincipal.Group(g)).toOption.flatten.get)
    auth shouldBe UserAuthorization.forUser(user, groups)
    auth.isAdmin shouldBe false

    rag.hasPermissions shouldBe true
    rag.calls.toList shouldBe List(
      "queryWithPermissionsAndAnswer" -> ("q", auth, "docs/*", Some(3), overrides),
      "searchWithPermissions" -> ("s", auth, "docs", None),
      "chatAnswer" -> ("c", history, Some(auth -> "eng/**"), None)
    )
  }

  it should "give administrators and access tokens unfiltered (admin) authorization" in {
    val rag = new Recording(Some(new StubIndex))
    (for {
      admin <- rag.authorizationFor(TestAuth.Admin)
      token <- rag.authorizationFor(TestAuth.token(Set("query"), Some(Set("docs"))))
    } yield (admin, token) shouldBe (Some(UserAuthorization.Admin), Some(UserAuthorization.Admin))).unsafeRunSync()
  }

  it should "give a principal without an external id anonymous authorization" in {
    val rag = new Recording(Some(new StubIndex))
    val anonymous = alice.copy(externalId = None)
    rag.authorizationFor(anonymous).unsafeRunSync() shouldBe Some(UserAuthorization.Anonymous)
  }

  it should "ingest into the named collection, or the default one, readable by the resolved principals" in {
    val index = new StubIndex
    val rag = new Recording(Some(index))
    (rag.createDocument("d1", "text", Map.empty, Some("docs/eng"), Some(Seq("user:bob", "group:eng"))) *>
      rag.createDocument("d2", "text", Map.empty, None, None) *>
      rag.upsertAs("d3", "text", Map.empty, None, Some(Seq("group:eng")), Some("h"))).unsafeRunSync()

    def id(e: ExternalPrincipal) = index.principalStore.lookup(e).toOption.flatten.get
    val bob = id(ExternalPrincipal.User("bob"))
    val eng = id(ExternalPrincipal.Group("eng"))
    rag.calls.toList shouldBe List(
      "ingestWithPermissions" -> ("docs/eng", "d1", Set(bob, eng)),
      "ingestWithPermissions" -> (RAGService.DefaultCollection, "d2", Set.empty),
      "upsertWithPermissions" -> (RAGService.DefaultCollection, "d3", Set(eng), Some("h"))
    )
  }

  it should "reject a malformed access list entry without ingesting" in {
    val rag = new Recording(Some(new StubIndex))
    val result = rag.createDocument("d1", "text", Map.empty, None, Some(Seq("user:bob", "nobody"))).attempt.unsafeRunSync()
    result.left.toOption.get shouldBe a[InvalidRequestError]
    result.left.toOption.get.getMessage should include("Invalid principal 'nobody'")
    rag.calls shouldBe empty
  }

  // ------------------------------------------------------------ settings

  "pipelineSettingsFor" should "reject strategies llm4s does not have, and apply valid overrides" in {
    val rag = new Recording(None)
    rag.pipelineSettingsFor(RAGService.SettingsOverride(fusionStrategy = Some("magic"))).isLeft shouldBe true
    rag.pipelineSettingsFor(RAGService.SettingsOverride(reranking = Some("sideways"))).isLeft shouldBe true
    rag.pipelineSettingsFor(RAGService.SettingsOverride.Empty) shouldBe Right(rag.currentPipelineSettings)
    rag.pipelineSettingsFor(RAGService.SettingsOverride(systemPrompt = Some("Be brief."))).map(_ == rag.currentPipelineSettings) shouldBe Right(false)
  }

  "chunkingConfig" should "bring sizes within llm4s's bounds" in {
    val effective = (target: Int, max: Int, overlap: Int) =>
      EffectiveCollectionConfig("simple", target, max, overlap, "runtime")
    val c = RAGService.chunkingConfig(effective(0, 0, 5))
    (c.targetSize, c.maxSize, c.overlap) shouldBe (1, 1, 0)
    val d = RAGService.chunkingConfig(effective(500, 300, 900))
    (d.targetSize, d.maxSize, d.overlap) shouldBe (500, 500, 499)
  }

  "sourceName" should "prefer the file name, then the path, then the source, then the id" in {
    RAGService.sourceName("id", Map("filename" -> "a.md", "path" -> "p", "source" -> "s")) shouldBe Some("a.md")
    RAGService.sourceName("id", Map("path" -> "p", "source" -> "s")) shouldBe Some("p")
    RAGService.sourceName("id", Map("source" -> "s")) shouldBe Some("s")
    RAGService.sourceName("id", Map.empty) shouldBe Some("id")
  }
}

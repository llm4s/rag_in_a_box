"""Tests for the RAG in a Box client, with the HTTP API mocked by ``responses``.

The mocked bodies follow the server's JSON (src/main/scala/org/llm4s/ragbox/model/ApiModels.scala).
"""

from datetime import datetime, timezone

import pytest
import requests
import responses
from responses import matchers

from ragbox import Document, RagBoxClient, RagBoxError

BASE = "http://ragbox.test"


@pytest.fixture
def client(monkeypatch):
    monkeypatch.delenv("RAGBOX_API_KEY", raising=False)
    return RagBoxClient(BASE)


# ---------------------------------------------------------------- authentication


@responses.activate
def test_api_key_is_sent_as_bearer_token(monkeypatch):
    monkeypatch.delenv("RAGBOX_API_KEY", raising=False)
    responses.get(
        f"{BASE}/api/v1/stats",
        json={"documentCount": 0},
        match=[matchers.header_matcher({"Authorization": "Bearer rat_abc"})],
    )
    RagBoxClient(BASE, api_key="rat_abc").stats()


@responses.activate
def test_api_key_falls_back_to_environment(monkeypatch):
    monkeypatch.setenv("RAGBOX_API_KEY", "from-env")
    responses.get(
        f"{BASE}/api/v1/stats",
        json={},
        match=[matchers.header_matcher({"Authorization": "Bearer from-env"})],
    )
    RagBoxClient(BASE).stats()


@responses.activate
def test_no_credential_sends_no_authorization_header(client):
    responses.get(f"{BASE}/health", json={"status": "healthy"})
    client.health()
    assert "Authorization" not in responses.calls[0].request.headers


@responses.activate
def test_error_response_is_raised_with_its_fields(client):
    responses.get(
        f"{BASE}/api/v1/stats",
        status=401,
        json={"error": "unauthorized", "message": "Authentication required"},
    )
    with pytest.raises(RagBoxError) as info:
        client.stats()
    assert info.value.status_code == 401
    assert info.value.error == "unauthorized"
    assert info.value.message == "Authentication required"
    assert isinstance(info.value, requests.HTTPError)


# ---------------------------------------------------------------- documents


@responses.activate
def test_upload_posts_json(client):
    responses.post(
        f"{BASE}/api/v1/documents",
        status=201,
        json={"documentId": "d1", "chunks": 3, "message": "ok"},
        match=[matchers.json_params_matcher({
            "content": "hello", "filename": "a.txt", "collection": "docs", "readableBy": ["alice"],
        })],
    )
    result = client.upload("hello", filename="a.txt", collection="docs", readable_by=["alice"])
    assert result["documentId"] == "d1"


@responses.activate
def test_upload_file_sends_multipart(client, tmp_path):
    path = tmp_path / "guide.md"
    path.write_bytes(b"# Guide")
    responses.post(f"{BASE}/api/v1/documents/upload", status=201,
                   json={"documentId": "d2", "chunks": 1, "message": "ok"})

    result = client.upload_file(path, collection="docs", metadata={"team": "a"}, readable_by=["alice", "bob"])

    assert result["documentId"] == "d2"
    request = responses.calls[0].request
    assert request.headers["Content-Type"].startswith("multipart/form-data")
    body = request.body
    assert b'name="file"; filename="guide.md"' in body
    assert b"# Guide" in body
    assert b'name="collection"' in body and b"docs" in body
    assert b'{"team": "a"}' in body
    assert b"alice,bob" in body


@responses.activate
def test_upsert_sends_hash_and_skips_unchanged(client):
    doc = Document(id="doc/1", content="text", metadata={"k": "v"}, collection="docs")
    responses.put(
        f"{BASE}/api/v1/documents/doc%2F1",
        status=201,
        json={"documentId": "doc/1", "chunks": 2, "action": "created", "message": "ok"},
        match=[matchers.json_params_matcher({
            "content": "text", "contentHash": doc.content_hash(), "metadata": {"k": "v"}, "collection": "docs",
        })],
    )
    assert client.upsert(doc).action == "created"
    # Second call is answered from the local cache
    assert client.upsert(doc).action == "unchanged"
    assert len(responses.calls) == 1


@responses.activate
def test_list_documents_passes_paging_and_filters(client):
    responses.get(
        f"{BASE}/api/v1/documents",
        json={"documents": [], "total": 0, "page": 2, "pageSize": 10, "hasMore": False},
        match=[matchers.query_param_matcher({"page": "2", "pageSize": "10", "collection": "docs", "search": "x"})],
    )
    assert client.list_documents(page=2, page_size=10, collection="docs", search="x")["page"] == 2


@responses.activate
def test_iter_documents_follows_pages(client):
    responses.get(f"{BASE}/api/v1/documents", json={"documents": [{"id": "a"}], "hasMore": True},
                  match=[matchers.query_param_matcher({"page": "1", "pageSize": "1"})])
    responses.get(f"{BASE}/api/v1/documents", json={"documents": [{"id": "b"}], "hasMore": False},
                  match=[matchers.query_param_matcher({"page": "2", "pageSize": "1"})])
    assert [d["id"] for d in client.iter_documents(page_size=1)] == ["a", "b"]


@responses.activate
def test_clear_all_uses_explicit_opt_in(client):
    responses.delete(f"{BASE}/api/v1/documents", json={"message": "All documents cleared"},
                     match=[matchers.query_param_matcher({"all": "true"})])
    client.clear_all()


@responses.activate
def test_delete_batch_sends_document_ids(client):
    responses.delete(
        f"{BASE}/api/v1/documents",
        json={"message": "ok", "deletedCount": 2, "dryRun": False},
        match=[matchers.json_params_matcher({"documentIds": ["a", "b"]})],
    )
    assert client.delete_batch(["a", "b"])["deletedCount"] == 2


@responses.activate
def test_collections(client):
    responses.get(f"{BASE}/api/v1/collections", json={"collections": ["default", "docs"]})
    assert client.collections() == ["default", "docs"]


# ---------------------------------------------------------------- query


@responses.activate
def test_query_parses_contexts_and_usage(client):
    responses.post(
        f"{BASE}/api/v1/query",
        json={
            "answer": "42",
            "contexts": [{"content": "c", "score": 0.9, "metadata": {}, "documentId": "d1", "chunkIndex": 0}],
            "usage": {"promptTokens": 10, "completionTokens": 5, "totalTokens": 15},
        },
        match=[matchers.json_params_matcher({"question": "q?", "topK": 3})],
    )
    result = client.query("q?", top_k=3)
    assert result.answer == "42"
    assert result.contexts[0].document_id == "d1"
    assert result.total_tokens == 15


@responses.activate
def test_query_sends_include_metadata_and_similarity_threshold(client):
    responses.post(
        f"{BASE}/api/v1/query",
        json={"answer": "a", "contexts": []},
        match=[matchers.json_params_matcher({
            "question": "q?", "includeMetadata": False, "overrides": {"similarityThreshold": 0.6}
        })],
    )
    assert client.query("q?", include_metadata=False, similarity_threshold=0.6).answer == "a"


@responses.activate
def test_search_parses_results(client):
    responses.post(f"{BASE}/api/v1/search",
                   json={"results": [{"content": "c", "score": 0.5, "metadata": {"a": "b"}}], "count": 1})
    result = client.search("q")
    assert result.count == 1
    assert result.results[0].metadata == {"a": "b"}


# ---------------------------------------------------------------- sync


@responses.activate
def test_list_synced_documents_reads_documents_array(client):
    responses.get(
        f"{BASE}/api/v1/sync/documents",
        json={"documents": [{"id": "a", "collection": "docs"}, {"id": "b"}], "total": 2},
    )
    assert client.list_synced_documents() == ["a", "b"]


@responses.activate
def test_synced_documents_follows_pages(client):
    responses.get(
        f"{BASE}/api/v1/sync/documents",
        json={"documents": [{"id": "a"}], "total": 2, "page": 1, "pageSize": 1, "hasMore": True},
        match=[matchers.query_param_matcher({"page": "1"})],
    )
    responses.get(
        f"{BASE}/api/v1/sync/documents",
        json={"documents": [{"id": "b"}], "total": 2, "page": 2, "pageSize": 1, "hasMore": False},
        match=[matchers.query_param_matcher({"page": "2"})],
    )
    assert client.list_synced_documents() == ["a", "b"]


@responses.activate
def test_synced_documents_include_and_since(client):
    responses.get(
        f"{BASE}/api/v1/sync/documents",
        json={"documents": [{"id": "a", "hash": "h", "updatedAt": "2026-01-02T03:04:05Z"}], "total": 1},
        match=[matchers.query_param_matcher({"include": "hash,updatedAt", "since": "2026-01-01T00:00:00Z", "page": "1"})],
    )
    docs = client.synced_documents(include_hash=True, include_updated_at=True, since=datetime(2026, 1, 1))
    assert docs[0].hash == "h"
    assert docs[0].updated_at == datetime(2026, 1, 2, 3, 4, 5, tzinfo=timezone.utc)


@responses.activate
def test_check_documents(client):
    responses.post(
        f"{BASE}/api/v1/sync/check",
        json={"found": [{"id": "a", "hash": "h"}], "missing": ["b"]},
        match=[matchers.json_params_matcher({"documentIds": ["a", "b"]})],
    )
    result = client.check_documents(["a", "b"])
    assert result["found"][0].id == "a"
    assert result["missing"] == ["b"]


@responses.activate
def test_sync_prunes_and_status(client):
    responses.post(
        f"{BASE}/api/v1/sync",
        json={"message": "Sync completed", "prunedCount": 1, "dryRun": False},
        match=[matchers.json_params_matcher({"keepDocumentIds": ["a"]})],
    )
    responses.get(
        f"{BASE}/api/v1/sync/status",
        json={"lastSyncTime": "2026-01-01T00:00:00Z", "documentCount": 1, "chunkCount": 4},
    )
    assert client.sync(keep_ids=["a"])["prunedCount"] == 1
    status = client.sync_status()
    assert status.document_count == 1
    assert status.last_sync_time == datetime(2026, 1, 1, tzinfo=timezone.utc)


@responses.activate
def test_sync_without_body_marks_complete(client):
    responses.post(f"{BASE}/api/v1/sync", json={"message": "Sync completed", "prunedCount": 0, "dryRun": False})
    client.sync()
    assert responses.calls[0].request.body is None


@responses.activate
def test_sync_dry_run(client):
    responses.post(
        f"{BASE}/api/v1/sync",
        json={"message": "Dry run", "prunedCount": 1, "prunedIds": ["x"], "dryRun": True},
        match=[matchers.json_params_matcher({"keepDocumentIds": ["a"], "dryRun": True})],
    )
    assert client.sync(keep_ids=["a"], dry_run=True)["prunedIds"] == ["x"]

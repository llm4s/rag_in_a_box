"""
RAG in a Box Python Client

Provides a simple interface for document ingestion and querying.
Supports incremental sync with content hash tracking.
"""

import hashlib
import json
import os
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote
from typing import Any, BinaryIO, Dict, List, Optional, Sequence, Union

import requests

#: Environment variable read when no ``api_key`` is passed to :class:`RagBoxClient`.
API_KEY_ENV = "RAGBOX_API_KEY"


class RagBoxError(requests.HTTPError):
    """
    An error response from the server.

    The server answers errors with ``{"error": ..., "message": ..., "details"?: ...}``;
    those fields are exposed here (``None`` if the body was not that shape). It is a
    ``requests.HTTPError``, so existing ``except requests.HTTPError`` handlers still work.
    """

    def __init__(self, response: requests.Response):
        self.status_code = response.status_code
        self.error: Optional[str] = None
        self.details: Optional[str] = None
        message: Optional[str] = None
        try:
            body = response.json()
            if isinstance(body, dict):
                self.error = body.get("error")
                message = body.get("message")
                self.details = body.get("details")
        except ValueError:
            pass
        self.message = message or response.reason or ""
        super().__init__(
            f"{response.status_code} {self.error or response.reason}: {self.message}",
            response=response,
        )


@dataclass
class Document:
    """A document to be ingested into RAG in a Box."""
    id: str
    content: str
    metadata: Dict[str, str] = field(default_factory=dict)
    collection: Optional[str] = None
    readable_by: Optional[List[str]] = None

    def content_hash(self) -> str:
        """Compute SHA-256 hash of content for change detection."""
        return hashlib.sha256(self.content.encode('utf-8')).hexdigest()


@dataclass
class ContextItem:
    """A context chunk returned from search."""
    content: str
    score: float
    metadata: Dict[str, str]
    document_id: Optional[str] = None
    chunk_index: Optional[int] = None


@dataclass
class QueryResult:
    """Result from a query with answer generation."""
    answer: str
    contexts: List[ContextItem]
    prompt_tokens: Optional[int] = None
    completion_tokens: Optional[int] = None
    total_tokens: Optional[int] = None
    query_id: Optional[str] = None
    """The query-log id: pass it to :meth:`RagBoxClient.feedback` to rate the answer."""


@dataclass
class SearchResult:
    """Result from a search (no LLM)."""
    results: List[ContextItem]
    count: int


@dataclass
class UpsertResult:
    """Result from upserting a document."""
    document_id: str
    chunks: int
    action: str  # "created", "updated", "unchanged"
    message: str


@dataclass
class SyncStatus:
    """Sync status information."""
    last_sync_time: Optional[datetime]
    document_count: int
    chunk_count: int


@dataclass
class SyncedDocument:
    """The server's record of a synced document (``GET /api/v1/sync/documents``)."""
    id: str
    hash: Optional[str] = None
    updated_at: Optional[datetime] = None
    collection: Optional[str] = None


def _parse_time(value: Optional[str]) -> Optional[datetime]:
    if not value:
        return None
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def _format_instant(value: Union[datetime, str]) -> str:
    """An ISO-8601 instant in UTC with a ``Z`` suffix, as the server expects; naive datetimes are taken as UTC."""
    if isinstance(value, str):
        return value
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _context(ctx: Dict[str, Any]) -> ContextItem:
    return ContextItem(
        content=ctx["content"],
        score=ctx["score"],
        metadata=ctx.get("metadata", {}),
        document_id=ctx.get("documentId"),
        chunk_index=ctx.get("chunkIndex"),
    )


def _synced(doc: Dict[str, Any]) -> SyncedDocument:
    return SyncedDocument(
        id=doc["id"],
        hash=doc.get("hash"),
        updated_at=_parse_time(doc.get("updatedAt")),
        collection=doc.get("collection"),
    )


class RagBoxClient:
    """
    Client for RAG in a Box API.

    Provides methods for document ingestion, querying, and sync operations.
    Tracks document state locally for efficient incremental updates.

    Example:
        client = RagBoxClient("http://localhost:8080", api_key="rat_...")

        # Upsert documents (idempotent)
        for doc in my_documents:
            result = client.upsert(doc)
            print(f"{doc.id}: {result.action}")

        # Query with answer generation
        result = client.query("What is PostgreSQL?")
        print(result.answer)

        # Mark sync complete and prune this ingester's orphaned documents
        client.sync(keep_ids=[doc.id for doc in my_documents])

    Give each ingester its own ``source`` (``RagBoxClient(..., source="confluence")``): every
    upsert records it, and :meth:`sync` only prunes that source's documents.
    """

    def __init__(
        self,
        base_url: str = "http://localhost:8080",
        timeout: int = 30,
        api_key: Optional[str] = None,
        session: Optional[requests.Session] = None,
        source: Optional[str] = None,
    ):
        """
        Initialize the RAG in a Box client.

        Args:
            base_url: Base URL of the RAG in a Box server (without ``/api/v1``)
            timeout: Request timeout in seconds
            api_key: Credential sent as ``Authorization: Bearer <api_key>``: the server's
                ``API_KEY``, a ``rat_`` access token, or a JWT from ``POST /api/v1/auth/login``
                (basic mode). Defaults to the ``RAGBOX_API_KEY`` environment variable; with
                neither, requests are unauthenticated (open mode without ``API_KEY``).
            session: Optional ``requests.Session`` to use (e.g. for retries or proxies)
            source: The sync source (ingester name) this client writes as. :meth:`upsert` records
                it on every document and :meth:`sync` prunes only its documents. ``None`` uses
                the server's default source, ``api``.
        """
        self.base_url = base_url.rstrip('/')
        self.source = source
        self.timeout = timeout
        self._session = session or requests.Session()

        token = api_key if api_key is not None else os.environ.get(API_KEY_ENV)
        if token:
            self._session.headers["Authorization"] = f"Bearer {token}"

        # Local state for tracking what we've pushed
        self._pushed_docs: Dict[str, str] = {}  # doc_id -> content_hash

    def _url(self, path: str) -> str:
        """Build full URL for API path."""
        return f"{self.base_url}{path}"

    def _request(self, method: str, path: str, **kwargs) -> requests.Response:
        """Make an HTTP request; raises :class:`RagBoxError` on an error status."""
        kwargs.setdefault('timeout', self.timeout)
        response = self._session.request(method, self._url(path), **kwargs)
        if response.status_code >= 400:
            raise RagBoxError(response)
        return response

    # ============================================================
    # Document Operations
    # ============================================================

    def upload(
        self,
        content: str,
        filename: Optional[str] = None,
        metadata: Optional[Dict[str, str]] = None,
        collection: Optional[str] = None,
        readable_by: Optional[Sequence[str]] = None,
    ) -> Dict[str, Any]:
        """
        Add a text document (``POST /api/v1/documents``); the server assigns its ID.

        For idempotent updates, use upsert() instead; for files (PDF, DOCX, ...) use
        upload_file().

        Args:
            content: Document text content
            filename: Optional filename for the document
            metadata: Optional metadata key-value pairs
            collection: Optional collection (default collection if omitted)
            readable_by: Optional principals allowed to read it (permission mode)

        Returns:
            ``{"documentId", "chunks", "message"}``
        """
        data: Dict[str, Any] = {"content": content}
        if filename:
            data["filename"] = filename
        if metadata:
            data["metadata"] = metadata
        if collection:
            data["collection"] = collection
        if readable_by:
            data["readableBy"] = list(readable_by)

        response = self._request("POST", "/api/v1/documents", json=data)
        return response.json()

    def upload_file(
        self,
        file: Union[str, Path, bytes, BinaryIO],
        filename: Optional[str] = None,
        metadata: Optional[Dict[str, str]] = None,
        collection: Optional[str] = None,
        readable_by: Optional[Sequence[str]] = None,
    ) -> Dict[str, Any]:
        """
        Upload a file (``POST /api/v1/documents/upload``, multipart/form-data).

        The server extracts the text (PDF, DOCX, HTML, Markdown, plain text, ... - anything
        Apache Tika reads) and ingests it like upload().

        Args:
            file: A path, the file's bytes, or a binary file object
            filename: Filename sent to the server (defaults to the path's name)
            metadata: Optional metadata key-value pairs
            collection: Optional collection
            readable_by: Optional principals allowed to read it (permission mode)

        Returns:
            ``{"documentId", "chunks", "message"}``
        """
        if isinstance(file, (str, Path)):
            path = Path(file)
            payload: Union[bytes, BinaryIO] = path.read_bytes()
            filename = filename or path.name
        else:
            payload = file
            filename = filename or getattr(file, "name", None) or "upload"
            filename = Path(str(filename)).name

        form: Dict[str, str] = {}
        if collection:
            form["collection"] = collection
        if metadata:
            form["metadata"] = json.dumps(metadata)
        if readable_by:
            form["readableBy"] = ",".join(readable_by)

        response = self._request(
            "POST", "/api/v1/documents/upload",
            files={"file": (filename, payload)}, data=form,
        )
        return response.json()

    def upsert(self, doc: Document) -> UpsertResult:
        """
        Upsert a document (idempotent create/update, ``PUT /api/v1/documents/{id}``).

        If the document doesn't exist or content has changed, it will be indexed.
        If the content is unchanged, the operation returns without re-indexing.

        Args:
            doc: Document to upsert

        Returns:
            UpsertResult with action taken ("created", "updated", "unchanged")
        """
        content_hash = doc.content_hash()

        # Check local cache first
        if doc.id in self._pushed_docs and self._pushed_docs[doc.id] == content_hash:
            return UpsertResult(
                document_id=doc.id,
                chunks=0,
                action="unchanged",
                message="Skipped (unchanged in local cache)"
            )

        data: Dict[str, Any] = {
            "content": doc.content,
            "contentHash": content_hash,
        }
        if doc.metadata:
            data["metadata"] = doc.metadata
        if doc.collection:
            data["collection"] = doc.collection
        if doc.readable_by:
            data["readableBy"] = list(doc.readable_by)
        if self.source:
            data["source"] = self.source

        response = self._request(
            "PUT", f"/api/v1/documents/{quote(doc.id, safe='')}", json=data
        )
        result = response.json()

        # Update local cache
        self._pushed_docs[doc.id] = content_hash

        return UpsertResult(
            document_id=result["documentId"],
            chunks=result["chunks"],
            action=result["action"],
            message=result["message"]
        )

    def upsert_batch(self, docs: Sequence[Document]) -> List[UpsertResult]:
        """Upsert multiple documents, one request each."""
        return [self.upsert(doc) for doc in docs]

    def get_document(self, document_id: str) -> Dict[str, Any]:
        """
        Get a document's metadata (``GET /api/v1/documents/{id}``).

        Returns:
            ``{"id", "filename", "chunkCount", "metadata", "createdAt", "collection"?, "updatedAt"?}``
        """
        response = self._request("GET", f"/api/v1/documents/{quote(document_id, safe='')}")
        return response.json()

    def delete(self, document_id: str) -> None:
        """Delete a document by ID (``DELETE /api/v1/documents/{id}``)."""
        self._request("DELETE", f"/api/v1/documents/{quote(document_id, safe='')}")
        self._pushed_docs.pop(document_id, None)

    def delete_batch(self, document_ids: Sequence[str], dry_run: bool = False) -> Dict[str, Any]:
        """
        Delete several documents (``DELETE /api/v1/documents`` with a ``documentIds`` body).

        Args:
            document_ids: IDs to delete
            dry_run: Only report what would be deleted

        Returns:
            ``{"message", "deletedCount", "deletedIds"?, "failedIds"?, "dryRun"}``
        """
        data: Dict[str, Any] = {"documentIds": list(document_ids)}
        if dry_run:
            data["dryRun"] = True
        response = self._request("DELETE", "/api/v1/documents", json=data)
        if not dry_run:
            for doc_id in document_ids:
                self._pushed_docs.pop(doc_id, None)
        return response.json()

    def list_documents(
        self,
        page: Optional[int] = None,
        page_size: Optional[int] = None,
        collection: Optional[str] = None,
        search: Optional[str] = None,
    ) -> Dict[str, Any]:
        """
        List documents, one page at a time (``GET /api/v1/documents``).

        Args:
            page: 1-based page number (server default 1)
            page_size: Items per page (server default 50, max 500)
            collection: Only documents in this collection
            search: Case-insensitive substring of id, filename or title

        Returns:
            ``{"documents": [...], "total", "page", "pageSize", "hasMore"}``
        """
        params: Dict[str, Any] = {}
        if page is not None:
            params["page"] = page
        if page_size is not None:
            params["pageSize"] = page_size
        if collection:
            params["collection"] = collection
        if search:
            params["search"] = search
        response = self._request("GET", "/api/v1/documents", params=params or None)
        return response.json()

    def iter_documents(
        self,
        page_size: int = 500,
        collection: Optional[str] = None,
        search: Optional[str] = None,
    ):
        """Yield every document, following the pages of list_documents()."""
        page = 1
        while True:
            result = self.list_documents(page=page, page_size=page_size, collection=collection, search=search)
            yield from result.get("documents", [])
            if not result.get("hasMore"):
                return
            page += 1

    def clear_all(self) -> None:
        """Delete all documents (administrators only).

        Uses the explicit ``?all=true`` opt-in; the server rejects a bare
        ``DELETE /api/v1/documents`` with 400.
        """
        self._request("DELETE", "/api/v1/documents", params={"all": "true"})
        self._pushed_docs.clear()

    def collections(self) -> List[str]:
        """List the collection names the caller can see (``GET /api/v1/collections``)."""
        response = self._request("GET", "/api/v1/collections")
        return response.json().get("collections", [])

    # ============================================================
    # Query Operations
    # ============================================================

    def query(
        self,
        question: str,
        top_k: Optional[int] = None,
        collection: Optional[str] = None,
        include_metadata: bool = True,
        similarity_threshold: Optional[float] = None
    ) -> QueryResult:
        """
        Query with answer generation (``POST /api/v1/query``).

        Args:
            question: The question to answer
            top_k: Number of context chunks to retrieve
            collection: Optional collection or pattern (e.g. ``docs/*``) to search
            include_metadata: If False, contexts come back without their metadata
            similarity_threshold: Minimum vector similarity (0 to 1) of a chunk to be used
                for the answer and returned

        Returns:
            QueryResult with answer and supporting contexts
        """
        data: Dict[str, Any] = {"question": question}
        if top_k:
            data["topK"] = top_k
        if collection:
            data["collection"] = collection
        if not include_metadata:
            data["includeMetadata"] = False
        if similarity_threshold is not None:
            data["overrides"] = {"similarityThreshold": similarity_threshold}

        response = self._request("POST", "/api/v1/query", json=data)
        result = response.json()

        usage = result.get("usage")

        return QueryResult(
            answer=result["answer"],
            contexts=[_context(ctx) for ctx in result.get("contexts", [])],
            prompt_tokens=usage.get("promptTokens") if usage else None,
            completion_tokens=usage.get("completionTokens") if usage else None,
            total_tokens=usage.get("totalTokens") if usage else None,
            query_id=result.get("queryId"),
        )

    def feedback(
        self,
        query_id: str,
        rating: int,
        comment: Optional[str] = None,
        relevant_chunks: Optional[Sequence[str]] = None,
    ) -> Dict[str, Any]:
        """
        Rate a query's answer (``POST /api/v1/feedback``).

        Args:
            query_id: The :attr:`QueryResult.query_id` of the query. Only the caller who asked
                it (or an administrator) may rate it; anyone else gets a 404 ``RagBoxError``.
            rating: 1 to 5
            comment: Optional free-text comment
            relevant_chunks: Optional ids of the chunks that were relevant

        Returns:
            ``{"success", "message"}``
        """
        data: Dict[str, Any] = {"queryId": query_id, "rating": rating}
        if comment is not None:
            data["comment"] = comment
        if relevant_chunks is not None:
            data["relevantChunks"] = list(relevant_chunks)
        return self._request("POST", "/api/v1/feedback", json=data).json()

    def search(
        self,
        query: str,
        top_k: Optional[int] = None,
        collection: Optional[str] = None
    ) -> SearchResult:
        """
        Search without LLM answer generation (``POST /api/v1/search``).

        Args:
            query: Search query
            top_k: Number of results to return
            collection: Optional collection or pattern to search

        Returns:
            SearchResult with matching contexts
        """
        data: Dict[str, Any] = {"query": query}
        if top_k:
            data["topK"] = top_k
        if collection:
            data["collection"] = collection

        response = self._request("POST", "/api/v1/search", json=data)
        result = response.json()

        contexts = [_context(ctx) for ctx in result.get("results", [])]
        return SearchResult(results=contexts, count=result.get("count", len(contexts)))

    # ============================================================
    # Sync Operations (administrators, or rat_ tokens with sync:read / sync:write)
    # ============================================================

    def sync(
        self,
        keep_ids: Optional[Sequence[str]] = None,
        dry_run: bool = False,
        source: Optional[str] = None,
        scope: Optional[str] = None,
    ) -> Dict[str, Any]:
        """
        Mark sync complete and optionally prune orphaned documents (``POST /api/v1/sync``).

        Pruning is scoped to one sync source: only that source's documents not in ``keep_ids``
        are deleted. Uploads and other sources' documents are never touched.

        Args:
            keep_ids: If provided, the source's documents not in this list are deleted
            dry_run: With keep_ids, only report what would be deleted
            source: The source to prune; defaults to the client's ``source`` (else ``api``)
            scope: ``"all"`` prunes every document not in ``keep_ids``, whatever its source
                (administrators only; not combined with a source)

        Returns:
            ``{"message", "prunedCount", "prunedIds"? (dry run), "dryRun", "scope", "source"}``
        """
        data = None
        if keep_ids is not None:
            data = {"keepDocumentIds": list(keep_ids)}
            if dry_run:
                data["dryRun"] = True
            if scope is not None:
                data["scope"] = scope
            if scope != "all" and (source or self.source):
                data["source"] = source or self.source

        response = self._request("POST", "/api/v1/sync", json=data)

        # Update local cache if pruning
        if keep_ids is not None and not dry_run:
            keep_set = set(keep_ids)
            self._pushed_docs = {
                k: v for k, v in self._pushed_docs.items()
                if k in keep_set
            }

        return response.json()

    def sync_status(self) -> SyncStatus:
        """Get current sync status (``GET /api/v1/sync/status``)."""
        response = self._request("GET", "/api/v1/sync/status")
        result = response.json()

        return SyncStatus(
            last_sync_time=_parse_time(result.get("lastSyncTime")),
            document_count=result["documentCount"],
            chunk_count=result["chunkCount"]
        )

    def synced_documents(
        self,
        include_hash: bool = False,
        include_updated_at: bool = False,
        since: Optional[Union[datetime, str]] = None,
    ) -> List[SyncedDocument]:
        """
        The server's record of every synced document (``GET /api/v1/sync/documents``).

        The endpoint is paged (``page``, ``pageSize``); this follows ``hasMore`` and returns
        every document the caller can see.

        Args:
            include_hash: Include each document's content hash
            include_updated_at: Include each document's last update time
            since: Only documents updated since this time (a datetime - naive means UTC - or an
                ISO-8601 instant string such as ``2026-01-01T00:00:00Z``)
        """
        params: Dict[str, str] = {}
        include = [name for name, wanted in (("hash", include_hash), ("updatedAt", include_updated_at)) if wanted]
        if include:
            params["include"] = ",".join(include)
        if since is not None:
            params["since"] = _format_instant(since)
        documents: List[SyncedDocument] = []
        page = 1
        while True:
            response = self._request("GET", "/api/v1/sync/documents", params={**params, "page": page})
            body = response.json()
            documents.extend(_synced(doc) for doc in body.get("documents", []))
            if not body.get("hasMore"):
                return documents
            page += 1

    def list_synced_documents(self, since: Optional[Union[datetime, str]] = None) -> List[str]:
        """List the IDs of synced documents (optionally only those updated since a time)."""
        return [doc.id for doc in self.synced_documents(since=since)]

    def get_synced_document(self, document_id: str) -> SyncedDocument:
        """One document's sync state, with hash and update time (``GET /api/v1/sync/documents/{id}``)."""
        response = self._request("GET", f"/api/v1/sync/documents/{quote(document_id, safe='')}")
        return _synced(response.json())

    def check_documents(self, document_ids: Sequence[str]) -> Dict[str, Any]:
        """
        Batch-check which documents exist (``POST /api/v1/sync/check``).

        Returns:
            ``{"found": [SyncedDocument, ...], "missing": [id, ...]}``
        """
        response = self._request("POST", "/api/v1/sync/check", json={"documentIds": list(document_ids)})
        result = response.json()
        return {
            "found": [_synced(doc) for doc in result.get("found", [])],
            "missing": result.get("missing", []),
        }

    # ============================================================
    # Health & Config Operations
    # ============================================================

    def health(self) -> Dict[str, Any]:
        """Check API health (``GET /health``, no authentication)."""
        response = self._request("GET", "/health")
        return response.json()

    def ready(self) -> Dict[str, Any]:
        """Check API readiness, including the database (``GET /health/ready``)."""
        response = self._request("GET", "/health/ready")
        return response.json()

    def stats(self) -> Dict[str, Any]:
        """Get RAG statistics (``GET /api/v1/stats``)."""
        response = self._request("GET", "/api/v1/stats")
        return response.json()

    def config(self) -> Dict[str, Any]:
        """Get current configuration (``GET /api/v1/config``)."""
        response = self._request("GET", "/api/v1/config")
        return response.json()

    def providers(self) -> Dict[str, Any]:
        """List available providers (``GET /api/v1/config/providers``)."""
        response = self._request("GET", "/api/v1/config/providers")
        return response.json()

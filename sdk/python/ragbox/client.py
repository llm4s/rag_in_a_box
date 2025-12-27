"""
RAG in a Box Python Client

Provides a simple interface for document ingestion and querying.
Supports incremental sync with content hash tracking.
"""

import hashlib
import json
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, List, Optional, Sequence

import requests


@dataclass
class Document:
    """A document to be ingested into RAG in a Box."""
    id: str
    content: str
    metadata: Dict[str, str] = field(default_factory=dict)

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
    pending_deletes: int


class RagBoxClient:
    """
    Client for RAG in a Box API.

    Provides methods for document ingestion, querying, and sync operations.
    Tracks document state locally for efficient incremental updates.

    Example:
        client = RagBoxClient("http://localhost:8080")

        # Upsert documents (idempotent)
        for doc in my_documents:
            result = client.upsert(doc)
            print(f"{doc.id}: {result.action}")

        # Query with answer generation
        result = client.query("What is PostgreSQL?")
        print(result.answer)

        # Mark sync complete and prune orphaned documents
        client.sync(keep_ids=[doc.id for doc in my_documents])
    """

    def __init__(self, base_url: str = "http://localhost:8080", timeout: int = 30):
        """
        Initialize the RAG in a Box client.

        Args:
            base_url: Base URL of the RAG in a Box API
            timeout: Request timeout in seconds
        """
        self.base_url = base_url.rstrip('/')
        self.timeout = timeout
        self._session = requests.Session()

        # Local state for tracking what we've pushed
        self._pushed_docs: Dict[str, str] = {}  # doc_id -> content_hash

    def _url(self, path: str) -> str:
        """Build full URL for API path."""
        return f"{self.base_url}{path}"

    def _request(self, method: str, path: str, **kwargs) -> requests.Response:
        """Make an HTTP request with error handling."""
        kwargs.setdefault('timeout', self.timeout)
        response = self._session.request(method, self._url(path), **kwargs)
        response.raise_for_status()
        return response

    # ============================================================
    # Document Operations
    # ============================================================

    def upload(
        self,
        content: str,
        filename: str,
        metadata: Optional[Dict[str, str]] = None,
        collection: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Upload a new document (generates UUID for document ID).

        For idempotent updates, use upsert() instead.

        Args:
            content: Document text content
            filename: Filename for the document
            metadata: Optional metadata key-value pairs
            collection: Optional collection name

        Returns:
            Upload response with documentId and chunk count
        """
        data = {
            "content": content,
            "filename": filename,
        }
        if metadata:
            data["metadata"] = metadata
        if collection:
            data["collection"] = collection

        response = self._request("POST", "/api/v1/documents", json=data)
        return response.json()

    def upsert(self, doc: Document) -> UpsertResult:
        """
        Upsert a document (idempotent create/update).

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

        data = {
            "content": doc.content,
            "contentHash": content_hash,
        }
        if doc.metadata:
            data["metadata"] = doc.metadata

        response = self._request("PUT", f"/api/v1/documents/{doc.id}", json=data)
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
        """
        Upsert multiple documents.

        Args:
            docs: Documents to upsert

        Returns:
            List of UpsertResult for each document
        """
        return [self.upsert(doc) for doc in docs]

    def delete(self, document_id: str) -> None:
        """
        Delete a document by ID.

        Args:
            document_id: ID of the document to delete
        """
        self._request("DELETE", f"/api/v1/documents/{document_id}")
        self._pushed_docs.pop(document_id, None)

    def list_documents(self) -> Dict[str, Any]:
        """
        List all documents.

        Returns:
            Document list with total count
        """
        response = self._request("GET", "/api/v1/documents")
        return response.json()

    def clear_all(self) -> None:
        """Delete all documents."""
        self._request("DELETE", "/api/v1/documents")
        self._pushed_docs.clear()

    # ============================================================
    # Query Operations
    # ============================================================

    def query(
        self,
        question: str,
        top_k: Optional[int] = None,
        collection: Optional[str] = None
    ) -> QueryResult:
        """
        Query with answer generation.

        Args:
            question: The question to answer
            top_k: Number of context chunks to retrieve
            collection: Optional collection to search

        Returns:
            QueryResult with answer and supporting contexts
        """
        data: Dict[str, Any] = {"question": question}
        if top_k:
            data["topK"] = top_k
        if collection:
            data["collection"] = collection

        response = self._request("POST", "/api/v1/query", json=data)
        result = response.json()

        contexts = [
            ContextItem(
                content=ctx["content"],
                score=ctx["score"],
                metadata=ctx.get("metadata", {}),
                document_id=ctx.get("documentId"),
                chunk_index=ctx.get("chunkIndex")
            )
            for ctx in result.get("contexts", [])
        ]

        usage = result.get("usage")

        return QueryResult(
            answer=result["answer"],
            contexts=contexts,
            prompt_tokens=usage.get("promptTokens") if usage else None,
            completion_tokens=usage.get("completionTokens") if usage else None,
            total_tokens=usage.get("totalTokens") if usage else None
        )

    def search(
        self,
        query: str,
        top_k: Optional[int] = None,
        collection: Optional[str] = None
    ) -> SearchResult:
        """
        Search without LLM answer generation.

        Args:
            query: Search query
            top_k: Number of results to return
            collection: Optional collection to search

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

        contexts = [
            ContextItem(
                content=ctx["content"],
                score=ctx["score"],
                metadata=ctx.get("metadata", {}),
                document_id=ctx.get("documentId"),
                chunk_index=ctx.get("chunkIndex")
            )
            for ctx in result.get("results", [])
        ]

        return SearchResult(
            results=contexts,
            count=result.get("count", len(contexts))
        )

    # ============================================================
    # Sync Operations
    # ============================================================

    def sync(self, keep_ids: Optional[Sequence[str]] = None) -> Dict[str, Any]:
        """
        Mark sync complete and optionally prune orphaned documents.

        Args:
            keep_ids: If provided, documents not in this list will be deleted

        Returns:
            Sync result with pruned count
        """
        data = None
        if keep_ids is not None:
            data = {"keepDocumentIds": list(keep_ids)}

        response = self._request("POST", "/api/v1/sync", json=data)

        # Update local cache if pruning
        if keep_ids is not None:
            keep_set = set(keep_ids)
            self._pushed_docs = {
                k: v for k, v in self._pushed_docs.items()
                if k in keep_set
            }

        return response.json()

    def sync_status(self) -> SyncStatus:
        """
        Get current sync status.

        Returns:
            SyncStatus with document counts and last sync time
        """
        response = self._request("GET", "/api/v1/sync/status")
        result = response.json()

        last_sync = None
        if result.get("lastSyncTime"):
            last_sync = datetime.fromisoformat(
                result["lastSyncTime"].replace("Z", "+00:00")
            )

        return SyncStatus(
            last_sync_time=last_sync,
            document_count=result["documentCount"],
            chunk_count=result["chunkCount"],
            pending_deletes=result.get("pendingDeletes", 0)
        )

    def list_synced_documents(self) -> List[str]:
        """
        List document IDs that have been synced.

        Returns:
            List of document IDs
        """
        response = self._request("GET", "/api/v1/sync/documents")
        return response.json().get("documentIds", [])

    # ============================================================
    # Health & Config Operations
    # ============================================================

    def health(self) -> Dict[str, Any]:
        """Check API health."""
        response = self._request("GET", "/health")
        return response.json()

    def ready(self) -> Dict[str, Any]:
        """Check API readiness (including database)."""
        response = self._request("GET", "/health/ready")
        return response.json()

    def stats(self) -> Dict[str, Any]:
        """Get RAG statistics."""
        response = self._request("GET", "/api/v1/stats")
        return response.json()

    def config(self) -> Dict[str, Any]:
        """Get current configuration."""
        response = self._request("GET", "/api/v1/config")
        return response.json()

    def providers(self) -> Dict[str, Any]:
        """List available providers."""
        response = self._request("GET", "/api/v1/config/providers")
        return response.json()

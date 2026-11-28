"""
RAG in a Box Python SDK

A simple client library for interacting with RAG in a Box API.
Supports incremental document ingestion with change detection.
"""

from .client import (
    API_KEY_ENV,
    ChatMessage,
    ChatReply,
    ChatSession,
    ChatSessionDetail,
    ChatSessionPage,
    ContextItem,
    Document,
    QueryResult,
    RagBoxClient,
    RagBoxError,
    SearchResult,
    StreamEvent,
    SyncedDocument,
    SyncStatus,
    UpsertResult,
)

__version__ = "0.2.0"
__all__ = [
    "API_KEY_ENV",
    "ChatMessage",
    "ChatReply",
    "ChatSession",
    "ChatSessionDetail",
    "ChatSessionPage",
    "ContextItem",
    "Document",
    "QueryResult",
    "RagBoxClient",
    "RagBoxError",
    "SearchResult",
    "StreamEvent",
    "SyncedDocument",
    "SyncStatus",
    "UpsertResult",
]

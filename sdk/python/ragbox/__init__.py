"""
RAG in a Box Python SDK

A simple client library for interacting with RAG in a Box API.
Supports incremental document ingestion with change detection.
"""

from .client import RagBoxClient, Document, QueryResult, SearchResult

__version__ = "0.1.0"
__all__ = ["RagBoxClient", "Document", "QueryResult", "SearchResult"]

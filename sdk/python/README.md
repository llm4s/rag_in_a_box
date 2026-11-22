# RAG in a Box Python Client

A Python SDK for interacting with [RAG in a Box](https://github.com/llm4s/rag_in_a_box) API.

## Installation

```bash
pip install ragbox-client
```

Or install from source:

```bash
cd sdk/python
pip install -e .
```

## Quick Start

```python
from ragbox import RagBoxClient, Document

# Connect to RAG in a Box (api_key may be omitted in open mode without API_KEY)
client = RagBoxClient("http://localhost:8080", api_key="rat_...")

# Upload a document
result = client.upload(
    content="PostgreSQL is a powerful open-source database...",
    filename="postgres-intro.txt",
    metadata={"source": "documentation"}
)
print(f"Uploaded: {result['documentId']}, {result['chunks']} chunks")

# Query with answer generation
result = client.query("What is PostgreSQL?")
print(f"Answer: {result.answer}")
print(f"Sources: {len(result.contexts)} contexts")
```

## Authentication

Pass a credential as `api_key`; it is sent as `Authorization: Bearer <api_key>`. Any
credential the server accepts in that header works:

- the server's static `API_KEY` (administrator),
- a `rat_` access token created in the admin UI or with `POST /api/v1/tokens` - the usual
  choice for ingesters; give it the scopes it needs (`documents:write`, `sync:read`,
  `sync:write`, `query`, ...), optionally restricted to collections,
- a JWT from `POST /api/v1/auth/login` (basic mode only).

If `api_key` is not given, the client reads the `RAGBOX_API_KEY` environment variable. With
neither, requests are sent unauthenticated, which only works in open mode without `API_KEY`.

```python
import os
client = RagBoxClient("https://rag.example.com", api_key=os.environ["RAGBOX_TOKEN"])
```

Error responses raise `ragbox.RagBoxError` (a `requests.HTTPError`) carrying the server's
`status_code`, `error`, `message` and `details`:

```python
from ragbox import RagBoxError

try:
    client.clear_all()
except RagBoxError as e:
    print(e.status_code, e.error, e.message)  # e.g. 403 forbidden Admin access required
```

## Uploading Files

`upload_file` sends a file to `POST /api/v1/documents/upload`; the server extracts the text
(PDF, DOCX, HTML, Markdown, plain text - anything Apache Tika reads):

```python
result = client.upload_file("handbook.pdf", collection="hr", metadata={"source": "intranet"})
print(result["documentId"], result["chunks"])
```

## Incremental Ingestion

The SDK supports efficient incremental ingestion using content hash tracking:

```python
from ragbox import RagBoxClient, Document

client = RagBoxClient("http://localhost:8080")

# Create documents
docs = [
    Document(id="doc-1", content="First document content"),
    Document(id="doc-2", content="Second document content"),
]

# Upsert documents (idempotent)
for doc in docs:
    result = client.upsert(doc)
    print(f"{doc.id}: {result.action}")  # "created", "updated", or "unchanged"

# On subsequent runs, unchanged documents are skipped
for doc in docs:
    result = client.upsert(doc)
    print(f"{doc.id}: {result.action}")  # "unchanged" for all
```

## Sync and Prune

After upserting all documents, you can prune orphaned documents:

```python
# Upsert your current documents
doc_ids = []
for doc in my_documents:
    result = client.upsert(doc)
    doc_ids.append(doc.id)

# Preview, then prune documents that are no longer in your source
# (deletes every document not in keep_ids, within the token's collections)
preview = client.sync(keep_ids=doc_ids, dry_run=True)
print(f"Would prune: {preview['prunedIds']}")
result = client.sync(keep_ids=doc_ids)
print(f"Pruned {result['prunedCount']} orphaned documents")
```

The sync endpoints are for ingesters: administrators, or `rat_` tokens with the `sync:read` /
`sync:write` scopes.

## API Reference

### RagBoxClient

```python
client = RagBoxClient(base_url="http://localhost:8080", timeout=30, api_key=None, session=None)
```

#### Document Operations

| Method | Endpoint | Description |
|--------|----------|-------------|
| `upload(content, filename, metadata, collection, readable_by)` | `POST /api/v1/documents` | Add a text document (server-assigned ID) |
| `upload_file(file, filename, metadata, collection, readable_by)` | `POST /api/v1/documents/upload` | Upload a file (multipart) |
| `upsert(doc: Document)` | `PUT /api/v1/documents/{id}` | Idempotent create/update |
| `upsert_batch(docs)` | `PUT /api/v1/documents/{id}` | Upsert multiple documents |
| `get_document(document_id)` | `GET /api/v1/documents/{id}` | A document's metadata |
| `delete(document_id)` | `DELETE /api/v1/documents/{id}` | Delete a document |
| `delete_batch(document_ids, dry_run)` | `DELETE /api/v1/documents` | Delete several documents |
| `list_documents(page, page_size, collection, search)` | `GET /api/v1/documents` | One page of documents (`pageSize` default 50, max 500) |
| `iter_documents(page_size, collection, search)` | `GET /api/v1/documents` | Every document, following pages |
| `collections()` | `GET /api/v1/collections` | Collection names |
| `clear_all()` | `DELETE /api/v1/documents?all=true` | Delete all documents (admin only) |

#### Query Operations

| Method | Endpoint | Description |
|--------|----------|-------------|
| `query(question, top_k, collection, include_metadata, similarity_threshold)` | `POST /api/v1/query` | Query with answer generation |
| `search(query, top_k, collection)` | `POST /api/v1/search` | Search without LLM |

#### Sync Operations

| Method | Endpoint | Description |
|--------|----------|-------------|
| `sync(keep_ids, dry_run)` | `POST /api/v1/sync` | Mark sync complete, optionally prune |
| `sync_status()` | `GET /api/v1/sync/status` | Get sync status |
| `synced_documents(include_hash, include_updated_at, since)` | `GET /api/v1/sync/documents` | Synced documents as `SyncedDocument` (follows the pages) |
| `list_synced_documents(since)` | `GET /api/v1/sync/documents` | Synced document IDs |
| `get_synced_document(document_id)` | `GET /api/v1/sync/documents/{id}` | One document's hash and update time |
| `check_documents(document_ids)` | `POST /api/v1/sync/check` | Which of these IDs exist (`found` / `missing`) |

#### Health & Config Operations

| Method | Endpoint | Description |
|--------|----------|-------------|
| `health()` | `GET /health` | Basic health check (no auth) |
| `ready()` | `GET /health/ready` | Readiness check (includes DB) |
| `stats()` | `GET /api/v1/stats` | Get RAG statistics |
| `config()` | `GET /api/v1/config` | Get current configuration |
| `providers()` | `GET /api/v1/config/providers` | List available providers |

### Document

```python
doc = Document(
    id="unique-id",
    content="Document text content",
    metadata={"key": "value"},   # string values
    collection="docs",           # optional; default collection if omitted
    readable_by=["alice"],       # optional; permission mode only
)
```

### QueryResult

```python
@dataclass
class QueryResult:
    answer: str
    contexts: List[ContextItem]
    prompt_tokens: Optional[int]
    completion_tokens: Optional[int]
    total_tokens: Optional[int]
```

### SearchResult

```python
@dataclass
class SearchResult:
    results: List[ContextItem]
    count: int
```

## Example: Custom Ingester

Build a custom ingester for any data source:

```python
from ragbox import RagBoxClient, Document
import my_custom_source

client = RagBoxClient("http://localhost:8080")  # api_key from $RAGBOX_API_KEY

# Ingest from custom source
doc_ids = []
for item in my_custom_source.fetch_all():
    doc = Document(
        id=item.id,
        content=item.text,
        metadata={
            "source": "custom",
            "updated": item.updated_at.isoformat()
        }
    )
    result = client.upsert(doc)
    doc_ids.append(doc.id)

    if result.action != "unchanged":
        print(f"Indexed {doc.id}: {result.action}")

# Prune deleted documents
client.sync(keep_ids=doc_ids)
print("Sync complete!")
```

## Development

```bash
cd sdk/python
pip install -e ".[dev]"
pytest
```

The tests mock the HTTP API with the `responses` library; CI runs them in the "Python SDK" job.

## License

MIT License

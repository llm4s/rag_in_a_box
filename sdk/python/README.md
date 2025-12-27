# RAG in a Box Python Client

A Python SDK for interacting with [RAG in a Box](https://github.com/llm4s/rag-in-a-box) API.

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

# Connect to RAG in a Box
client = RagBoxClient("http://localhost:8080")

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

# Prune documents that are no longer in your source
# This deletes any documents not in keep_ids
result = client.sync(keep_ids=doc_ids)
print(f"Pruned {result['prunedCount']} orphaned documents")
```

## API Reference

### RagBoxClient

```python
client = RagBoxClient(base_url="http://localhost:8080", timeout=30)
```

#### Document Operations

| Method | Description |
|--------|-------------|
| `upload(content, filename, metadata, collection)` | Upload a new document |
| `upsert(doc: Document)` | Idempotent create/update |
| `upsert_batch(docs)` | Upsert multiple documents |
| `delete(document_id)` | Delete a document |
| `list_documents()` | List all documents |
| `clear_all()` | Delete all documents |

#### Query Operations

| Method | Description |
|--------|-------------|
| `query(question, top_k, collection)` | Query with answer generation |
| `search(query, top_k, collection)` | Search without LLM |

#### Sync Operations

| Method | Description |
|--------|-------------|
| `sync(keep_ids)` | Mark sync complete, optionally prune |
| `sync_status()` | Get sync status |
| `list_synced_documents()` | List synced document IDs |

#### Health Operations

| Method | Description |
|--------|-------------|
| `health()` | Basic health check |
| `ready()` | Readiness check (includes DB) |
| `stats()` | Get RAG statistics |
| `config()` | Get current configuration |
| `providers()` | List available providers |

### Document

```python
doc = Document(
    id="unique-id",
    content="Document text content",
    metadata={"key": "value"}
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

client = RagBoxClient("http://localhost:8080")

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

## License

MIT License

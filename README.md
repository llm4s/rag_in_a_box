# RAG in a Box

A turnkey RAG (Retrieval Augmented Generation) deployment powered by [llm4s](https://github.com/llm4s/llm4s).

**Features:**
- REST API for document ingestion and querying
- PostgreSQL + pgvector for scalable vector storage
- **Persistent document registry** - survives restarts, tracks content changes
- Multiple embedding providers (OpenAI, VoyageAI, Ollama)
- Multiple LLM providers for answer generation (OpenAI, Anthropic)
- Hybrid search (vector + keyword) with RRF fusion
- Incremental ingestion with content hash change detection
- Built-in connectors (directory, URL) with scheduled sync
- Python SDK for custom ingesters
- Docker Compose for easy deployment

## Quick Start

### Prerequisites
- Docker and Docker Compose
- An API key for OpenAI (or Anthropic)

### Run with Docker Compose

```bash
# Clone the repository
git clone https://github.com/llm4s/rag-in-a-box.git
cd rag-in-a-box

# Copy and configure environment
cp .env.example .env
# Edit .env and add your OPENAI_API_KEY

# Start all services
docker-compose up -d

# Check health
curl http://localhost:8080/health
```

### Local Development

```bash
# Start PostgreSQL with pgvector
docker run -d --name ragbox-postgres \
  -e POSTGRES_USER=rag \
  -e POSTGRES_PASSWORD=rag \
  -e POSTGRES_DB=ragdb \
  -v $(pwd)/scripts/init-db.sql:/docker-entrypoint-initdb.d/01-init.sql \
  -p 15432:5432 \
  pgvector/pgvector:pg15

# Configure environment
cp .env.example .env
# Edit .env and add your OPENAI_API_KEY

# Run the application
./run.sh
```

### Access Points
- **API**: http://localhost:8080
- **PostgreSQL**: localhost:15432 (user: rag, password: rag)

## API Usage

### Upload a Document

```bash
curl -X POST http://localhost:8080/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{
    "content": "PostgreSQL is a powerful open-source relational database...",
    "filename": "postgres-intro.txt",
    "metadata": {"source": "documentation"}
  }'
```

### Query with Answer Generation

```bash
curl -X POST http://localhost:8080/api/v1/query \
  -H "Content-Type: application/json" \
  -d '{
    "question": "What is PostgreSQL?",
    "topK": 5
  }'
```

### Search Only (No LLM)

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "database features",
    "topK": 10
  }'
```

### Get Statistics

```bash
curl http://localhost:8080/api/v1/stats
```

## Incremental Ingestion (SDK Pattern)

RAG in a Box supports idempotent document ingestion for custom ingesters:

### Upsert a Document

```bash
# First upsert - creates document
curl -X PUT http://localhost:8080/api/v1/documents/my-doc-1 \
  -H "Content-Type: application/json" \
  -d '{"content": "Document content here...", "metadata": {"source": "api"}}'
# Returns: {"action": "created", "chunks": 3, ...}

# Same content - skips re-indexing
curl -X PUT http://localhost:8080/api/v1/documents/my-doc-1 \
  -H "Content-Type: application/json" \
  -d '{"content": "Document content here...", "metadata": {"source": "api"}}'
# Returns: {"action": "unchanged", ...}

# Updated content - re-indexes
curl -X PUT http://localhost:8080/api/v1/documents/my-doc-1 \
  -H "Content-Type: application/json" \
  -d '{"content": "Updated document content!", "metadata": {"source": "api"}}'
# Returns: {"action": "updated", "chunks": 2, ...}
```

### Sync and Prune

After upserting documents, prune orphaned ones:

```bash
# Get sync status
curl http://localhost:8080/api/v1/sync/status

# List synced document IDs
curl http://localhost:8080/api/v1/sync/documents

# Complete sync and prune documents not in keep list
curl -X POST http://localhost:8080/api/v1/sync \
  -H "Content-Type: application/json" \
  -d '{"keepDocumentIds": ["my-doc-1", "my-doc-2"]}'
```

### Health Checks

```bash
# Basic health
curl http://localhost:8080/health

# Readiness (includes database check)
curl http://localhost:8080/health/ready
```

## Configuration

All settings can be configured via environment variables:

### Server Settings
| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_HOST` | `0.0.0.0` | Server bind address |
| `SERVER_PORT` | `8080` | Server port |

### Database Settings
| Variable | Default | Description |
|----------|---------|-------------|
| `PG_HOST` | `localhost` | PostgreSQL host |
| `PG_PORT` | `5432` | PostgreSQL port |
| `PG_DATABASE` | `ragdb` | Database name |
| `PG_USER` | `rag` | Database user |
| `PG_PASSWORD` | `rag` | Database password |
| `DATABASE_URL` | - | Alternative: full connection URL |

### API Keys
| Variable | Required | Description |
|----------|----------|-------------|
| `OPENAI_API_KEY` | For OpenAI | OpenAI API key |
| `ANTHROPIC_API_KEY` | For Anthropic | Anthropic API key |
| `VOYAGE_API_KEY` | For VoyageAI | VoyageAI API key |

### Embedding Settings
| Variable | Default | Description |
|----------|---------|-------------|
| `EMBEDDING_PROVIDER` | `openai` | Provider: openai, voyage, ollama |
| `EMBEDDING_MODEL` | `text-embedding-3-small` | Embedding model |

### LLM Settings
| Variable | Default | Description |
|----------|---------|-------------|
| `LLM_MODEL` | `openai/gpt-4o` | Model for answer generation |
| `LLM_TEMPERATURE` | `0.1` | Temperature for responses |

### RAG Settings
| Variable | Default | Description |
|----------|---------|-------------|
| `RAG_CHUNKING_STRATEGY` | `sentence` | Strategy: simple, sentence, markdown, semantic |
| `RAG_CHUNK_SIZE` | `800` | Target chunk size (characters) |
| `RAG_CHUNK_OVERLAP` | `150` | Overlap between chunks |
| `RAG_TOP_K` | `5` | Number of context chunks |
| `RAG_FUSION_STRATEGY` | `rrf` | Fusion: rrf, weighted, vector_only, keyword_only |

## API Reference

### Documents

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/documents` | Upload a document |
| PUT | `/api/v1/documents/{id}` | Upsert a document (idempotent) |
| GET | `/api/v1/documents` | List documents |
| DELETE | `/api/v1/documents` | Clear all documents |
| DELETE | `/api/v1/documents/{id}` | Delete a document |

### Sync

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/sync/status` | Get sync status |
| GET | `/api/v1/sync/documents` | List synced document IDs |
| POST | `/api/v1/sync` | Mark sync complete (optionally prune) |

### Query

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/query` | Query with answer generation |
| POST | `/api/v1/search` | Search without LLM |

### Ingestion

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/ingest/directory` | Ingest from directory |
| POST | `/api/v1/ingest/url` | Ingest from URLs |
| POST | `/api/v1/ingest/run` | Run all configured sources |
| POST | `/api/v1/ingest/run/{source}` | Run specific source |
| GET | `/api/v1/ingest/status` | Get ingestion status |
| GET | `/api/v1/ingest/sources` | List configured sources |

### Configuration

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/config` | Get current configuration |
| GET | `/api/v1/config/providers` | List available providers |
| GET | `/api/v1/stats` | Get RAG statistics |

### Health

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/health` | Basic health check |
| GET | `/health/ready` | Readiness check |
| GET | `/health/live` | Liveness check |

## Built-in Ingestion (Tier 1)

RAG in a Box includes built-in connectors for common data sources:

### Directory Ingestion

Ingest all documents from a directory:

```bash
curl -X POST http://localhost:8080/api/v1/ingest/directory \
  -H "Content-Type: application/json" \
  -d '{
    "path": "/data/docs",
    "patterns": ["*.md", "*.txt", "*.pdf"],
    "recursive": true
  }'
```

### URL Ingestion

Ingest documents from URLs:

```bash
curl -X POST http://localhost:8080/api/v1/ingest/url \
  -H "Content-Type: application/json" \
  -d '{"urls": ["https://example.com/doc1.html", "https://example.com/doc2.html"]}'
```

### Configure Automatic Ingestion

Set environment variables for automatic directory ingestion:

```bash
export INGEST_DIR=/data/docs
export INGEST_PATTERNS="*.md,*.txt,*.pdf"
export INGEST_RECURSIVE=true
export INGEST_ON_STARTUP=true
export INGEST_SCHEDULE="6h"  # Run every 6 hours

./run.sh
```

Or configure in `application.conf`:

```hocon
ingestion {
  enabled = true
  run-on-startup = true
  schedule = "6h"  # Options: "5m", "1h", "6h", "daily", "hourly"

  sources = [
    {
      type = "directory"
      name = "docs"
      path = "/data/docs"
      patterns = ["*.md", "*.txt", "*.pdf"]
      recursive = true
    }
  ]
}
```

### Schedule Options

| Format | Description |
|--------|-------------|
| `5m`, `30m` | Every N minutes |
| `1h`, `6h`, `12h` | Every N hours |
| `1d`, `7d` | Every N days |
| `hourly` | Every hour |
| `daily` | Once per day |
| `weekly` | Once per week |
| `0 * * * *` | Cron: every hour |
| `0 */6 * * *` | Cron: every 6 hours |

### Ingestion Status

```bash
# Check ingestion status
curl http://localhost:8080/api/v1/ingest/status

# List configured sources
curl http://localhost:8080/api/v1/ingest/sources

# Run all configured sources
curl -X POST http://localhost:8080/api/v1/ingest/run
```

## Python SDK

A Python client is available for easy integration:

```bash
cd sdk/python
pip install -e .
```

### Usage

```python
from ragbox import RagBoxClient, Document

client = RagBoxClient("http://localhost:8080")

# Upsert documents (idempotent)
docs = [
    Document(id="doc-1", content="First document"),
    Document(id="doc-2", content="Second document"),
]

for doc in docs:
    result = client.upsert(doc)
    print(f"{doc.id}: {result.action}")

# Query with answer
result = client.query("What is in the documents?")
print(result.answer)

# Prune deleted documents
client.sync(keep_ids=[doc.id for doc in docs])
```

See [sdk/python/README.md](sdk/python/README.md) for full documentation.

## Persistence

RAG in a Box uses PostgreSQL-backed document registry for durability:

- **Document tracking persists across restarts** - No re-indexing needed after restart
- **Content hash detection** - Only changed documents are re-indexed
- **Sync status preserved** - Last sync time and document list survive restarts

The document registry stores:
- Document IDs and content hashes (SHA-256)
- Chunk counts and metadata
- Indexed and updated timestamps

This enables efficient incremental ingestion workflows where only new or modified documents are processed.

## Development

### Build Fat JAR

```bash
sbt assembly
java -jar target/scala-3.7.1/ragbox-assembly.jar
```

### Running Tests

```bash
sbt test
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                          Docker Compose                              │
├─────────────────────────────────────────────────────────────────────┤
│  ┌────────────────────────┐  ┌────────────────────────────────────┐ │
│  │    RAG API Service     │  │     PostgreSQL + pgvector          │ │
│  │    (Scala + http4s)    │  │                                    │ │
│  │                        │  │  ┌─────────────────────────────┐   │ │
│  │  • Document upload     │◀─│  │ rag_embeddings (vectors)    │   │ │
│  │  • Upsert (idempotent) │  │  │ document_registry (tracking)│   │ │
│  │  • Query + Answer      │  │  │ sync_status (sync state)    │   │ │
│  │  • Incremental sync    │  │  └─────────────────────────────┘   │ │
│  │      Port 8080         │  │        Port 5432                   │ │
│  └────────────────────────┘  └────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
          │                              │
          ▼ External Services            ▼ Custom Ingesters
   ┌──────┴──────┐                ┌──────┴──────┐
   │   LLM API   │                │ Python SDK  │
   │  (OpenAI,   │                │ REST API    │
   │  Anthropic) │                │ Scheduled   │
   └─────────────┘                └─────────────┘
```

## License

MIT License - see [LICENSE](LICENSE) for details.

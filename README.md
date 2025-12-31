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
- **Visibility API** - inspect chunks, view config, understand RAG behavior
- **Chunking Preview** - test chunking strategies before committing
- **Runtime Configuration** - modify settings without server restart
- **Per-Collection Chunking** - different chunking strategies per collection with file-type overrides
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
- **Admin UI**: http://localhost:8080/admin
- **PostgreSQL**: localhost:15432 (user: rag, password: rag)

## Admin UI

RAG in a Box includes a built-in web-based admin interface for managing documents, configuration, and monitoring.

### Features

- **Dashboard** - Overview of document counts, chunk statistics, and system health
- **Documents** - Browse, search, upload, and delete documents
- **Upload** - Add documents via text, file upload, or URL ingestion
- **Configuration** - View and modify runtime settings
- **Collections** - Manage per-collection chunking configurations
- **Chunking Preview** - Test and compare chunking strategies before applying
- **Visibility** - Inspect chunks and understand RAG behavior
- **Ingestion** - Monitor and trigger ingestion jobs

### Accessing the Admin UI

The Admin UI is bundled with the API server and available at `/admin`:

```bash
# Start the server
docker-compose up -d

# Open in browser
open http://localhost:8080/admin
```

### Development Mode

For frontend development with hot reload:

```bash
cd admin-ui
npm install
npm run dev
```

This starts a development server at http://localhost:3000 that proxies API requests to the backend.

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

### Visibility

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/visibility/config` | Detailed config with changeability annotations |
| GET | `/api/v1/visibility/chunks` | List all chunks (paginated) |
| GET | `/api/v1/visibility/chunks/{docId}` | Get all chunks for a document |
| GET | `/api/v1/visibility/chunks/{docId}/{idx}` | Get specific chunk |
| GET | `/api/v1/visibility/stats` | Detailed stats with chunk size distribution |
| GET | `/api/v1/visibility/collections` | Collection details with chunking info |

### Chunking Preview

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/chunking/preview` | Preview chunking on sample text |
| POST | `/api/v1/chunking/compare` | Compare multiple strategies |
| GET | `/api/v1/chunking/strategies` | List available strategies |
| GET | `/api/v1/chunking/presets` | Get preset configurations |

### Runtime Configuration

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/config/runtime` | Get current runtime settings |
| PUT | `/api/v1/config/runtime` | Update runtime settings |
| POST | `/api/v1/config/runtime/validate` | Validate proposed changes |
| GET | `/api/v1/config/runtime/history` | Get config change history |

### Collection Configuration

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/collections/{name}/config` | Get collection chunking config |
| PUT | `/api/v1/collections/{name}/config` | Set collection chunking config |
| DELETE | `/api/v1/collections/{name}/config` | Remove custom config (use defaults) |
| GET | `/api/v1/collections/configs` | List all collection configs |
| POST | `/api/v1/collections/{name}/config/preview` | Preview effective config for a file |

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

## Runtime Configuration API

The Runtime Configuration API allows you to modify settings without restarting the server. Settings are classified as:

- **Hot**: Changes take effect immediately (topK, fusionStrategy, systemPrompt)
- **Warm**: Changes affect new documents only (chunkingStrategy, chunkSize)

### View Current Settings

```bash
curl http://localhost:8080/api/v1/config/runtime
```

### Update Settings

```bash
curl -X PUT http://localhost:8080/api/v1/config/runtime \
  -H "Content-Type: application/json" \
  -d '{"topK": 10, "fusionStrategy": "weighted"}'
```

### Validate Before Applying

```bash
curl -X POST http://localhost:8080/api/v1/config/runtime/validate \
  -H "Content-Type: application/json" \
  -d '{"topK": 10, "chunkSize": 500}'
```

### View Change History

```bash
curl http://localhost:8080/api/v1/config/runtime/history
```

## Per-Collection Chunking API

The Per-Collection Chunking API allows different collections to use different chunking configurations. This is useful when you have documents with different characteristics in different collections.

### Configuration Resolution Order

When determining the effective configuration for a file:
1. **File-type override** - If the collection config has a file-type strategy for the file extension
2. **Collection config** - The collection's custom settings
3. **Runtime defaults** - The global default settings

### View Collection Config

```bash
curl http://localhost:8080/api/v1/collections/my-collection/config
```

Response shows both the custom config (if any) and the effective config:
```json
{
  "collection": "my-collection",
  "hasCustomConfig": true,
  "config": {
    "strategy": "markdown",
    "targetSize": 1000,
    "fileTypeStrategies": {".md": "markdown", ".txt": "sentence"}
  },
  "effectiveConfig": {
    "strategy": "markdown",
    "targetSize": 1000,
    "maxSize": 1600,
    "overlap": 150,
    "source": "collection"
  },
  "documentCount": 25
}
```

### Set Collection Config

```bash
curl -X PUT http://localhost:8080/api/v1/collections/my-collection/config \
  -H "Content-Type: application/json" \
  -d '{
    "strategy": "markdown",
    "targetSize": 1000,
    "fileTypeStrategies": {
      ".md": "markdown",
      ".txt": "sentence"
    }
  }'
```

### Preview Effective Config for a File

Test which settings will apply for a specific file:

```bash
curl -X POST http://localhost:8080/api/v1/collections/my-collection/config/preview \
  -H "Content-Type: application/json" \
  -d '{"collection": "my-collection", "filename": "README.md"}'
```

Response includes the resolution path showing how the config was determined:
```json
{
  "collection": "my-collection",
  "filename": "README.md",
  "effectiveConfig": {
    "strategy": "markdown",
    "targetSize": 1000,
    "source": "file-type",
    "appliedFileTypeOverride": ".md"
  },
  "configResolutionPath": [
    "Checked file extension: .md",
    "Found file-type override in collection 'my-collection' config",
    "Using strategy: markdown"
  ]
}
```

### List All Collection Configs

```bash
curl http://localhost:8080/api/v1/collections/configs
```

### Remove Custom Config

```bash
curl -X DELETE http://localhost:8080/api/v1/collections/my-collection/config
```

## Chunking Preview API

The Chunking Preview API lets you test chunking strategies before committing to them. This is essential for tuning RAG performance.

### Preview Chunking

Test how content will be chunked with current or custom settings:

```bash
curl -X POST http://localhost:8080/api/v1/chunking/preview \
  -H "Content-Type: application/json" \
  -d '{
    "content": "# My Document\n\nThis is sample content to test chunking...",
    "strategy": "markdown",
    "targetSize": 500
  }'
```

### Compare Strategies

Compare multiple chunking strategies on the same content:

```bash
curl -X POST http://localhost:8080/api/v1/chunking/compare \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Your sample content here...",
    "strategies": ["simple", "sentence", "markdown"]
  }'
```

The response includes a recommendation based on content analysis.

### List Strategies

View available strategies with their descriptions and trade-offs:

```bash
curl http://localhost:8080/api/v1/chunking/strategies
```

### View Presets

Get preset configurations for different use cases:

```bash
curl http://localhost:8080/api/v1/chunking/presets
```

## Visibility API

The Visibility API provides insight into how your RAG system is configured and how documents are being chunked. This is essential for understanding and tuning RAG performance.

### Configuration Visibility

View detailed configuration with changeability annotations:

```bash
curl http://localhost:8080/api/v1/visibility/config
```

Response includes changeability information for each setting:
- **Hot**: Can change at runtime with immediate effect (topK, fusionStrategy)
- **Warm**: Can change at runtime, affects new documents only (chunkSize, chunkingStrategy)
- **Cold**: Requires restart and full re-indexing (embeddingProvider, embeddingModel)

### Chunk Inspection

View how documents are chunked:

```bash
# List all chunks (paginated)
curl "http://localhost:8080/api/v1/visibility/chunks?page=1&pageSize=20"

# Get all chunks for a specific document
curl http://localhost:8080/api/v1/visibility/chunks/{documentId}

# Get a specific chunk
curl http://localhost:8080/api/v1/visibility/chunks/{documentId}/0
```

### Statistics

Get detailed statistics including chunk size distribution:

```bash
curl http://localhost:8080/api/v1/visibility/stats
```

Response includes:
- Document and chunk counts
- Per-collection statistics
- Chunk size distribution (min, max, avg, median, p90)
- Histogram buckets for chunk sizes
- Ingestion timestamps

### Collection Details

View collections with their chunking configuration:

```bash
curl http://localhost:8080/api/v1/visibility/collections
```

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
│  │  • Query + Answer      │  │  │ chunk_registry (visibility) │   │ │
│  │  • Visibility API      │  │  │ collection_configs (tuning) │   │ │
│  │  • Runtime Config      │  │  │ config_history (audit)      │   │ │
│  │  • Collection Config   │  │  │ sync_status (sync state)    │   │ │
│  │      Port 8080         │  │  └─────────────────────────────┘   │ │
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

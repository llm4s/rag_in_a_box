# RAG in a Box - Design Document

A turnkey RAG (Retrieval Augmented Generation) deployment that provides a complete, ready-to-run document Q&A system with web interfaces for document management and chat.

---

## Overview

### Vision
RAG in a Box packages the production-grade RAG capabilities from [llm4s](https://github.com/llm4s/llm4s) into a deployable service with:
- **REST API** for document ingestion and querying
- **Admin UI** for document management and system configuration
- **Chat UI** for interactive Q&A with source highlighting
- **Docker Compose** deployment with bundled PostgreSQL/pgvector

### Goals
1. **Zero-config startup**: `docker-compose up` gives you a working RAG system
2. **Production-ready**: Built on battle-tested llm4s RAG components
3. **Extensible**: Configurable vector stores, embedding providers, and LLM backends
4. **Developer-friendly**: Clear APIs, good documentation, easy local development

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Docker Compose                            │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │   Admin UI   │  │   Chat UI    │  │   RAG API Service    │  │
│  │  (Vue.js +   │  │  (Vue.js +   │  │   (Scala + llm4s)    │  │
│  │   Vuetify)   │  │   Vuetify)   │  │                      │  │
│  │   Port 3000  │  │   Port 3001  │  │     Port 8080        │  │
│  └──────┬───────┘  └──────┬───────┘  └──────────┬───────────┘  │
│         │                 │                      │              │
│         └─────────────────┴──────────────────────┘              │
│                           │ REST API                            │
│                           ▼                                     │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                    PostgreSQL + pgvector                  │  │
│  │                       Port 5432                           │  │
│  │                  (Data: ./data/postgres)                  │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                           │
                           ▼ External Services (configurable)
              ┌────────────┴────────────┐
              │                         │
        ┌─────┴─────┐            ┌──────┴──────┐
        │  LLM API  │            │  Embedding  │
        │ (OpenAI,  │            │   Provider  │
        │ Anthropic,│            │  (OpenAI,   │
        │  Ollama)  │            │   Voyage,   │
        └───────────┘            │   Ollama)   │
                                 └─────────────┘
```

---

## Technology Stack

### Backend (Scala)
| Component | Technology | Notes |
|-----------|------------|-------|
| Framework | http4s + cats-effect | Functional, async HTTP |
| RAG Engine | llm4s | Core RAG pipeline, embeddings, vector store |
| JSON | circe | Type-safe JSON handling |
| Database | Doobie + PostgreSQL | Connection pooling, transactions |
| Build | sbt | Cross-compile support from llm4s |

### Frontend (TypeScript)
| Component | Technology | Notes |
|-----------|------------|-------|
| Framework | Vue.js 3 | Composition API |
| UI Library | Vuetify 3 | Material Design components |
| Build | Vite | Fast development builds |
| HTTP Client | Axios | REST API communication |
| State | Pinia | Vue state management |

### Infrastructure
| Component | Technology | Notes |
|-----------|------------|-------|
| Container | Docker + Docker Compose | Multi-service deployment |
| Database | PostgreSQL 15 + pgvector | Vector similarity search |
| Reverse Proxy | nginx (optional) | SSL termination, routing |

---

## Components

### 1. RAG API Service (Scala)

The core backend service exposing REST endpoints for all RAG operations.

#### Endpoints (Implemented)

```
Document Management:
  POST   /api/v1/documents              - Upload document
  PUT    /api/v1/documents/{id}         - Upsert document (idempotent)
  GET    /api/v1/documents              - List documents
  DELETE /api/v1/documents/{id}         - Delete document
  DELETE /api/v1/documents              - Clear all documents
  GET    /api/v1/collections            - List collections

Sync (for custom ingesters):
  GET    /api/v1/sync/status            - Get sync status
  GET    /api/v1/sync/documents         - List synced document IDs
  POST   /api/v1/sync                   - Mark sync complete (prune)

Query:
  POST   /api/v1/query                  - Search + answer generation
  POST   /api/v1/search                 - Search only (no LLM)

Ingestion:
  POST   /api/v1/ingest/directory       - Ingest from directory
  POST   /api/v1/ingest/url             - Ingest from URLs
  POST   /api/v1/ingest/run             - Run all configured sources
  GET    /api/v1/ingest/status          - Get ingestion status
  GET    /api/v1/ingest/sources         - List configured sources

Configuration:
  GET    /api/v1/config                 - Get current config
  GET    /api/v1/config/providers       - List available providers
  GET    /api/v1/stats                  - Get RAG statistics

Runtime Configuration:
  GET    /api/v1/config/runtime         - Get runtime settings
  PUT    /api/v1/config/runtime         - Update runtime settings
  POST   /api/v1/config/runtime/validate - Validate proposed changes
  GET    /api/v1/config/runtime/history - Get config change history

Collection Configuration:
  GET    /api/v1/collections/{name}/config     - Get collection config
  PUT    /api/v1/collections/{name}/config     - Set collection config
  DELETE /api/v1/collections/{name}/config     - Remove custom config
  GET    /api/v1/collections/configs           - List all collection configs
  POST   /api/v1/collections/{name}/config/preview - Preview effective config

Visibility:
  GET    /api/v1/visibility/config      - Detailed config with annotations
  GET    /api/v1/visibility/chunks      - List all chunks (paginated)
  GET    /api/v1/visibility/chunks/{docId} - Get document chunks
  GET    /api/v1/visibility/chunks/{docId}/{idx} - Get specific chunk
  GET    /api/v1/visibility/stats       - Detailed stats
  GET    /api/v1/visibility/collections - Collection details

Chunking Preview:
  POST   /api/v1/chunking/preview       - Preview chunking on sample
  POST   /api/v1/chunking/compare       - Compare strategies
  GET    /api/v1/chunking/strategies    - List strategies
  GET    /api/v1/chunking/presets       - Get preset configs

Health:
  GET    /health                        - Health check
  GET    /health/ready                  - Readiness check
  GET    /health/live                   - Liveness check
```

#### Key Request/Response Types

```typescript
// Document Upload
POST /api/v1/documents
{
  "content": "string",           // Raw text content
  "filename": "string",          // Original filename
  "metadata": {                  // Optional metadata
    "source": "string",
    "author": "string",
    "tags": ["string"]
  },
  "collection": "default"        // Target collection
}

// Query with Answer
POST /api/v1/query
{
  "question": "string",
  "collection": "default",       // Optional: filter by collection
  "topK": 5,                     // Number of context chunks
  "includeMetadata": true        // Include source info
}

Response:
{
  "answer": "string",
  "contexts": [
    {
      "content": "string",
      "score": 0.95,
      "metadata": {
        "documentId": "uuid",
        "filename": "string",
        "chunkIndex": 0
      }
    }
  ],
  "usage": {
    "promptTokens": 150,
    "completionTokens": 200,
    "embeddingTokens": 50
  }
}
```

#### llm4s Integration

The API service wraps llm4s RAG components:

```scala
// Pseudo-code showing llm4s integration
import org.llm4s.rag._
import org.llm4s.vectorstore._
import org.llm4s.llmconnect._

class RAGService(config: RAGServiceConfig) {

  // Initialize from llm4s
  private val rag = RAGConfig()
    .withEmbeddingProvider(config.embeddingProvider)
    .withVectorStore(VectorStoreFactory.pgvector(config.pgConfig))
    .withChunkingStrategy(ChunkerFactory.Strategy.Sentence)
    .withLLM(LLMConnect.fromConfig(config.llmConfig))
    .build()

  def ingest(doc: DocumentRequest): Result[IngestResponse] =
    rag.ingestRaw(doc.content, doc.metadata)

  def query(req: QueryRequest): Result[QueryResponse] =
    rag.queryWithAnswer(req.question, Some(req.topK))
}
```

### 2. Admin UI (Vue.js + Vuetify)

Web interface for system administration.

#### Features
- **Dashboard**: Document counts, storage usage, query statistics
- **Document Browser**: List, search, preview, delete documents
- **Upload**: Drag-and-drop file upload, URL ingestion, bulk import
- **Collections**: Create/manage logical document groupings
- **Configuration**:
  - LLM provider settings (API keys, model selection)
  - Embedding provider settings
  - Chunking strategy configuration
  - Search parameters (topK, fusion strategy)
- **Logs**: View recent queries, errors, performance metrics

#### Screens
```
/admin
  ├── /dashboard         - Overview statistics
  ├── /documents         - Document management
  │   ├── /upload        - Upload interface
  │   └── /:id           - Document detail
  ├── /collections       - Collection management
  ├── /config            - System configuration
  │   ├── /llm           - LLM provider settings
  │   ├── /embeddings    - Embedding settings
  │   └── /search        - Search parameters
  └── /logs              - Activity logs
```

### 3. Chat UI (Vue.js + Vuetify)

Interactive Q&A interface for end users.

#### Features
- **Conversation**: Chat-style interface with message history
- **Source Highlighting**: Expandable source citations with document links
- **Context Preview**: Show relevant chunks used to generate answer
- **Collections**: Filter queries to specific document collections
- **Export**: Download conversation history

#### Screens
```
/chat
  ├── /                  - Main chat interface
  ├── /history           - Conversation history
  └── /collections       - Collection selector
```

---

## Configuration

### Environment Variables

```bash
# Required
OPENAI_API_KEY=sk-...              # Or ANTHROPIC_API_KEY
LLM_MODEL=openai/gpt-4o            # Model for answer generation

# Database (defaults work with bundled postgres)
DATABASE_URL=postgresql://rag:rag@postgres:5432/ragdb

# Embedding (defaults to OpenAI)
EMBEDDING_PROVIDER=openai          # openai, voyage, ollama
OPENAI_EMBEDDING_MODEL=text-embedding-3-small

# Optional
RAG_CHUNK_SIZE=500                 # Target chunk size in tokens
RAG_CHUNK_OVERLAP=50               # Overlap between chunks
RAG_TOP_K=5                        # Default number of context chunks
RAG_FUSION_STRATEGY=rrf            # rrf, weighted, vector_only
LOG_LEVEL=info                     # debug, info, warn, error
```

### Configuration File (config.yaml)

```yaml
server:
  host: 0.0.0.0
  port: 8080

database:
  url: ${DATABASE_URL}
  pool_size: 10

rag:
  chunking:
    strategy: sentence        # simple, sentence, markdown, semantic
    target_size: 500
    overlap: 50

  search:
    top_k: 5
    fusion_strategy: rrf      # rrf, weighted, vector_only, keyword_only
    use_reranker: false

  embeddings:
    provider: openai
    model: text-embedding-3-small

llm:
  provider: openai
  model: gpt-4o
  temperature: 0.1
  max_tokens: 1024
```

---

## Docker Compose Setup

### docker-compose.yml

```yaml
version: '3.8'

services:
  # PostgreSQL with pgvector
  postgres:
    image: pgvector/pgvector:pg15
    environment:
      POSTGRES_USER: rag
      POSTGRES_PASSWORD: rag
      POSTGRES_DB: ragdb
    volumes:
      - ./data/postgres:/var/lib/postgresql/data
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U rag -d ragdb"]
      interval: 5s
      timeout: 5s
      retries: 5

  # RAG API Service
  api:
    build: ./api
    environment:
      DATABASE_URL: postgresql://rag:rag@postgres:5432/ragdb
      OPENAI_API_KEY: ${OPENAI_API_KEY}
      LLM_MODEL: ${LLM_MODEL:-openai/gpt-4o}
    ports:
      - "8080:8080"
    depends_on:
      postgres:
        condition: service_healthy

  # Admin UI
  admin:
    build: ./admin-ui
    environment:
      VITE_API_URL: http://localhost:8080
    ports:
      - "3000:80"
    depends_on:
      - api

  # Chat UI
  chat:
    build: ./chat-ui
    environment:
      VITE_API_URL: http://localhost:8080
    ports:
      - "3001:80"
    depends_on:
      - api
```

### Quick Start

```bash
# Clone and start
git clone https://github.com/llm4s/rag-in-a-box.git
cd rag-in-a-box

# Set your API key
export OPENAI_API_KEY=sk-...

# Start all services
docker-compose up -d

# Access:
# - Admin UI: http://localhost:3000
# - Chat UI:  http://localhost:3001
# - API:      http://localhost:8080
```

---

## Project Structure

```
rag-in-a-box/
├── docker-compose.yml
├── README.md
├── DESIGN.md                    # This document
│
├── api/                         # Scala backend
│   ├── build.sbt
│   ├── project/
│   │   └── plugins.sbt
│   ├── src/
│   │   ├── main/
│   │   │   ├── scala/
│   │   │   │   └── ragbox/
│   │   │   │       ├── Main.scala
│   │   │   │       ├── config/
│   │   │   │       │   └── AppConfig.scala
│   │   │   │       ├── routes/
│   │   │   │       │   ├── DocumentRoutes.scala
│   │   │   │       │   ├── QueryRoutes.scala
│   │   │   │       │   └── ConfigRoutes.scala
│   │   │   │       ├── service/
│   │   │   │       │   ├── RAGService.scala
│   │   │   │       │   └── DocumentService.scala
│   │   │   │       └── model/
│   │   │   │           ├── ApiModels.scala
│   │   │   │           └── Codecs.scala
│   │   │   └── resources/
│   │   │       ├── application.conf
│   │   │       └── logback.xml
│   │   └── test/
│   │       └── scala/
│   └── Dockerfile
│
├── admin-ui/                    # Vue.js Admin UI
│   ├── package.json
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── src/
│   │   ├── main.ts
│   │   ├── App.vue
│   │   ├── router/
│   │   │   └── index.ts
│   │   ├── views/
│   │   │   ├── Dashboard.vue
│   │   │   ├── Documents.vue
│   │   │   ├── Upload.vue
│   │   │   └── Config.vue
│   │   ├── components/
│   │   │   ├── DocumentList.vue
│   │   │   ├── DocumentUploader.vue
│   │   │   └── ConfigPanel.vue
│   │   ├── stores/
│   │   │   ├── documents.ts
│   │   │   └── config.ts
│   │   └── api/
│   │       └── client.ts
│   ├── public/
│   └── Dockerfile
│
├── chat-ui/                     # Vue.js Chat UI
│   ├── package.json
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── src/
│   │   ├── main.ts
│   │   ├── App.vue
│   │   ├── views/
│   │   │   └── Chat.vue
│   │   ├── components/
│   │   │   ├── ChatMessage.vue
│   │   │   ├── ChatInput.vue
│   │   │   ├── SourceCard.vue
│   │   │   └── ConversationList.vue
│   │   ├── stores/
│   │   │   └── chat.ts
│   │   └── api/
│   │       └── client.ts
│   ├── public/
│   └── Dockerfile
│
├── data/                        # Persistent data (gitignored)
│   └── postgres/
│
└── scripts/
    ├── init-db.sql              # Database initialization
    └── dev.sh                   # Development helper scripts
```

---

## llm4s Integration Points

RAG in a Box leverages these llm4s components:

### From `/modules/core/src/main/scala/org/llm4s/`

| Component | Path | Usage |
|-----------|------|-------|
| RAG Pipeline | `rag/RAG.scala` | Main orchestration - ingest, query, answer |
| RAG Config | `rag/RAGConfig.scala` | Fluent builder for pipeline configuration |
| Document Loaders | `rag/loader/*.scala` | File, URL, text ingestion |
| Chunking | `chunking/*.scala` | Document splitting strategies |
| Vector Store | `vectorstore/PgVectorStore.scala` | PostgreSQL pgvector backend |
| Hybrid Search | `vectorstore/HybridSearcher.scala` | Vector + keyword fusion |
| Embeddings | `llmconnect/EmbeddingClient.scala` | Embedding API abstraction |
| LLM Client | `llmconnect/LLMConnect.scala` | Multi-provider LLM interface |
| Result Types | `types/package.scala` | `Result[A] = Either[LLMError, A]` |
| Configuration | `config/ConfigReader.scala` | Type-safe configuration |

### Key Patterns to Follow

From llm4s CLAUDE.md:

```scala
// Always use Result[A] for error handling
def processDocument(doc: Document): Result[ProcessedDoc] =
  for {
    chunks <- chunker.chunk(doc.content)
    embeddings <- embeddingClient.embed(chunks.map(_.content))
    _ <- vectorStore.upsert(chunks.zip(embeddings))
  } yield ProcessedDoc(doc.id, chunks.length)

// Use ConfigReader, never sys.env directly
val config: Result[AppConfig] = ConfigReader.load[AppConfig]()

// Type-safe newtypes for domain concepts
final case class DocumentId(value: String) extends AnyVal
final case class CollectionId(value: String) extends AnyVal
```

---

## Development Phases

### Phase 1: Core API ✅ COMPLETE
- [x] Set up Scala project with sbt, http4s, llm4s dependency
- [x] Implement document ingestion endpoints (POST, PUT, DELETE, GET)
- [x] Implement query/search endpoints
- [x] Add PostgreSQL/pgvector integration
- [x] Docker build for API service
- [x] Basic health checks and logging
- [x] Sync endpoints for custom ingesters
- [x] Built-in directory/URL ingestion with scheduling
- [x] Python SDK for custom ingesters

### Phase 1.5: RAG Tuning & Visibility ✅ COMPLETE
- [x] Visibility API - inspect chunks, view config, stats
- [x] Chunking Preview API - test strategies before committing
- [x] Runtime Configuration API - modify settings without restart
- [x] Per-Collection Chunking - different chunking per collection/file type

### Phase 2: Admin UI ✅ COMPLETE
- [x] Vue.js + Vuetify project setup
- [x] Dashboard with statistics
- [x] Document list and detail views
- [x] Upload interface (drag-and-drop, URL)
- [x] Configuration management screens
- [x] Docker build for Admin UI

### Phase 3: Chat UI (Future)
- [ ] Vue.js + Vuetify project setup
- [ ] Chat interface with message history
- [ ] Source citation display
- [ ] Context preview expansion
- [ ] Collection filtering
- [ ] Docker build for Chat UI

### Phase 4: Integration & Polish
- [x] Docker Compose orchestration
- [x] Database initialization scripts
- [x] Environment variable configuration
- [x] README and documentation
- [ ] Example datasets for demo
- [ ] End-to-end testing

---

## Future Enhancements

### Phase 5 (Post-MVP)
- **Multi-tenancy**: Organization isolation, API keys, quotas
- **Authentication**: OAuth2/OIDC integration, user management
- **Additional Vector Stores**: SQLite (embedded), Qdrant
- **Helm Charts**: Kubernetes deployment
- **Metrics Dashboard**: Grafana integration
- **Streaming Responses**: SSE for real-time answer generation
- **Document Versioning**: Track document changes over time
- **Scheduled Sync**: Automatic re-ingestion from URLs

---

## References

### llm4s Documentation
- [RAG Evaluation Guide](https://github.com/llm4s/llm4s/blob/main/docs/guide/rag-evaluation.md)
- [Vector Store Guide](https://github.com/llm4s/llm4s/blob/main/docs/guide/vector-store.md)
- [RAG Benchmark Results](https://github.com/llm4s/llm4s/blob/main/docs/rag-benchmark-results.md)
- [Roadmap - Phase 5](https://github.com/llm4s/llm4s/blob/main/docs/reference/roadmap.md)

### llm4s Source Files
- RAG Core: `/modules/core/src/main/scala/org/llm4s/rag/`
- Vector Stores: `/modules/core/src/main/scala/org/llm4s/vectorstore/`
- Chunking: `/modules/core/src/main/scala/org/llm4s/chunking/`
- Embeddings: `/modules/core/src/main/scala/org/llm4s/llmconnect/`
- Samples: `/modules/samples/src/main/scala/org/llm4s/samples/rag/`

### External References
- [pgvector Documentation](https://github.com/pgvector/pgvector)
- [Vue.js 3 Documentation](https://vuejs.org/)
- [Vuetify 3 Documentation](https://vuetifyjs.com/)
- [http4s Documentation](https://http4s.org/)

---
layout: page
title: Configuration
parent: User Guide
nav_order: 1
---

# Configuration Reference

Complete reference for all RAG in a Box configuration options.
{: .fs-6 .fw-300 }

## Environment Variables

RAG in a Box is configured primarily through environment variables. All settings have sensible defaults for development.

---

## Server Settings

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_HOST` | `0.0.0.0` | Host to bind the server to |
| `SERVER_PORT` | `8080` | Port to listen on |

---

## Database Settings

PostgreSQL with pgvector is required for vector storage.

| Variable | Default | Description |
|----------|---------|-------------|
| `PG_HOST` | `localhost` | PostgreSQL host |
| `PG_PORT` | `15432` | PostgreSQL port |
| `PG_DATABASE` | `ragdb` | Database name |
| `PG_USER` | `rag` | Database user |
| `PG_PASSWORD` | `rag` | Database password |
| `PG_TABLE_NAME` | `rag_embeddings` | Table for vector embeddings |
| `DATABASE_URL` | - | Alternative: full connection URL |

### Using DATABASE_URL

Instead of individual settings, you can use a single connection URL:

```bash
DATABASE_URL=postgresql://user:password@host:5432/database
```

---

## LLM Settings

Configure the language model for answer generation.

| Variable | Default | Description |
|----------|---------|-------------|
| `LLM_MODEL` | `openai/gpt-4o` | Model in format `provider/model` |
| `LLM_TEMPERATURE` | `0.1` | Temperature for generation (0.0-1.0) |

### Supported Models

```bash
# OpenAI
LLM_MODEL=openai/gpt-4o
LLM_MODEL=openai/gpt-4o-mini
LLM_MODEL=openai/gpt-3.5-turbo

# Anthropic
LLM_MODEL=anthropic/claude-3-opus
LLM_MODEL=anthropic/claude-3-sonnet
LLM_MODEL=anthropic/claude-3-haiku
```

---

## Embedding Settings

Configure the embedding model for vector search.

| Variable | Default | Description |
|----------|---------|-------------|
| `EMBEDDING_PROVIDER` | `openai` | Provider: `openai`, `voyage`, `ollama` |
| `EMBEDDING_MODEL` | `text-embedding-3-small` | Model name |

### Provider Examples

```bash
# OpenAI
EMBEDDING_PROVIDER=openai
EMBEDDING_MODEL=text-embedding-3-small

# Voyage AI
EMBEDDING_PROVIDER=voyage
EMBEDDING_MODEL=voyage-2

# Ollama (local)
EMBEDDING_PROVIDER=ollama
EMBEDDING_MODEL=nomic-embed-text
```

---

## RAG Settings

Configure chunking and search behavior.

### Chunking

| Variable | Default | Description |
|----------|---------|-------------|
| `RAG_CHUNKING_STRATEGY` | `sentence` | Strategy: `simple`, `sentence`, `markdown`, `semantic` |
| `RAG_CHUNK_SIZE` | `800` | Target chunk size in characters |
| `RAG_CHUNK_OVERLAP` | `150` | Overlap between chunks |

### Search

| Variable | Default | Description |
|----------|---------|-------------|
| `RAG_TOP_K` | `5` | Number of chunks to retrieve |
| `RAG_FUSION_STRATEGY` | `rrf` | Fusion: `rrf`, `weighted`, `vector_only`, `keyword_only` |
| `RAG_RRF_K` | `60` | RRF k parameter (for rrf strategy) |

### System Prompt

| Variable | Default | Description |
|----------|---------|-------------|
| `RAG_SYSTEM_PROMPT` | (see below) | Custom system prompt for answer generation |

Default system prompt:
```
You are a helpful assistant that answers questions based on the provided context.
Use only the information from the context to answer the question.
If the context doesn't contain enough information, say so.
Be concise and accurate.
```

---

## API Keys

Required for the respective providers.

| Variable | Description |
|----------|-------------|
| `OPENAI_API_KEY` | OpenAI API key (also accepts `OPEN_AI_KEY`) |
| `ANTHROPIC_API_KEY` | Anthropic API key |
| `VOYAGE_API_KEY` | Voyage AI API key |

---

## Authentication

See [Authentication Guide](/guide/authentication) for detailed setup.

| Variable | Default | Description |
|----------|---------|-------------|
| `AUTH_MODE` | `open` | Mode: `open`, `basic`, `oauth` |
| `ADMIN_USERNAME` | `admin` | Admin username (basic mode) |
| `ADMIN_PASSWORD` | - | Admin password (basic mode, required) |
| `JWT_SECRET` | - | Secret for JWT signing (required for basic/oauth) |
| `JWT_EXPIRATION` | `86400` | Token expiration in seconds (24h) |

### Legacy API Key Mode

| Variable | Default | Description |
|----------|---------|-------------|
| `API_KEY` | - | Simple API key authentication |
| `ALLOW_ADMIN_HEADER` | `false` | Allow X-Admin header bypass |

---

## Production Hardening

### Rate Limiting

| Variable | Default | Description |
|----------|---------|-------------|
| `RATE_LIMIT_ENABLED` | `false` | Enable rate limiting |
| `RATE_LIMIT_MAX_REQUESTS` | `100` | Max requests per window |
| `RATE_LIMIT_WINDOW_SECONDS` | `60` | Window size in seconds |

### Request Size

| Variable | Default | Description |
|----------|---------|-------------|
| `REQUEST_SIZE_ENABLED` | `true` | Enable size limiting |
| `MAX_BODY_SIZE_MB` | `10` | Maximum body size in MB |

### Graceful Shutdown

| Variable | Default | Description |
|----------|---------|-------------|
| `SHUTDOWN_TIMEOUT_SECONDS` | `30` | Max time for shutdown |
| `SHUTDOWN_DRAIN_SECONDS` | `5` | Connection drain time |

---

## Metrics

| Variable | Default | Description |
|----------|---------|-------------|
| `METRICS_ENABLED` | `false` | Enable Prometheus metrics at `/metrics` |

---

## Ingestion Sources

Configure automatic document ingestion via HOCON configuration file or environment variables.

### Simple Directory Ingestion

```bash
INGEST_DIR=/data/docs
INGEST_PATTERNS=*.md,*.txt,*.pdf
INGEST_RECURSIVE=true
INGEST_ON_STARTUP=true
INGEST_SCHEDULE="0 0 * * *"  # Daily at midnight
```

### Advanced Configuration (HOCON)

For complex setups, use `application.conf`:

```hocon
ingestion {
  enabled = true
  run-on-startup = false
  schedule = "0 0 * * *"

  sources = [
    # Directory source
    {
      type = "directory"
      name = "local-docs"
      path = "/data/docs"
      patterns = ["*.md", "*.txt", "*.pdf"]
      recursive = true
    }

    # URL source
    {
      type = "url"
      name = "external-docs"
      urls = ["https://example.com/doc.html"]
    }

    # Web crawler
    {
      type = "web"
      name = "company-docs"
      seed-urls = ["https://docs.example.com"]
      max-depth = 3
      max-pages = 500
      same-domain-only = true
    }

    # Database source
    {
      type = "database"
      name = "kb-articles"
      url = "jdbc:postgresql://localhost:5432/mydb"
      user = "myuser"
      password = "mypassword"
      query = "SELECT id, content FROM articles"
    }
  ]
}
```

---

## Example Configurations

### Development

```bash
# Minimal development setup
export OPENAI_API_KEY=sk-...
export AUTH_MODE=open
```

### Production

```bash
# Production with basic auth
export AUTH_MODE=basic
export ADMIN_PASSWORD=secure-password
export JWT_SECRET=32-character-secret-key-here!!

# Database
export DATABASE_URL=postgresql://rag:password@db:5432/ragdb

# API keys
export OPENAI_API_KEY=sk-...

# Hardening
export RATE_LIMIT_ENABLED=true
export METRICS_ENABLED=true
```

### Docker Compose

```yaml
services:
  ragbox:
    image: ghcr.io/llm4s/rag-in-a-box:latest
    environment:
      - DATABASE_URL=postgresql://rag:rag@postgres:5432/ragdb
      - OPENAI_API_KEY=${OPENAI_API_KEY}
      - AUTH_MODE=basic
      - ADMIN_PASSWORD=${ADMIN_PASSWORD}
      - JWT_SECRET=${JWT_SECRET}
    ports:
      - "8080:8080"
```

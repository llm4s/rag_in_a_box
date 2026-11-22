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
| `DATABASE_URL` | - | Alternative: full connection URL (overrides the settings above) |
| `PERMISSIONS_REQUIRED` | `false` | Fail startup if permission-based RAG cannot be initialized (see below) |

### Using DATABASE_URL

Instead of individual settings, you can use a single connection URL:

```bash
DATABASE_URL=postgresql://user:password@host:5432/database
```

Format: `postgresql://[user[:password]@]host[:port][/database][?params]`

- `postgres://` and `jdbc:postgresql://` are also accepted.
- Credentials are taken from the URL and passed to the driver separately, so
  they never appear in the JDBC URL. Percent-encode special characters in the
  user name or password (for example `@` is `%40`, `/` is `%2F`, `:` is `%3A`).
- A missing port defaults to `5432` (not `PG_PORT`). A missing user, password
  or database falls back to `PG_USER`, `PG_PASSWORD` and `PG_DATABASE`.
- Query parameters are passed through to the PostgreSQL JDBC driver, e.g.
  `?sslmode=require`.
- Multiple hosts are not supported. Wrap IPv6 addresses in brackets:
  `postgresql://user:pass@[::1]:5432/ragdb`.
- An invalid `DATABASE_URL` fails startup with a configuration error.

Every database consumer (document registry, vector store, permission search
index, auth, analytics) uses the same effective connection settings.

### Permission-based RAG

At startup the server initializes a permission-aware search index. If that fails
(for example the database is unreachable or the schema cannot be migrated), the
default behaviour is to log a warning and fall back to **legacy mode, in which
queries ignore all document permissions**. The startup log always states the
actual mode (`Permission-based RAG: enabled` or `Permission-based RAG: DISABLED`).

Set `PERMISSIONS_REQUIRED=true` (HOCON `permissions.required`) to make this a
startup failure instead. This is recommended for any deployment that relies on
document permissions.

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
# Directories the API may ingest from (defaults to INGEST_DIR)
INGEST_ALLOWED_ROOTS=/data/docs
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

### Ingestion policy (allowed directories and URLs)

The one-shot endpoints `POST /api/v1/ingest/directory` and `POST /api/v1/ingest/url` take a
path or URLs from the caller, so they are restricted. Configured sources are checked the same
way. A refused request gets `400 Bad Request` with an `ErrorResponse` explaining why; file
contents are never included.

| Variable | HOCON | Default | Description |
|----------|-------|---------|-------------|
| `INGEST_ALLOWED_ROOTS` | `ingestion.allowed-roots` | paths of the configured directory sources (e.g. `INGEST_DIR`) | Comma-separated directories that directory ingestion may read under. With none, the directory endpoint is disabled. |
| `INGEST_URL_ALLOWED_HOSTS` | `ingestion.url.allowed-hosts` | *(any public host)* | Comma-separated hosts URL and web ingestion may fetch. `*.example.com` matches subdomains. |
| `INGEST_URL_ALLOW_PRIVATE_NETWORKS` | `ingestion.url.allow-private-networks` | `false` | Skip ragbox's private-address check (see the note below). |

**Directories.** A path is accepted only if it is under an allowed root once symlinks are
resolved (`toRealPath`): `..` segments are refused outright, and a symlink that points outside the
roots is refused. While walking the directory, symlinks are followed only when their target stays
inside the requested directory, and only regular files are read. `patterns` must be file
extensions such as `*.md`; patterns containing `/`, `\` or `..` are refused.

**URLs.** Only `http` and `https` are accepted, without embedded credentials. The host is
resolved and every address must be public: loopback, private (RFC 1918, IPv6 ULA `fc00::/7`),
link-local (including the cloud metadata endpoints `169.254.169.254` and `fd00:ec2::254`),
unspecified, multicast, CGNAT `100.64.0.0/10`, reserved ranges and IPv6 forms embedding such an
IPv4 address (e.g. `[::ffff:127.0.0.1]`) are refused, as are numeric spellings such as
`http://2130706433/` or `http://0x7f.1/` and names like `localhost`. For web crawler sources each
seed URL is checked, and pages on hosts outside `allowed-hosts` are skipped. llm4s checks the
address of every page and redirect the crawler and URL loader fetch.

**Limitations.**
- The address is resolved again when llm4s connects, so a host whose DNS answer changes between
  the check and the fetch (DNS rebinding) is not covered.
- The web crawler fetches `robots.txt` (following redirects) before llm4s's own check of a crawled
  link. Its body is never ingested, but the request is made; keep `same-domain-only = true` (the
  default) so crawled links stay on the checked seed domains.
- llm4s 0.6.0's loaders refuse private addresses themselves, whatever ragbox allows, so
  `allow-private-networks = true` does not yet make intranet URLs fetchable. To ingest internal
  content, push it with the [sync API](external-ingesters.md) instead.

The llm4s hooks that would close these gaps are tracked in
[llm4s/llm4s#2724](https://github.com/llm4s/llm4s/issues/2724).

> **Note:** these endpoints are admin operations. Until role checks land (issue #13), protect
> them with `AUTH_MODE` / an API key, and do not expose the API to untrusted callers.

### How sources are synced

- **Each source syncs only its own documents.** ragbox records which source ingested each
  document. When a directory, web or S3 source no longer lists a document, that source's next
  run removes it. Other sources' documents, and documents uploaded through the API, are never
  removed. URL and database sources add and update documents but never remove them.
- **Unchanged documents are not re-embedded,** including after a restart. Content hashes are
  kept in PostgreSQL. Documents deleted at a source while the server was down are removed on
  that source's next run.
- **Source names must stay stable.** A source's `name` identifies its documents. If you rename
  a source, its previously ingested documents are no longer removed automatically.
- **One run at a time.** While a run is in progress, `POST /api/v1/ingest/run` and
  `POST /api/v1/ingest/run/{source}` return `409 Conflict`, and a scheduled run that comes due
  is skipped.

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
    image: ghcr.io/llm4s/rag_in_a_box:9e5427d
    environment:
      - DATABASE_URL=postgresql://rag:rag@postgres:5432/ragdb
      - OPENAI_API_KEY=${OPENAI_API_KEY}
      - AUTH_MODE=basic
      - ADMIN_PASSWORD=${ADMIN_PASSWORD}
      - JWT_SECRET=${JWT_SECRET}
    ports:
      - "8080:8080"
```

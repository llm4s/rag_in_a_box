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

RAG in a Box is configured primarily through environment variables, which `application.conf`
(HOCON) maps onto its settings. All settings have sensible defaults for development; the
repository's `.env.example` lists them with placeholders.

---

## Server Settings

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_HOST` | `0.0.0.0` | Host to bind the server to |
| `SERVER_PORT` | `8080` | Port to listen on |

## Logging

| Variable | Default | Description |
|----------|---------|-------------|
| `LOG_LEVEL` | `INFO` | Level for the `org.llm4s` / `org.llm4s.ragbox` loggers and the root logger |
| `LOG_FORMAT` | (console) | `json` for one JSON object per line (logstash encoder, for log shippers); anything else gives human-readable console output |

The HTTP request log (logger `org.llm4s.ragbox.http`) writes one line per request (method, path,
query string, status, duration). Headers are not logged, and credential-like query parameters
(`access_token`, `api_key`, `token`, `code`, `state`, ...) are redacted.

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
| `DB_POOL_SIZE` | `10` | Size of the connection pool shared by every component (ingestion locks use their own connections, see [Several replicas](#several-replicas)) |
| `DB_MIGRATE_ON_START` | `true` | Apply pending schema migrations at startup (see below) |
| `PERMISSIONS_REQUIRED` | `false` | Fail startup if permission-based RAG cannot be initialized (see below) |

### Schema migrations

ragbox manages its own tables (document and chunk registries, runtime settings, collection
configs, query logs, users, access tokens, experiments, chat history, OAuth sessions) with
[Flyway](https://documentation.red-gate.com/flyway). The migrations are SQL scripts shipped in
the jar (`src/main/resources/db/migration`); Flyway records the ones applied in the
`flyway_schema_history` table.

- By default (`database.migrate-on-start = true`) the server applies pending migrations at
  startup, right after connecting and before anything else uses the database. Replicas that
  start together are serialised by Flyway's PostgreSQL lock, so each migration runs once.
- To migrate separately, e.g. from a deploy job before rolling out new servers, run the same
  image or jar with `--migrate-only` (`java -jar ragbox-assembly.jar --migrate-only`, or
  `sbt "run --migrate-only"`): it applies the migrations and exits. Then start the servers with
  `DB_MIGRATE_ON_START=false`; they expect the schema to be up to date. The Helm chart can run
  this as a hook job (`migrations.job.enabled`, see
  [Helm Deployment](helm-deployment.md#schema-migrations)).
- The vector and keyword tables (`PG_TABLE_NAME`) and the permission tables
  (`llm4s_collections`, `llm4s_principals`) belong to llm4s, which creates them itself after
  the migrations have run.
- The database user needs the rights to create tables and indexes in its schema. The
  `vector` extension is created by `scripts/init-db.sql` (or by llm4s when the user may).

Databases created by releases before Flyway are adopted automatically on the first start; see
[Upgrading an existing database](../production-deployment.md#upgrading-an-existing-database).

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

Configure the language model for answer generation. The provider is resolved through
llm4s's `ProviderRegistry`, so any llm4s provider on the classpath can be named; ragbox
ships with OpenAI, Anthropic and Ollama. If the LLM cannot be built (unknown provider,
missing API key), the server logs the llm4s error and runs without answer generation:
search still works, answers fail with a configuration error.

| Variable | Default | Description |
|----------|---------|-------------|
| `LLM_MODEL` | `openai/gpt-4o` | Model in format `provider/model` (a bare model name means OpenAI) |
| `LLM_BASE_URL` | (provider default) | Provider endpoint: Ollama, an OpenAI-compatible gateway, ... |
| `LLM_TEMPERATURE` | `0.1` | Temperature answers are generated at (0.0-2.0) |

`LLM_TEMPERATURE` applies to every answer: `/query`, chat, and their streams. It can be changed
at runtime (`llmTemperature`), set for one query (`overrides.llmTemperature`) and tested in an
experiment.

### Examples

```bash
# OpenAI
LLM_MODEL=openai/gpt-4o
LLM_MODEL=openai/gpt-4o-mini

# Anthropic
LLM_MODEL=anthropic/claude-sonnet-4-5

# Ollama (local or remote; no API key)
LLM_MODEL=ollama/llama3.1
LLM_BASE_URL=http://localhost:11434
```

`GET /api/v1/config/providers` lists every registered provider, whether ragbox has what it
needs to use it, and its models - live from the provider's model-listing API when it has one
and is configured, otherwise from llm4s's bundled model metadata.

---

## Embedding Settings

Configure the embedding model for vector search. Like the LLM, the provider is resolved
through llm4s's `ProviderRegistry`.

| Variable | Default | Description |
|----------|---------|-------------|
| `EMBEDDING_PROVIDER` | `openai` | Provider: `openai`, `voyage`, `ollama` (any registered llm4s embedding provider) |
| `EMBEDDING_MODEL` | `text-embedding-3-small` | Model name |
| `EMBEDDING_DIMENSIONS` | (from llm4s's model table) | Embedding dimensions, passed to llm4s `withEmbeddings(provider, model, dims)` |
| `EMBEDDING_BASE_URL` | (provider default) | Provider endpoint, e.g. a remote Ollama server |

### Provider Examples

```bash
# OpenAI
EMBEDDING_PROVIDER=openai
EMBEDDING_MODEL=text-embedding-3-small

# Voyage AI
EMBEDDING_PROVIDER=voyage
EMBEDDING_MODEL=voyage-3

# Ollama (local)
EMBEDDING_PROVIDER=ollama
EMBEDDING_MODEL=nomic-embed-text
EMBEDDING_DIMENSIONS=768
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

Each document is chunked once - with its collection's chunking config when it has one (see
[Per-collection chunking](#per-collection-chunking)), otherwise with these settings as the
runtime config currently has them - and exactly those chunks are indexed and shown in the
chunk store, under the ids llm4s indexed them as (`semantic` embeds sentences with the
embedding provider). See [Chunking](chunking.md).

### Search

| Variable | Default | Description |
|----------|---------|-------------|
| `RAG_TOP_K` | `5` | Number of chunks to retrieve |
| `RAG_FUSION_STRATEGY` | `rrf` | Fusion: `rrf`, `weighted`, `vector_only`, `keyword_only` |
| `RAG_RRF_K` | `60` | RRF k parameter (for rrf strategy) |

Search is hybrid: vectors in pgvector and BM25 keywords in PostgreSQL full-text search
(llm4s `RAGConfig.withPgHybrid`), so the keyword index survives restarts.

**Permission mode** searches the same way: fusion, keyword search, reranking and query transforms
all apply to permission-filtered search, whose keyword half is PostgreSQL full-text search over
the permission index's own table.

### Reranking

Rerank hybrid-search candidates before the top-k are kept (llm4s
`withCohereReranking` / `withLLMReranking`). Off by default.

| Variable | Default | Description |
|----------|---------|-------------|
| `RAG_RERANKING_STRATEGY` | `none` | `none`, `cohere` (needs `COHERE_API_KEY`) or `llm` (reranks with the answer LLM) |
| `RAG_RERANKING_MODEL` | `rerank-english-v3.0` | Cohere rerank model |
| `RAG_RERANKING_CANDIDATES` | `30` | Candidates retrieved and reranked down to top-k |

### Query Transformation

Rewrite the query before retrieval (llm4s `withQueryTransformer`). Off by default.

| Variable | Default | Description |
|----------|---------|-------------|
| `RAG_QUERY_TRANSFORM` | `none` | `none` or `llm-rewrite` (llm4s `LLMQueryRewriter`, using the answer LLM) |

### Chat

| Variable | Default | Description |
|----------|---------|-------------|
| `RAG_CHAT_HISTORY_MESSAGES` | `10` | Earlier messages of a chat session sent to the LLM with each question (`0` sends none) |

Chat answers include the session's recent answered exchanges as conversation history (llm4s
`RAG.queryWithAnswer`'s `history`); retrieval uses the new question. The streaming endpoints
send the answer as the LLM generates it (`chunk` events), after the contexts it is generated
from.

### Tracing and query metrics

ragbox registers an llm4s `Tracing` backend with the pipeline (`RAGConfig.withTracing`), which
traces every stage and model call of a query. From that trace, every answered query
records its embedding, search and LLM latencies (llm4s's own stage timings), the chunks retrieved
and used, embedding/prompt/completion tokens and an estimated cost (for models whose pricing
llm4s knows), totalled over the answer, query-rewrite and LLM-rerank calls. These are returned in the query
response's `metrics` and stored in the query log, and the latency suggestion is based on
retrieval latency (without answer generation).

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

## Runtime Settings

Some settings can be changed while the server runs, through `PUT /api/v1/config/runtime`
(admin only) or the admin UI's Runtime Configuration page. The values above are the
starting point; a runtime change applies to the next request without a restart.

```bash
curl -X PUT http://localhost:8080/api/v1/config/runtime \
  -H "Content-Type: application/json" \
  -d '{"topK": 8, "systemPrompt": "Answer in one paragraph."}'
```

| Setting | Kind | Applies to |
|---------|------|------------|
| `topK` | hot | the next search, query and chat message (a request's own `topK` takes precedence) |
| `fusionStrategy`, `rrfK` | hot | the next search, query and chat message |
| `systemPrompt` | hot | the next `/query` answer and chat answer |
| `llmTemperature` | hot | the next `/query` answer and chat answer |
| `chunkingStrategy`, `chunkSize`, `chunkOverlap` | warm | documents ingested from then on, in collections without their own chunking config; indexed documents keep their chunks until re-ingested |

Each change in the update response says whether it is `effectiveImmediately` and, where a
setting does not apply, a `note` saying why.

**How it is applied.** fusion, the system prompt, reranking and the query transform are baked
into an llm4s `RAG` pipeline when it is built, so ragbox keeps pipelines keyed by those settings.
After a change the next query builds a pipeline for the new settings; the previous one stops
taking requests and is closed once the requests still using it finish. topK is passed to llm4s
on every call, so it needs no rebuild.

**Persistence.** Every setting changed at runtime is stored in the `runtime_config` table and
applied over the environment's value at startup, so changes survive a restart (`config_history`
keeps the audit trail). Setting a value back to the environment's value removes the stored row,
so the setting follows the environment again. A stored value that is no longer valid is
ignored with a warning.

### Per-collection chunking

A collection can have its own chunking (`PUT /api/v1/collections/{name}/config`, admin only):
a strategy, sizes, and strategies by file extension. Every ingest path - JSON and multipart
upload, upserts and ingestion sources - chunks a document with what its collection resolves to:
the file-type override for its file name (the `filename` metadata, or the path or source a
loader records), else the collection's settings, else the runtime settings. Extensions are
case-insensitive (`.MD` is stored as `.md`).

A config is validated as it will be used: sizes it leaves out come from the runtime settings,
the overlap must be below the target size, and `maxSize` (by default 1.5 x the target size)
must be at least the target size.

```bash
curl -X PUT http://localhost:8080/api/v1/collections/docs/config \
  -H "Content-Type: application/json" \
  -d '{"strategy": "markdown", "targetSize": 500, "overlap": 75, "fileTypeStrategies": {".txt": "sentence"}}'
```

---

## API Keys

Required for the respective providers.

| Variable | Description |
|----------|-------------|
| `OPENAI_API_KEY` | OpenAI API key (also accepts `OPEN_AI_KEY`) |
| `ANTHROPIC_API_KEY` | Anthropic API key |
| `VOYAGE_API_KEY` | Voyage AI API key |
| `COHERE_API_KEY` | Cohere API key, for `RAG_RERANKING_STRATEGY=cohere` |
| `<PROVIDER>_API_KEY` | The key of any other llm4s provider, e.g. `MISTRAL_API_KEY` |

Keys are resolved by llm4s provider id, so any provider module on the classpath works without
ragbox knowing it. A provider's key is, highest precedence first: ragbox's
`api-keys.<id>` (HOCON), the variable the llm4s module binds for its vendor
(`llm4s.credentials.<id>.apiKey` in the module's `reference.conf`, e.g. `GEMINI_API_KEY`), and
finally `<ID>_API_KEY` (the id upper-cased, other characters as `_`).

---

## Authentication

See [Authentication Guide](authentication.md) for detailed setup.

| Variable | Default | Description |
|----------|---------|-------------|
| `AUTH_MODE` | `open` | Mode: `open`, `basic`, `oauth` |
| `ADMIN_USERNAME` | `admin` | Admin username (basic mode) |
| `ADMIN_PASSWORD` | - | Admin password (basic mode, required; creates the admin user when there are no users, replaces the old default `admin`; must meet the password policy and not be a placeholder) |
| `JWT_SECRET` | - | Secret for JWT signing (basic mode, required: 32+ characters, not a `CHANGE_ME` / `REPLACE-ME` placeholder) |
| `JWT_EXPIRATION` | `86400` | Token expiration in seconds (24h) |
| `JWT_ISSUER` | `rag-in-a-box` | `iss` claim written into and required of JWTs |
| `JWT_AUDIENCE` | `rag-in-a-box` | `aud` claim written into and required of JWTs |
| `PASSWORD_MIN_LENGTH` | `12` | Minimum password length for new and changed passwords and `ADMIN_PASSWORD` |
| `PASSWORD_HASH_ITERATIONS` | `600000` | PBKDF2-HMAC-SHA256 iterations; weaker and legacy hashes are upgraded at the next login |

### API Key and Trusted Proxies

| Variable | Default | Description |
|----------|---------|-------------|
| `API_KEY` | - | Static administrator key, sent as `X-API-Key` or `Authorization: Bearer` (`?api_key=` is no longer accepted). Placeholders are rejected |
| `ALLOW_ADMIN_HEADER` | `false` | With trusted proxy headers: `X-Admin: true` keeps admin rights for a proxied user |
| `AUTH_TRUSTED_PROXY_HEADERS` | `false` | Let administrator credentials act for an end user via `X-User-Id` / `X-Group-Ids` (headers are stripped otherwise). See [trusted proxy headers](permissions.md#trusted-proxy-headers) |

### OAuth / OIDC (`AUTH_MODE=oauth`)

OAuth is configured only when `OAUTH_CLIENT_ID` is set; `AUTH_MODE=oauth` then also needs
`OAUTH_CLIENT_SECRET` and `OAUTH_REDIRECT_URI`, and permission mode (the groups claim maps to
principals). See [OAuth configuration](../oauth-configuration.md) for provider walkthroughs.

| Variable | Default | Description |
|----------|---------|-------------|
| `OAUTH_PROVIDER` | `google` | Preset: `google`, `azure`, `okta`, `keycloak`, `custom` |
| `OAUTH_CLIENT_ID` / `OAUTH_CLIENT_SECRET` | - | Client credentials registered with the provider |
| `OAUTH_REDIRECT_URI` | - | Callback URL, e.g. `https://ragbox.example.com/api/v1/oauth/callback` |
| `OAUTH_AZURE_TENANT_ID` | - | Azure AD tenant (provider `azure`, required) |
| `OAUTH_OKTA_DOMAIN` | - | Okta domain (provider `okta`, required) |
| `OAUTH_OKTA_AUTH_SERVER_ID` | `default` | Okta custom authorization server id (provider `okta`) |
| `OAUTH_KEYCLOAK_BASE_URL` / `OAUTH_KEYCLOAK_REALM` | - | Keycloak server and realm (provider `keycloak`, required) |
| `OAUTH_AUTHORIZATION_ENDPOINT`, `OAUTH_TOKEN_ENDPOINT`, `OAUTH_USERINFO_ENDPOINT`, `OAUTH_JWKS_URI`, `OAUTH_ISSUER` | - | Endpoints of a `custom` provider (all required) |
| `OAUTH_SCOPES` | provider preset (e.g. `openid profile email`) | Scopes to request, space- or comma-separated; must include `openid` |
| `OAUTH_CLAIM_USER_ID` / `OAUTH_CLAIM_EMAIL` / `OAUTH_CLAIM_GROUPS` / `OAUTH_CLAIM_NAME` | `sub` / `email` / `groups` / `name` | ID-token claims mapped to the user |
| `OAUTH_ADMIN_GROUPS` | - | Comma-separated groups whose members are administrators (none by default) |
| `OAUTH_SESSION_STORAGE` | `memory` | Where sessions and login states live: `memory` or `postgres` (needed for more than one replica) |
| `OAUTH_SESSION_COOKIE_NAME` | `ragbox_session` | Session cookie name |
| `OAUTH_SESSION_COOKIE_SECURE` | `true` | `Secure` flag on the cookie (set `false` only for plain-HTTP development) |
| `OAUTH_SESSION_MAX_AGE` | `86400` | Session lifetime in seconds |
| `OAUTH_STATE_TTL` | `300` | Seconds a login may take between redirect and callback |

---

## Production Hardening

### Rate Limiting

| Variable | Default | Description |
|----------|---------|-------------|
| `RATE_LIMIT_ENABLED` | `false` | Enable the per-client request limit |
| `RATE_LIMIT_MAX_REQUESTS` | `100` | Max requests per client per window |
| `RATE_LIMIT_WINDOW_SECONDS` | `60` | Window size in seconds |
| `RATE_LIMIT_TRUSTED_PROXIES` | - | Comma-separated IPs / CIDRs of reverse proxies whose `X-Forwarded-For` / `X-Real-IP` identify the client. From any other peer these headers are ignored |
| `RATE_LIMIT_MAX_CLIENTS` | `10000` | Most clients tracked at once (the oldest windows are evicted beyond it) |
| `RATE_LIMIT_LOGIN_MAX_ATTEMPTS` | `10` | Failed `POST /api/v1/auth/login` attempts allowed per client IP and per username per login window; `0` disables. Applies even with `RATE_LIMIT_ENABLED=false` |
| `RATE_LIMIT_LOGIN_WINDOW_SECONDS` | `300` | Login throttling window |

The limiter runs in front of authentication, so requests that fail authentication count too.
Health checks and `/metrics` are exempt. Without trusted proxies the client is the TCP peer;
behind a proxy (an ingress controller, a load balancer) set `RATE_LIMIT_TRUSTED_PROXIES`, or
every client shares the proxy's address and limit. Behind trusted proxies the client is the
right-most `X-Forwarded-For` address that is not itself a trusted proxy.

### CORS

| Variable | Default | Description |
|----------|---------|-------------|
| `ALLOWED_ORIGINS` | - | Comma-separated origins allowed to call the API from a browser (`https://app.example.com`; a bare host matches any scheme), or `*` for any |
| `CORS_ALLOW_ALL_ORIGINS` | `false` | Allow every origin when `ALLOWED_ORIGINS` is not set |

By default no CORS headers are sent, so browsers only let pages from the server's own origin
read responses. The admin UI is served from the same origin (`/admin`) and needs nothing; in
development the Vite dev server (`npm run dev`, port 3000) proxies `/api`, `/health` and `/ws`
to `localhost:8080`, so it is same-origin as well. An explicit `ALLOWED_ORIGINS` list takes
precedence over `CORS_ALLOW_ALL_ORIGINS`.

The same list guards the `/ws` WebSocket, which browsers open cross-site without CORS (and with
the session cookie): a handshake whose `Origin` is neither the server's own origin (its `Host`, or
`X-Forwarded-Host` from a reverse proxy) nor an allowed origin is refused with `403`. Clients that
send no `Origin` (scripts, CLI tools) are unaffected and authenticate with a token as usual
(`Authorization`, `X-API-Key` or `?access_token=`).

### Request Size

| Variable | Default | Description |
|----------|---------|-------------|
| `REQUEST_SIZE_ENABLED` | `true` | Enable size limiting |
| `MAX_BODY_SIZE_MB` | `10` | Maximum body size in MB |

The limit applies to every request body, whatever the method: a larger `Content-Length` is
refused up front, and bodies without one (chunked) are counted as they are read. Either way the
answer is `413 Payload Too Large`.

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
| `METRICS_REQUIRE_AUTH` | `true` | Serve `/metrics` to administrators only (e.g. `Authorization: Bearer <API_KEY>` or an admin-scoped `rat_` token). `false` makes it public |

---

## Ingestion Sources

Configure automatic document ingestion via HOCON configuration file or environment variables.

### Simple Directory Ingestion

```bash
INGEST_DIR=/data/docs
INGEST_PATTERNS=*.md,*.txt,*.pdf
INGEST_RECURSIVE=true
INGEST_ON_STARTUP=true
INGEST_SCHEDULE="0 2 * * *"  # every day at 02:00, see below
# Directories the API may ingest from (defaults to INGEST_DIR)
INGEST_ALLOWED_ROOTS=/data/docs
```

| Variable | Default | Description |
|----------|---------|-------------|
| `INGEST_DIR` | - | Directory to ingest (a source named `env-directory`) |
| `INGEST_PATTERNS` | `*.md,*.txt,*.pdf` | Comma-separated file patterns |
| `INGEST_RECURSIVE` | `true` | Descend into subdirectories |
| `INGEST_ON_STARTUP` | `false` | Run every configured source once at startup |
| `INGEST_SCHEDULE` | - | When to run every configured source (see [Schedules](#schedules)) |
| `INGEST_SCHEDULE_TZ` | server time zone | Time zone of a cron schedule: an IANA name (`Europe/London`, `UTC`) or an offset (`+02:00`) |

`INGEST_DIR` adds a directory source named `env-directory` to the sources configured in HOCON
(and enables ingestion). `INGEST_ON_STARTUP` and `INGEST_SCHEDULE` set `run-on-startup` and
`schedule`, which apply to **every** configured source, with or without `INGEST_DIR`.

Source names must be unique: each source's documents are tracked by its name, so startup
fails when two configured sources share one (including a HOCON source named `env-directory`
while `INGEST_DIR` is set, and two unnamed sources of the same `type`, which are named after it).

### Schedules

`schedule` / `INGEST_SCHEDULE` is one of:

- a standard five-field **cron expression** (minute, hour, day of month, month, day of week;
  Sunday is `0` or `7`), evaluated in `INGEST_SCHEDULE_TZ` / `schedule-time-zone` (default:
  the server's time zone), e.g.
  `0 2 * * *` (02:00 every day), `*/30 * * * *`, `0 6,18 * * 1-5`
- `hourly`, `daily`, `weekly`: `0 * * * *`, `0 0 * * *` (midnight), `0 0 * * 0` (Sunday midnight)
- an **interval** between runs, counted from server start: `30m`, `6h`, `1d` (units `s`, `m`, `h`, `d`)

An invalid schedule or time zone fails startup. A run that is still going when the next one is due delays
nothing: the missed slot is skipped. `GET /api/v1/ingest/status` reports `nextScheduledRun`.
Runs can always be started with `POST /api/v1/ingest/run`.

### Several replicas

Replicas sharing a database coordinate their ingestion runs, so `INGEST_SCHEDULE` and
`INGEST_ON_STARTUP` can be set on every replica:

- Each source has a cluster-wide lock, a PostgreSQL session-level advisory lock. A run takes
  the locks of every source it will sync before it starts, all or nothing: a run of all
  sources (scheduled, at startup, or `POST /api/v1/ingest/run`) takes every enabled source's,
  a run of one source (`POST /api/v1/ingest/run/{source}`) only that source's, and a one-shot
  `POST /api/v1/ingest/directory` that of its path.
- A run that finds a lock held, by another replica or another run on the same one, does
  nothing: a scheduled or startup run is skipped with an info log; an API call answers
  `409 Conflict`. So replicas whose schedules fire together run each source once, single sources
  run in parallel on different replicas, and a run of all sources is refused while any of them
  is running elsewhere. One-shot `POST /api/v1/ingest/url` runs are not coordinated (they only
  upsert).
- Locks are released when a run ends, however it ends (success, failure, cancellation). They
  live in the database session, so a replica that crashes or loses its connection loses its
  locks with it.
- Each running run holds its lock on a **dedicated connection**, opened when the run starts and
  closed when it ends, outside the `DB_POOL_SIZE` pool (so a long run never ties up a pooled
  connection). Allow for them in PostgreSQL's `max_connections`: per replica, `DB_POOL_SIZE`
  plus one for a run of the configured sources plus one per concurrent one-shot directory
  ingest. Connection poolers in transaction mode (e.g. PgBouncer `pool_mode = transaction`) do
  not keep session-level locks; connect ragbox to PostgreSQL directly or in session mode.
- `GET /api/v1/ingest/status` reports `running` for the replica that answers.

### Advanced Configuration (HOCON)

For complex setups, use `application.conf`:

```hocon
ingestion {
  enabled = true
  run-on-startup = false
  schedule = "daily"
  schedule-time-zone = "Europe/London"  # INGEST_SCHEDULE_TZ; default: the server's

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
| `INGEST_URL_ALLOW_PRIVATE_NETWORKS` | `ingestion.url.allow-private-networks` | `false` | Allow hosts on loopback, private and other non-public addresses, to ingest from an intranet. Cloud metadata addresses stay refused. |

**Directories.** A path is accepted only if it is under an allowed root once symlinks are
resolved (`toRealPath`): `..` segments are refused outright, and a symlink that points outside the
roots is refused. While walking the directory, llm4s's `DirectoryLoader` follows a symlink only when
its target stays inside the requested directory, and reads only regular files. `patterns` must be
file extensions such as `*.md`; patterns containing `/`, `\` or `..` are refused.

**URLs.** Only `http` and `https` are accepted, without embedded credentials. The host is
resolved and every address must be public: loopback, private (RFC 1918, IPv6 ULA `fc00::/7`),
link-local (including the cloud metadata endpoints `169.254.169.254` and `fd00:ec2::254`),
unspecified, multicast, CGNAT `100.64.0.0/10`, reserved ranges and IPv6 forms embedding such an
IPv4 address (e.g. `[::ffff:127.0.0.1]`) are refused, as are numeric spellings such as
`http://2130706433/` or `http://0x7f.1/` and names like `localhost`.

The policy is an llm4s `UrlPolicy` that the URL loader and the web crawler enforce themselves, on
every request they make: each configured URL, each crawled page, each redirect hop and each
`robots.txt`. They connect to the address the policy vetted, so a host cannot pass the check and
then resolve somewhere else (DNS rebinding). ragbox also checks the URLs of a request up front,
to refuse it with a reason (`400`). A crawled page the policy refuses is skipped, and removed if
it was ingested before.

The loaders connect over their own socket, not through a proxy: `http.proxyHost` and
`https.proxyHost` do not apply to them.

All `/api/v1/ingest/*` endpoints are administrator-only (`403` for anyone else).

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
export ADMIN_PASSWORD="$(openssl rand -base64 18)"   # 12+ characters
export JWT_SECRET="$(openssl rand -base64 32)"

# Database
export DATABASE_URL=postgresql://rag:password@db:5432/ragdb

# API keys
export OPENAI_API_KEY=sk-...

# Hardening
export RATE_LIMIT_ENABLED=true
export RATE_LIMIT_TRUSTED_PROXIES=10.0.0.0/8   # your ingress / load balancer
export METRICS_ENABLED=true                     # /metrics for administrators only
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

# RAG in a Box

A self-hosted RAG (retrieval-augmented generation) server with an admin UI, built on
[llm4s](https://github.com/llm4s/llm4s). Start it next to PostgreSQL + pgvector, point it at
documents, and query them over a REST API, a chat UI or the Python SDK.

RAG in a Box is also the reference application for llm4s: it uses the library the way a
production service would, and the gaps it finds go back upstream as llm4s issues.

Full documentation: **[docs/](docs/index.md)** (published with Jekyll). API reference: the
OpenAPI spec at [`admin-ui/public/openapi.yaml`](admin-ui/public/openapi.yaml), browsable in the
admin UI under **API Docs** (`/admin/api-docs`).

## Features

- **Ingestion**: JSON and file upload (PDF, DOCX, HTML, Markdown, text, ...), idempotent upserts
  with content-hash change detection, and built-in sources (directory, URL, web crawler,
  database, S3) with scheduled runs; each source syncs its own documents, so files deleted at the
  source are removed from the index.
- **Retrieval**: hybrid search (pgvector + PostgreSQL full-text) with RRF or weighted fusion,
  optional Cohere or LLM reranking and LLM query rewriting, and answer generation with any llm4s
  LLM provider.
- **Chat**: multi-turn sessions with history, ratings and server-sent-event responses.
- **Permissions**: users, groups and hierarchical collections with `queryableBy` /
  `readableBy`; every query is filtered by what the caller may read.
- **Authentication**: open, basic (username/password with JWTs) or OAuth2/OIDC (Google, Azure AD,
  Okta, Keycloak, custom), plus a static API key and scoped `rat_` access tokens for ingesters.
- **Tuning**: runtime settings without a restart (top-K, fusion, prompt, temperature, chunking),
  per-collection chunking with file-type overrides, chunking preview/compare, and chunk
  visibility.
- **Evaluation**: a query log with per-stage latency, tokens and cost; feedback; optimization
  suggestions; A/B experiments with significance tests (Welch's t-test, two-proportion z-test).
- **Operations**: Flyway migrations, a shared HikariCP pool, health/readiness probes,
  Prometheus metrics, rate limiting, request-size limits, CORS, structured logs, Docker image,
  Helm chart and Kubernetes manifests.
- **Clients**: Vue 3 + Vuetify admin UI at `/admin`, Python SDK, OpenAPI spec.

### llm4s features showcased

| llm4s feature | Where ragbox uses it |
|---|---|
| Provider registry (`ProviderRegistry`, ServiceLoader provider modules) | Every LLM and embedding client is resolved by provider id (`openai/gpt-4o`, `anthropic/...`, `voyage`, `ollama`, or any module on the classpath); nothing in ragbox matches on provider names (`Llm4sPipeline`). |
| `RAG` pipeline (`RAG.queryWithAnswer`, `RAGConfig`) | Search and answer generation; pipelines are pooled by their settings (`PipelinePool`). |
| PostgreSQL hybrid search (pgvector + full-text) and `FusionStrategy` | Default search mode with RRF or weighted fusion. |
| Permission-aware `PgSearchIndex`, `PrincipalStore`, collections | Users, groups and collection permissions; permission-filtered retrieval. |
| Rerankers (`CohereReranker`, LLM reranking) and `LLMQueryRewriter` | `RAG_RERANKING_STRATEGY`, `RAG_QUERY_TRANSFORM`. |
| Chunkers (`ChunkerFactory`: simple, sentence, markdown, semantic) | Ingestion, per-collection chunking and the chunking preview. |
| `TikaDocumentExtractor` | `POST /api/v1/documents/upload`. |
| Loaders (`FileLoader`, `UrlLoader`, `WebCrawlerLoader`, `S3Loader`) | Built-in ingestion sources. |
| `NetworkSecurity` | SSRF policy for URL and crawler ingestion. |
| Tracing (`Tracing`, `TracingLLMClient`) and the bundled model metadata (`ModelRegistryService`) | Per-query token, latency and cost accounting in the query log. |
| `Result` / typed errors, `Secret` | Error handling and secret handling throughout. |

Where llm4s does not yet do what ragbox needs, ragbox works around it and links the upstream
issue; see [ROADMAP.md](ROADMAP.md).

## Quick start

### Docker Compose

Needs Docker and an OpenAI API key (or change the models; see below).

```bash
git clone https://github.com/llm4s/rag_in_a_box.git
cd rag_in_a_box

cp .env.example .env          # then set OPENAI_API_KEY in .env
docker compose up -d --build  # builds the admin UI and the server into one image

curl http://localhost:8080/health
open http://localhost:8080/admin
```

Compose starts PostgreSQL + pgvector (host port 15432) and the server (port 8080) in **open
mode**: no authentication, every request is an administrator. That is fine on your own machine,
not anywhere else; see [Authentication](#authentication).

`docker-compose.yml` passes a fixed list of variables to the server (models, API keys, chunking
and search settings, `API_KEY`, ingestion, logging, rate limits). To use others, such as
`AUTH_MODE`, `JWT_SECRET`, `ADMIN_PASSWORD`, `COHERE_API_KEY` or the `OAUTH_*` settings, add them
to the `api` service's `environment:` block. Files in `./data` (or `INGEST_HOST_DIR`) are
mounted at `/data` for directory ingestion.

### Local development

Needs Java 21, sbt, Node.js 20+ and Docker.

```bash
# PostgreSQL with pgvector (the init script only creates the extension; Flyway creates the schema)
docker run -d --name ragbox-postgres \
  -e POSTGRES_USER=rag -e POSTGRES_PASSWORD=rag -e POSTGRES_DB=ragdb \
  -v $(pwd)/scripts/init-db.sql:/docker-entrypoint-initdb.d/01-init.sql \
  -p 15432:5432 pgvector/pgvector:pg15

cp .env.example .env   # set OPENAI_API_KEY

# Build the admin UI once: sbt copies admin-ui/dist into the server's resources.
# Without it the API works but /admin is empty (sbt warns "Admin UI dist not found").
(cd admin-ui && npm ci && npm run build)

./run.sh               # loads .env and runs `sbt run`
```

For UI work, run `npm run dev` in `admin-ui/` instead: a Vite dev server on
http://localhost:3000 that proxies API calls to the server on port 8080.

### First requests

```bash
# Add a document
curl -X POST http://localhost:8080/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{"content": "PostgreSQL is a powerful open-source relational database.", "filename": "pg.txt"}'

# Upload a file
curl -X POST http://localhost:8080/api/v1/documents/upload -F file=@handbook.pdf -F collection=docs

# Ask a question
curl -X POST http://localhost:8080/api/v1/query \
  -H "Content-Type: application/json" \
  -d '{"question": "What is PostgreSQL?"}'

# Retrieve chunks without generating an answer
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{"query": "relational database", "topK": 5}'
```

With authentication on, add `-H "Authorization: Bearer $TOKEN"` (an `API_KEY`, a `rat_` access
token, or a JWT from `POST /api/v1/auth/login`).

## Authentication

Set `AUTH_MODE`:

| Mode | Who can call the API | Typical use |
|---|---|---|
| `open` (default) | Everyone, as administrator. With `API_KEY` set, only holders of that key or a `rat_` token. | Local development; a single trusted client |
| `basic` | Users who log in with a password (`POST /api/v1/auth/login` returns a JWT, valid for `JWT_EXPIRATION`, default 24 h); the API key and `rat_` tokens. Needs `JWT_SECRET` (at least 32 characters) and `ADMIN_PASSWORD` (bootstrap admin `ADMIN_USERNAME`, default `admin`). | Small teams |
| `oauth` | Users who sign in through an OIDC provider (session cookie); the API key and `rat_` tokens. Needs the PostgreSQL permission index. | Organisations with SSO |

- **Access tokens** (`rat_...`, created by an admin with `POST /api/v1/tokens`) carry scopes
  (`documents:read`, `documents:write`, `sync:read`, `sync:write`, `query`, `admin`) and can be
  restricted to collections. Use them for ingesters and integrations.
- **Admin-only** endpoints: users, tokens, principals, ingestion, visibility, analytics,
  suggestions, experiments, runtime-config changes, collection writes, `/metrics` and deleting
  every document.
- **Trusted proxy**: with `AUTH_TRUSTED_PROXY_HEADERS=true`, an admin-authenticated gateway can
  act for an end user with `X-User-Id` / `X-Group-Ids`. Otherwise those headers are stripped.

See the [authentication guide](docs/guide/authentication.md),
[access tokens](docs/guide/access-tokens.md), [permissions](docs/guide/permissions.md) and
[OAuth configuration](docs/oauth-configuration.md).

## API overview

All endpoints are under `/api/v1` except health, metrics and the WebSocket. The authoritative
reference, with request and response schemas and the auth rule of every operation, is
[`openapi.yaml`](admin-ui/public/openapi.yaml) (a test fails when a route is missing from it).
"Admin" means administrators only; "Auth" means any authenticated caller (access tokens need the
named scope).

| Area | Endpoints | Auth |
|---|---|---|
| Health | `GET /health`, `/health/ready`, `/health/live` | Public |
| Metrics | `GET /metrics` (Prometheus; `METRICS_ENABLED=true`) | Admin, or public with `METRICS_REQUIRE_AUTH=false` |
| Login | `GET /auth/mode`, `POST /auth/login` (basic mode) | Public |
| OAuth | `GET /oauth/login`, `GET /oauth/callback`, `POST /oauth/logout`, `GET /oauth/userinfo` (oauth mode) | Public / session |
| Current user | `GET /auth/me`, `PUT /auth/password` | Auth |
| Users | `GET`, `POST /users`; `DELETE /users/{id}`; `PUT /users/{id}/password` | Admin |
| Access tokens | `GET`, `POST /tokens`; `GET`, `DELETE /tokens/{id}` | Admin |
| Documents | `GET`, `POST`, `DELETE /documents`; `POST /documents/upload`; `GET`, `PUT`, `DELETE /documents/{id}`; `GET /stats` | Auth (`documents:read` / `documents:write`); `DELETE /documents?all=true` is Admin |
| Sync | `GET /sync/status`; `POST /sync`; `GET /sync/documents`, `/sync/documents/{id}`; `POST /sync/check` | Admin, or tokens with `sync:read` / `sync:write` |
| Query | `POST /query`, `/query/stream` (SSE), `/search` | Auth (`query`) |
| Chat | `GET`, `POST /chat/sessions`; `GET`, `PUT`, `DELETE /chat/sessions/{id}`; `POST /chat/sessions/{id}/messages`, `.../messages/stream`; `PUT /chat/messages/{id}/rate` | Auth (`query`), own sessions only |
| Collections | `GET /collections`, `/collections/accessible`, `/collections/{path}`, `/collections/{path}/stats`; `POST /collections`; `PUT /collections/{path}/permissions`; `DELETE /collections/{path}` | Reads: Auth (filtered); writes: Admin |
| Collection chunking | `GET /collections/configs`; `GET`, `PUT`, `DELETE /collections/{c}/config`; `POST /collections/{c}/config/preview` | Reads: Auth; writes and preview: Admin |
| Principals | `GET`, `POST /principals/users`, `/principals/groups`; `GET /principals/lookup/{id}`; `DELETE /principals/{type}/{id}` | Admin |
| Configuration | `GET /config`, `/config/providers`, `/config/runtime`, `/config/runtime/meta`; `PUT /config/runtime`; `POST /config/runtime/validate`; `GET /config/runtime/history` | Reads: Auth; changes and history: Admin |
| Chunking | `POST /chunking/preview`, `/chunking/compare`; `GET /chunking/strategies`, `/chunking/presets` | Auth |
| Ingestion | `POST /ingest/directory`, `/ingest/url`, `/ingest/run`, `/ingest/run/{source}`; `GET /ingest/status`, `/ingest/sources` | Admin |
| Visibility | `GET /visibility/config`, `/visibility/stats`, `/visibility/collections`, `/visibility/chunks`, `/visibility/chunks/{docId}`, `/visibility/chunks/{docId}/{index}` | Admin |
| Analytics | `GET /analytics/queries`, `/analytics/queries/summary`, `/analytics/queries/{id}`; `GET /analytics/suggestions`, `.../summary`, `.../collection/{name}` | Admin |
| Feedback | `POST /feedback` | Auth |
| Experiments | `GET`, `POST /experiments`; `GET /experiments/running`, `/experiments/{id}`, `/experiments/{id}/results`; `PUT /experiments/{id}/start`, `/stop`, `/archive`; `DELETE /experiments/{id}` | Admin |
| WebSocket | `GET /ws` (ingestion progress, stats updates; `?access_token=` accepted; browser `Origin` must be same-origin or in `ALLOWED_ORIGINS`) | Auth |

Errors are JSON: `{"error": "<code>", "message": "...", "details": "..."}` (`details` is
optional). Invalid bodies and query parameters are 400s that name the problem; a 500 never
carries exception text (the cause is in the server log).

## Configuration

Configuration comes from environment variables (read through
[`application.conf`](src/main/resources/application.conf)). [`.env.example`](.env.example)
lists every variable with its default; the
[configuration guide](docs/guide/configuration.md) explains them. The most common:

| Variable | Default | Purpose |
|---|---|---|
| `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `VOYAGE_API_KEY`, `COHERE_API_KEY`, `<PROVIDER>_API_KEY` | - | Provider API keys (any llm4s provider) |
| `LLM_MODEL` | `openai/gpt-4o` | Answer model, `<provider>/<model>` |
| `EMBEDDING_PROVIDER` / `EMBEDDING_MODEL` | `openai` / `text-embedding-3-small` | Embeddings (changing them needs a re-index) |
| `PG_HOST`, `PG_PORT`, `PG_DATABASE`, `PG_USER`, `PG_PASSWORD` | `localhost`, `15432`, `ragdb`, `rag`, `rag` | Database; or `DATABASE_URL` |
| `AUTH_MODE` | `open` | `open`, `basic` or `oauth` |
| `API_KEY` | - | Static admin key (`X-API-Key` or `Authorization: Bearer`) |
| `RAG_CHUNKING_STRATEGY`, `RAG_CHUNK_SIZE`, `RAG_CHUNK_OVERLAP` | `sentence`, `800`, `150` | Default chunking |
| `RAG_TOP_K`, `RAG_FUSION_STRATEGY` | `5`, `rrf` | Retrieval |
| `RAG_RERANKING_STRATEGY`, `RAG_QUERY_TRANSFORM` | `none`, `none` | Reranking (`cohere`, `llm`) and query rewriting (`llm-rewrite`) |
| `INGEST_DIR`, `INGEST_SCHEDULE` | - | Directory source; cron schedule (`0 2 * * *`, `daily`) or interval (`6h`) for all sources |
| `ALLOWED_ORIGINS` | - | CORS origins, also allowed to open `/ws` (same-origin only when unset) |

Runtime settings changed through the API or the admin UI are stored in the database and survive
restarts.

## Python SDK

```bash
pip install ./sdk/python
```

```python
from ragbox import RagBoxClient, Document

client = RagBoxClient("http://localhost:8080", api_key="rat_...")  # or RAGBOX_API_KEY
client.upsert(Document(id="doc-1", content="First document"))
print(client.query("What is in the documents?").answer)
client.sync(keep_ids=["doc-1"])  # prune documents no longer at the source
```

See [sdk/python/README.md](sdk/python/README.md).

## Deployment

- **Docker**: the [Dockerfile](Dockerfile) builds the admin UI and the server into one image
  (published as `ghcr.io/llm4s/rag_in_a_box` by CI).
- **Kubernetes**: a [Helm chart](deploy/helm/ragbox) and plain [manifests](deploy/kubernetes);
  see the [Helm guide](docs/guide/helm-deployment.md).
- **Production checklist**: [docs/production-deployment.md](docs/production-deployment.md).

## Development

```bash
sbt test                 # unit tests; integration specs use Testcontainers (Docker)
sbt assembly             # fat JAR in target/scala-3.7.1/
cd admin-ui && npm run test:run   # admin UI unit tests (npm run test:e2e: Playwright)
cd sdk/python && pip install '.[dev]' && pytest
```

[DESIGN.md](DESIGN.md) describes the architecture, [ROADMAP.md](ROADMAP.md) what is done and
planned, and [CONTRIBUTING.md](CONTRIBUTING.md) how to contribute.

## License

MIT; see [LICENSE](LICENSE).

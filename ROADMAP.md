# RAG in a Box: Roadmap

## Vision

A turnkey RAG server: start it next to PostgreSQL, point it at documents, and get good retrieval
straight away, with permissions, authentication and external ingesters when you need them. It is
also the reference application for [llm4s](https://github.com/llm4s/llm4s), so it should use
llm4s the way a production service would and send what it learns back upstream.

This file records what works today, what is known not to, and what is planned. Open work is
tracked in GitHub issues; the November 2026 codebase review is tracked in #27.

---

## Done

Each item works end to end and is covered by tests. Pull request numbers are given where the
behaviour was last reworked.

### RAG pipeline (llm4s)
- Every LLM and embedding provider resolved through the llm4s `ProviderRegistry` (OpenAI,
  Anthropic, Voyage, Ollama, or any provider module on the classpath) (#39)
- Hybrid search: pgvector + PostgreSQL full-text with RRF or weighted fusion; vector-only and
  keyword-only modes (#39)
- Optional reranking (Cohere or LLM) and LLM query rewriting (#39)
- Chunking with llm4s chunkers (simple, sentence, markdown, semantic); each document is chunked
  once, with the chunking its collection resolves to (#39, #42)
- File upload with text extraction through llm4s's `TikaDocumentExtractor` (#39)
- Per-query latency, token and cost accounting from llm4s tracing and the bundled model
  metadata (#39)
- Chat sessions with conversation history (#39, #41)
- Pipelines pooled by their settings (`PipelinePool`), so runtime changes take effect without a
  restart and in-flight requests are never cut off (#42)

### Documents and ingestion
- Document API: add, idempotent upsert with content hashes, list with paging and search, batch
  delete, clear-all only with `?all=true` (#28)
- Sync API for external ingesters: status, document states (`include`, `since`), batch check,
  prune with dry run; scoped access tokens (#33, #38)
- Built-in sources: directory, URL, web crawler, database (JDBC) and S3, on an interval
  schedule; each source syncs only its own documents, and the bookkeeping survives restarts (#33)
- Ingestion policy: directory ingestion only under allowed roots; URL and crawler ingestion only
  to public addresses unless allowed (SSRF) (#37)

### Permissions and security
- Users, groups and hierarchical collections (`queryableBy` / `readableBy`) on the llm4s
  permission-aware search index; every query filtered by the caller's permissions (#32)
- One authenticator for every protected route: static API key, `rat_` access tokens with scopes
  and collection restrictions, JWTs (basic mode) and OIDC sessions (oauth mode) (#38)
- Authorization: admin-only endpoint groups, filtered reads, chat sessions owned by their
  creator, trusted-proxy identity headers only when enabled (#41)
- OIDC login: Google, Azure AD, Okta, Keycloak or custom; PKCE, browser-bound state, nonce,
  `iss`+`sub` identities, in-memory or PostgreSQL sessions (#35)
- Hardening: CORS same-origin by default, required `JWT_SECRET` and `ADMIN_PASSWORD` in basic mode,
  PBKDF2 password hashing, login throttling, bounded rate limiter, streaming body-size limit,
  authenticated `/metrics` by default (#44)

### Tuning and evaluation
- Runtime settings (top-K, fusion, RRF k, system prompt, temperature, chunking) changed through
  the API or UI, persisted with history (#42)
- Per-collection chunking with file-type overrides, and a preview of the effective config (#42)
- Chunking preview and strategy comparison; chunk and statistics visibility
- Query log, feedback, analytics summary (latency percentiles, cost) and optimization
  suggestions
- A/B experiments: variants run with their full configuration; Welch's t-test and
  two-proportion z-tests on the results (#43)

### Persistence and operations
- One HikariCP pool for every component; blocking JDBC on the blocking pool; transactional
  writes (#36)
- Flyway migrations, safe for several replicas starting together (#46)
- `RAGService` split into focused services; one error-handling layer (no exception text in
  responses, 400 for malformed bodies and query parameters, typed service errors); derived
  codecs; paged `GET /api/v1/sync/documents` (#50)
- `DATABASE_URL` parsing (#29); health, readiness and liveness probes; Prometheus metrics;
  structured JSON logs
- Docker image (admin UI and server), Docker Compose, Helm chart with a pgvector StatefulSet,
  Kubernetes manifests (#31)
- CI: backend tests with Testcontainers, admin UI build and unit tests, Playwright end-to-end
  tests against a real backend, Python SDK tests, release workflow (#30, #49)

### Clients and docs
- Vue 3 admin UI: dashboard, documents, upload, chat, collections and permissions, users,
  tokens, configuration, chunking, visibility, ingestion, analytics, suggestions, experiments,
  API docs; login for basic and OIDC modes (#34, #38)
- OpenAPI spec covering every route, kept complete by a test (#26)
- Python SDK with API-key/token authentication and a test suite (#26)
- Documentation site (Jekyll) (#26)

---

## Known limitations

These are limits of the current llm4s release (0.6.0) that ragbox works around or documents.
Most are fixed or proposed upstream; see #40.

| Limitation | Effect in ragbox | Upstream |
|---|---|---|
| RAG answers take no completion options | The answer temperature is fixed: `LLM_TEMPERATURE`, the runtime `llmTemperature` and the experiment-variant temperature do not reach `/query` or chat | llm4s/llm4s#2715 (fixed on llm4s `main`, unreleased) |
| No streaming answer API | `/query/stream` and chat streaming send the answer as one event once it is complete | llm4s/llm4s#2717 (fixed on `main`, unreleased) |
| Permission-mode search is vector-only | In permission mode, fusion, keyword search, reranking and query rewriting do not apply (reported in `GET /api/v1/config` as `searchMode` / `fusionApplies`); ragbox routes ingests without a collection to `default` itself | llm4s/llm4s#2716 (fixed on `main`, unreleased) |
| No persistent registry or source-scoped sync | ragbox keeps its own document registry and per-source sync state | llm4s/llm4s#2714 |
| Loader URL policy | ragbox checks URLs before handing them to the llm4s loaders; robots.txt fetches and DNS rebinding remain residual risks | llm4s/llm4s#2724 |
| No pre-chunked ingest with permissions | ragbox embeds its own chunks and writes them through `SearchIndex.ingest` | llm4s/llm4s#2732 |
| No chat history in `queryWithAnswer` | ragbox builds the conversation for chat itself | llm4s/llm4s#2733 |
| Tracing covers embeddings only | ragbox wraps the LLM client to trace the answer, rerank and rewrite calls | llm4s/llm4s#2734 |

Other known gaps (tracked in issues): `/ws` does not check the `Origin` header (#45).

---

## Planned

### Next
- **Adopt the next llm4s release** (#40): completion options, real streaming answers,
  permission-mode hybrid search; drop the workarounds above as upstream issues land
  (llm4s/llm4s#2714, #2724, #2732, #2733, #2734).
- **Test gaps** (#48): route and service specs for chat, experiments, suggestions, the
  WebSocket and `RAGService`; a V3 migration dropping a duplicate index; a Helm migration job.
- **WebSocket origin check** (#45).

### Later
- Real-time directory watching (NIO `WatchService`) with debouncing
- More connectors: GCS, Azure Blob, Confluence, SharePoint, Google Drive
- Cron schedules for ingestion (today schedules are intervals; see the configuration guide)
- Query-log ids in query responses, so API clients can send feedback
- Evaluation datasets and offline scoring (RAGAS-style) alongside the online experiments

---

## Decisions

| Decision | Choice |
|---|---|
| Authentication | `AUTH_MODE` = open, basic or oauth; one authenticator in front of every protected route |
| Ingester credentials | `rat_` access tokens with scopes and optional collection restrictions |
| Default mode | Open, for local use; anything shared should use basic or oauth |
| Vector store | PostgreSQL + pgvector through llm4s; hybrid search with PostgreSQL full-text |
| Schema | Flyway migrations owned by ragbox; llm4s owns its vector, keyword and permission tables |
| Providers | Whatever llm4s's provider registry offers; no provider names hard-coded in ragbox |
| Schedules | Intervals (`30m`, `6h`, `daily`), not cron |
| Docs | Jekyll site in `docs/`, OpenAPI spec in `admin-ui/public/openapi.yaml` |

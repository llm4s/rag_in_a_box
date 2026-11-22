# RAG in a Box: Design

How the server is put together today. For what it does, see the [README](README.md); for what
is planned, the [ROADMAP](ROADMAP.md); for how to extend it, [docs/EXTENDING.md](docs/EXTENDING.md).

## Goals

1. **Turnkey**: one container next to PostgreSQL + pgvector gives a working RAG API and admin UI.
2. **Good retrieval by default**: hybrid search, sensible chunking, tunable at runtime.
3. **Safe to share**: real authentication and authorization, permission-filtered retrieval,
   ingestion that cannot be turned against the host.
4. **An llm4s showcase**: use llm4s idiomatically for everything RAG-related; where it falls
   short, work around it in one place and raise an upstream issue.

## Architecture

```
                 ┌───────────────────────────── ragbox (one JVM, http4s Ember) ─────────────────────────────┐
 Browser ──/admin──▶ StaticRoutes (Vue admin UI from the classpath)                                         │
 Clients ──/api/v1─▶ RequestLogger ▶ CORS ▶ RequestSize ▶ RateLimit ▶ public routes                         │
 Ingesters         │                                                 └▶ Authenticator ▶ Authz.tokenAllowlist │
 /ws               │                                                       ▶ AuthedRoutes[Principal]        │
                   │                                                                                         │
                   │  Routes ──▶ RAGService ──▶ PipelinePool ──▶ llm4s RAG (Llm4sPipeline)                  │
                   │         │               ├─▶ ChunkingService / CollectionConfigRegistry                  │
                   │         │               ├─▶ DocumentRegistry, ChunkStore                                │
                   │         │               └─▶ llm4s SearchIndex (permission mode)                         │
                   │         ├─▶ IngestionService (+ IngestionScheduler, IngestionPolicy)                    │
                   │         ├─▶ ExperimentService, SuggestionService, QueryLogRegistry                      │
                   │         ├─▶ ChatSessionRegistry, RuntimeConfigManager                                   │
                   │         └─▶ AuthService, UserRegistry, AccessTokenRegistry, OidcService                 │
                   └──────────────────────────────── one HikariCP pool ──────────────────────────────────────┘
                                                        │
                                   PostgreSQL + pgvector (Flyway-managed ragbox tables,
                                   llm4s-owned vector / keyword / permission tables)
                                                        │
                                   LLM, embedding and reranking providers (via llm4s)
```

## Technology

| Layer | Choice |
|---|---|
| Language, runtime | Scala 3.7, Java 21 |
| HTTP | http4s (Ember server and client), circe |
| Effects | cats-effect 3 (`IO`, `Resource`, `Supervisor`) |
| RAG | llm4s 0.6.0 (`llm4s-rag` plus the OpenAI, Anthropic, Voyage and Ollama provider modules) |
| Database | PostgreSQL 15+ with pgvector; plain JDBC over HikariCP; Flyway migrations |
| Admin UI | Vue 3, Vuetify 3, Pinia, Vite, Swagger UI (served at `/admin`) |
| Tests | ScalaTest, Testcontainers (PostgreSQL, Keycloak), Vitest, Playwright |
| Packaging | sbt-assembly fat JAR, multi-stage Docker image, Helm chart |

## Startup and wiring

`Main` builds everything as a `Resource`, so shutdown releases it in reverse order:

1. Load and validate `AppConfig` (`application.conf` + environment). Invalid combinations
   (basic mode without `JWT_SECRET`, placeholder secrets, Cohere reranking without a key, ...)
   stop startup with a list of errors.
2. Create the **connection pool** (`db/ConnectionPool`, HikariCP, `DB_POOL_SIZE`).
3. Run **Flyway migrations** (`db/Migrations`) unless `DB_MIGRATE_ON_START=false`;
   `--migrate-only` runs them and exits, for deploy jobs.
4. Create the llm4s **`PgSearchIndex`** over the same pool (permission mode). If it cannot be
   created, the server falls back to legacy mode with a warning, or fails when
   `PERMISSIONS_REQUIRED=true`.
5. Create `RuntimeConfigManager`, `PgCollectionConfigRegistry` and **`RAGService`**.
6. `RagboxApp.resource` builds the rest (registries, services, OIDC components in oauth mode,
   rate limiters) and assembles the routes; `RagboxApp.httpApp` is what Ember serves. Tests
   build the same `RagboxApp` over a Testcontainers database, so they exercise the real stack.
7. Background tasks under a `Supervisor`: the ingestion schedule and OAuth session cleanup.
   Startup ingestion runs if configured.

## HTTP stack

`RagboxApp.httpApp`, from the outside in:

1. **Identity-header stripping**: `X-User-Id`, `X-Group-Ids` and `X-Admin` are removed unless
   `AUTH_TRUSTED_PROXY_HEADERS=true`.
2. **Request logger**: method, path, status and latency; credentials are redacted;
   `LOG_FORMAT=json` for structured logs.
3. **CORS**: same-origin only unless `ALLOWED_ORIGINS` (or `CORS_ALLOW_ALL_ORIGINS`) is set.
4. **Request size**: bodies over `MAX_BODY_SIZE_MB` are rejected with 413 while streaming.
5. **Rate limiting**: an optional per-client limit, plus login throttling per IP and per
   username; client IPs come from `X-Forwarded-For` only for `RATE_LIMIT_TRUSTED_PROXIES`.
6. **Error handling** (`routes/ErrorHandling`), around all routes: body decode failures → 400
   (`details` names the field), wrong media type → 415, typed `ServiceError`s
   (`InvalidRequestError` 400, `NotFoundError` 404, `ConflictError` 409, `LlmUnavailableError`
   400 `configuration_error`), anything else → a generic 500 whose cause is logged, never
   returned. Query parameters are validated by `routes/Params` (400 naming the parameter;
   paging clamped).
7. **Public routes**: health, `/api/v1/auth/login` and `/auth/mode`, OAuth endpoints (oauth
   mode), admin UI assets, and `/metrics` when `METRICS_REQUIRE_AUTH=false`.
8. **Authenticator** (`middleware/Authenticator`), in front of everything else.
9. **`Authz.tokenAllowlist`**: access tokens without the `admin` scope only reach documents,
   stats, sync, query/search, chat, `GET /api/v1/collections`, `/auth/me` and `/ws`.
10. **Routes**: `AuthedRoutes[Principal, IO]`, one object per area in `routes/`.

### Authentication

One `Authenticator` turns a request's credentials into a `Principal` (`auth/Principal`):

| Credential | Where | Modes |
|---|---|---|
| none | - | open mode without `API_KEY`: every request is `Principal.OpenModeAdmin` |
| static `API_KEY` | `X-API-Key`, or `Authorization: Bearer` | any mode where it is set |
| `rat_` access token | `Authorization: Bearer` | all (validated against `access_tokens`, last use recorded) |
| JWT | `Authorization: Bearer` | basic (HMAC-signed by `AuthService`; the user is re-read on every request, so deletions and demotions apply at once) |
| OIDC session cookie | `ragbox_session` | oauth (`OidcService` + `SessionRegistry`, memory or PostgreSQL) |
| `?access_token=` | query string, `/ws` only | as the bearer credential it carries |

The first credential found is used, and an invalid one is a 401 even if another would have been
valid. With `AUTH_TRUSTED_PROXY_HEADERS=true`, an *admin* principal (a gateway holding the API
key) may act for an end user with `X-User-Id` / `X-Group-Ids`; `X-Admin: true` additionally
needs `ALLOW_ADMIN_HEADER=true`.

**OIDC** (`auth/Oidc*`, `routes/OAuthRoutes`): authorization-code flow with PKCE, a state bound
to the browser by a pre-auth cookie, a nonce, ID-token validation against the provider's JWKS
(cached), and identities keyed by `iss|sub` (or a verified email). `ClaimMapper` turns claims into
llm4s principals (user and groups) so collection permissions apply; `OAUTH_ADMIN_GROUPS` makes
group members administrators. Provider presets: Google, Azure AD, Okta, Keycloak, custom.

### Authorization

`routes/Authz` holds the combinators the routes use:

- `requireAdmin`; `adminOnlyWhen(rule)` for whole groups (`/principals`, `/ingest`,
  `/visibility`, `/analytics`, `/experiments`) or for every mutating request in a group
  (`/collections`, `/config`, runtime-config history);
- `requireScope` / `scopeForPaths` for access-token scopes (`documents:read`,
  `documents:write`, `sync:read`, `sync:write`, `query`, `admin`);
- `requireIngester` (sync: admin, or a token with the sync scope);
- `requireCollection` / `requirePattern` for collection-restricted tokens.

Reads are filtered rather than refused where it makes sense: non-admins see only the
collections, documents and chat sessions they may see, and get 404 for the rest.

**Retrieval permissions** come from llm4s: in permission mode every query runs with the caller's
`UserAuthorization` (`auth/PermissionContext`), built from the principal's user and groups, so
the search index only returns chunks from collections whose `queryableBy` admits them and
documents whose `readableBy` does. Administrators query unrestricted.

## RAG pipeline

### `Llm4sPipeline`: building llm4s objects from configuration

Every provider is resolved through llm4s's **`ProviderRegistry`** (provider modules are found
with `ServiceLoader`): the LLM client from `LLM_MODEL` (`<provider>/<model>`), the embedding
provider config from `EMBEDDING_PROVIDER`/`EMBEDDING_MODEL`, the Cohere reranker config, and the
`RAGConfig` (hybrid PostgreSQL vector + keyword store, fusion strategy, system prompt, reranker,
query transform, tracing). ragbox never matches on provider names; tests register an in-process
embedder in the registry.

### `PipelinePool`

An llm4s `RAG` fixes some settings when it is built (fusion, system prompt, reranker, query
transform). `PipelinePool` keeps one built pipeline per `PipelineSettings` value:

- built on first use, builds serialised, failures not cached (retried after a cooldown, so a
  provider that is down at startup does not break the server until restart);
- every use holds a **lease**; a pipeline that is retired (settings moved on) or evicted (least
  recently used beyond the limit) is closed only when its last lease is released.

Experiments use the same pool: a variant with different settings gets its own pipeline.

### `RAGService`

The facade the routes use. It delegates to `RagPipelines` (runtime settings, LLM, embedder,
chunkers and the `PipelinePool`, shared by the others), `DocumentIngestion` (ingest, upsert,
delete, prune; chunk store and registry; permission-mode collections), `QueryService` (search,
answers and chat replies with their metrics), `StatsService` (statistics, chunks, sync status)
and `SystemInfoService` (health, readiness, config views, providers). Methods such as `answer`,
`searchAs`, `chatAs` and `upsertAs` take the caller's principal and pick the permission-filtered
or plain path, so routes never branch on the mode.

- **Ingest**: chunk the document once, with the chunking its collection resolves to
  (`CollectionConfigRegistry.getEffective`: file-type override → collection config → runtime
  settings), embed the chunks, write them to llm4s (`SearchIndex.ingest` in permission mode, the
  pipeline otherwise), record the document and its content hash in the **`DocumentRegistry`**
  and its chunks in the **`ChunkStore`** (for visibility). Unchanged content is skipped.
- **Query**: `RAG.queryWithAnswer` with the current runtime settings (top-K per call) through a
  pooled pipeline, inside `QueryMetricsTracing.collect`, which attributes llm4s trace events
  (embedding usage, completion usage, cost) to the request. The query log records latency per
  stage, tokens, cost, the experiment variant and failures.
- **Chat**: retrieval for the new message (permission-filtered), then the answer LLM is called
  with a conversation ragbox builds itself: system prompt, the session's recent messages
  (`RAG_CHAT_HISTORY_MESSAGES`), the retrieved context and the question (llm4s's
  `queryWithAnswer` takes no history yet, llm4s/llm4s#2733).
- **Uploads**: `TikaDocumentExtractor` extracts text from PDF, DOCX, HTML and other formats.
- All llm4s calls are synchronous, so they run on the blocking pool (`IO.blocking`).

In permission mode llm4s searches vector-only, so fusion, keyword search, reranking and query
rewriting do not apply; `GET /api/v1/config` reports this (`searchMode`, `fusionApplies`,
`limitations`). See the ROADMAP for this and the other llm4s workarounds.

### Runtime configuration

`RuntimeConfigManager` holds the settings that can change without a restart:

- **hot** (next query): `topK`, `fusionStrategy`, `rrfK`, `systemPrompt`, `llmTemperature`;
- **warm** (documents ingested afterwards): `chunkingStrategy`, `chunkSize`, `chunkOverlap`.

Reads are lock-free (`AtomicReference`); updates are serialised, written to `runtime_config` and
`config_history` in one transaction, then published. Stored values override the configuration
file at startup. A change to a pipeline setting retires the old pipeline in the pool.

## Ingestion

- **`IngestionService`** runs the configured sources (directory, URL, web crawler, database, S3)
  through llm4s loaders (`FileLoader`, `UrlLoader`, `WebCrawlerLoader`, `S3Loader`) and the
  one-shot `POST /ingest/directory` and `/ingest/url` requests. Every document is recorded with
  the **source that owns it**: a source's sync removes only its own documents that it no longer
  lists, so sources never delete each other's documents, and because the registry is in
  PostgreSQL this works across restarts. One run at a time (409 otherwise). Progress is
  broadcast on `/ws`.
- **`IngestionScheduler`** runs the sources on an interval (`INGEST_SCHEDULE`: `30m`, `6h`,
  `daily`, ...).
- **`IngestionPolicy`**: directories only under allowed roots (`INGEST_ALLOWED_ROOTS`, default the
  configured directory sources), never following symlinks out; URLs only http(s), optionally only
  allowed hosts (`INGEST_URL_ALLOWED_HOSTS`), and resolving to public addresses unless
  `INGEST_URL_ALLOW_PRIVATE_NETWORKS=true` (checked with llm4s's `NetworkSecurity`). Violations
  are 400s.
- **External ingesters** use the document and sync APIs with `rat_` tokens (see the Python SDK).

## Persistence

- **One HikariCP pool** (`db/ConnectionPool`) is shared by every component, including the llm4s
  search index (`PgSearchIndex.fromDataSource`). Nothing holds a connection between operations;
  `withConnection` / `withTransaction` run on the blocking pool.
- **Flyway** (`db/Migrations`, `src/main/resources/db/migration`) owns ragbox's schema.
  Databases created before Flyway are baselined at version 0 and brought up to date by the
  idempotent `V1__baseline`. Replicas starting together are serialised by Flyway's lock.
- **llm4s owns** its tables: the vector table (`PG_TABLE_NAME`, default `rag_embeddings`), the
  keyword table and the permission tables (`llm4s_collections`, `llm4s_principals`, ...). It
  creates them after the migrations run.

| Table | Owner component |
|---|---|
| `document_registry`, `sync_status` | `PgDocumentRegistry` (content hashes, owning source, sync time) |
| `chunk_registry` | `PgChunkStore` |
| `runtime_config`, `config_history` | `RuntimeConfigManager` |
| `collection_configs` | `PgCollectionConfigRegistry` |
| `query_logs` | `QueryLogRegistry` |
| `users`, `access_tokens` | `UserRegistry`, `AccessTokenRegistry` |
| `experiments` | `ExperimentRegistry` |
| `chat_sessions`, `chat_messages` | `ChatSessionRegistry` |
| `oauth_sessions`, `oauth_auth_state` | `PgSessionRegistry` (`OAUTH_SESSION_STORAGE=postgresql`) |

## Evaluation

- **Query log** (`QueryLogRegistry`): every query and chat answer, with latency per stage,
  chunks, tokens, cost, rating, experiment and variant.
- **Analytics** (`AnalyticsRoutes`): filtered log, summary with p50/p95/p99 latency and cost.
- **Suggestions** (`SuggestionService`): rules over the log (latency, ratings, chunk use) that
  suggest chunking and search changes.
- **Experiments** (`ExperimentService`, `Statistics`): one running experiment at a time; each
  `/query` is assigned at random to baseline or variant by the traffic split, and runs with that
  variant's full configuration. Results compare latency (Welch's t-test), error rate and the
  share of positive ratings (two-proportion z-tests) at α = 0.05.

## Admin UI

A Vue 3 + Vuetify single-page app in `admin-ui/`, built by Vite into `admin-ui/dist`, copied
into the server's resources by sbt and served at `/admin` by `StaticRoutes`. It reads
`GET /api/v1/auth/mode` to choose its login (none, password, SSO or API key), and covers every
API area: dashboard, documents, upload, chat, collections and permissions, users, groups,
tokens, configuration, chunking preview, visibility, ingestion, analytics, suggestions,
experiments and the API docs (Swagger UI over `public/openapi.yaml`). A contract check in CI
keeps the UI's API calls in line with the backend; Playwright tests run it against a real
server.

## API contract

- Routes live in `routes/*.scala`; request and response types in `model/` (circe codecs).
- `admin-ui/public/openapi.yaml` documents every route; `OpenApiCoverageSpec` scans the route
  patterns in `routes/` and fails when a route is missing from the spec or the spec lists one
  that does not exist.
- Errors are JSON `ErrorResponse {error, message, details?}`, produced in one place
  (`ErrorHandling`); 413 and 429 from the size and rate-limit middleware have their own bodies.
- Models derive their circe codecs (`derives Codec.AsObject`); `ApiContractSpec` pins the JSON
  shapes the admin UI relies on.

## Project layout

```
src/main/scala/org/llm4s/ragbox/
  Main.scala, RagboxApp.scala   startup, wiring and the HTTP stack
  auth/                         principals, password/JWT auth, access tokens, OIDC, sessions
  config/                       AppConfig, DATABASE_URL parsing, runtime settings
  db/                           connection pool, Flyway migrations
  ingestion/                    sources, scheduler, ingestion policy
  middleware/                   authenticator, rate limit, request size, request logger
  model/                        API models (with derived codecs)
  registry/                     PostgreSQL registries (documents, query log, chat, experiments, ...)
  routes/                       HTTP routes, Authz, ErrorHandling, Params
  service/                      RAGService facade and its services (RagPipelines,
                                DocumentIngestion, QueryService, StatsService,
                                SystemInfoService), ServiceError, Llm4sPipeline, PipelinePool,
                                tracing, chunking, experiments, suggestions, statistics
  store/                        chunk store
  validation/                   input validation
src/main/resources/             application.conf, logback.xml, db/migration
admin-ui/                       Vue admin UI (and public/openapi.yaml)
sdk/python/                     Python SDK
deploy/                         Helm chart, Kubernetes manifests
docs/                           documentation site
```

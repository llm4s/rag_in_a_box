---
layout: page
title: API Reference
nav_order: 4
has_children: true
---

# API Reference
{: .no_toc }

REST API reference for RAG in a Box.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Base URLs

| Paths | Base |
|-------|------|
| REST API | `http://localhost:8080/api/v1` |
| Health checks, metrics, WebSocket, admin UI | `http://localhost:8080` (`/health`, `/metrics`, `/ws`, `/admin`) |

An OpenAPI description is served with the admin UI at `/admin/openapi.yaml`.

## Authentication

Which credentials are accepted depends on `AUTH_MODE` (see the
[Authentication guide](/guide/authentication)):

| Credential | `open` | `open` + `API_KEY` | `basic` | `oauth` |
|------------|--------|--------------------|---------|---------|
| none (every caller is an anonymous administrator) | yes | | | |
| static `API_KEY` (holder is an administrator) | | yes | if set | if set |
| `rat_` access token ([Tokens API](/api/tokens)) | | yes | yes | yes |
| JWT from `POST /api/v1/auth/login` | | | yes | |
| OIDC session cookie (`ragbox_session` by default) from the [OAuth flow](/api/auth#oauth) | | | | yes |

Credentials are read from, first match wins:

```bash
# The static API key
curl -H "X-API-Key: $API_KEY" ...

# API key, rat_ access token or JWT
curl -H "Authorization: Bearer <API_KEY | rat_... | JWT>" ...
```

- `?access_token=<token>` is accepted **only** on the WebSocket endpoint `/ws`, because browsers
  cannot set headers on a WebSocket handshake. `?api_key=` is not accepted anywhere.
- A request that presents an invalid credential is rejected with `401` even if another valid
  credential is also present.
- `401` responses carry `WWW-Authenticate: Bearer realm="ragbox"`.

`GET /api/v1/auth/mode` (public) tells a client which login methods the server accepts.

### Acting for an end user (trusted proxy)

With `AUTH_TRUSTED_PROXY_HEADERS=true`, a request authenticated as an administrator (typically a
gateway holding the `API_KEY`) may send `X-User-Id` and `X-Group-Ids` to act as that end user;
queries are then filtered by that user's collection permissions. `X-Admin: true` keeps
administrator rights only if `ALLOW_ADMIN_HEADER=true`. Non-admin credentials can never assert
another identity. Without `AUTH_TRUSTED_PROXY_HEADERS=true` these headers are stripped from every
request.

## Authorization

Each endpoint below lists who may call it:

| Label | Meaning |
|-------|---------|
| **Public** | No credentials needed |
| **Authenticated** | Any authenticated principal. Access tokens additionally need the listed scope |
| **Admin** | Administrators: admin users, the `API_KEY`, access tokens with the `admin` scope, and everyone in `open` mode without an `API_KEY` |
| **Ingester** | Access tokens with the listed scope, or administrators |

Access tokens without the `admin` scope may only reach these paths (everything else answers
`403` before any route runs): `/api/v1/documents/**`, `GET /api/v1/collections`,
`/api/v1/stats`, `/api/v1/sync/**`, `/api/v1/query/**`, `/api/v1/search`, `/api/v1/chat/**`,
`/api/v1/auth/me` and `/ws`. A token restricted to collections only sees and changes documents
in those collections (and their descendants), and may only query patterns inside them.

## Endpoints

### Public

| Method | Path | Description |
|--------|------|-------------|
| GET | [`/health`](/api/health#health) | Basic health |
| GET | [`/health/ready`](/api/health#ready) | Readiness (503 when not ready) |
| GET | [`/health/live`](/api/health#live) | Liveness |
| POST | [`/api/v1/auth/login`](/api/auth#login) | Password login, returns a JWT (`basic` mode only) |
| GET | [`/api/v1/auth/mode`](/api/auth#mode) | Which login methods the server accepts |
| GET | [`/api/v1/oauth/login`](/api/auth#oauth) | Start the OIDC login (`oauth` mode only) |
| GET | [`/api/v1/oauth/callback`](/api/auth#oauth) | OIDC callback (`oauth` mode only) |
| POST | [`/api/v1/oauth/logout`](/api/auth#oauth) | End the OIDC session (`oauth` mode only) |
| GET | [`/api/v1/oauth/userinfo`](/api/auth#oauth) | The OIDC session's user (`oauth` mode only) |
| GET | `/admin`, `/admin/**` | Admin UI (must be built, see [Quick Start](/getting-started/quickstart)) |
| GET | [`/metrics`](/api/health#metrics) | Prometheus metrics, only when `METRICS_ENABLED=true` and `METRICS_REQUIRE_AUTH=false` |

### Authenticated

| Method | Path | Who | Description |
|--------|------|-----|-------------|
| GET | [`/api/v1/auth/me`](/api/auth#me) | Authenticated | The current principal |
| PUT | [`/api/v1/auth/password`](/api/auth#change-password) | Password users | Change own password |
| POST | [`/api/v1/users`](/api/auth#create-user) | Admin | Create a user |
| GET | [`/api/v1/users`](/api/auth#list-users) | Admin | List users |
| DELETE | [`/api/v1/users/{id}`](/api/auth#delete-user) | Admin | Delete a user |
| PUT | [`/api/v1/users/{id}/password`](/api/auth#reset-password) | Admin | Reset a user's password |
| POST | [`/api/v1/tokens`](/api/tokens#create-token) | Admin | Create an access token |
| GET | [`/api/v1/tokens`](/api/tokens#list-tokens) | Admin | List access tokens |
| GET | [`/api/v1/tokens/{id}`](/api/tokens#get-token) | Admin | Get an access token |
| DELETE | [`/api/v1/tokens/{id}`](/api/tokens#revoke-token) | Admin | Revoke an access token |
| POST | [`/api/v1/documents`](/api/documents#upload) | `documents:write` | Add a document (JSON) |
| POST | [`/api/v1/documents/upload`](/api/documents#upload-file) | `documents:write` | Upload a file (multipart) |
| GET | [`/api/v1/documents`](/api/documents#list) | `documents:read` | List documents (paginated) |
| GET | [`/api/v1/documents/{id}`](/api/documents#get) | `documents:read` | Document metadata |
| PUT | [`/api/v1/documents/{id}`](/api/documents#upsert) | `documents:write` | Upsert a document |
| DELETE | [`/api/v1/documents/{id}`](/api/documents#delete) | `documents:write` | Delete a document |
| DELETE | [`/api/v1/documents`](/api/documents#batch-delete) | `documents:write` | Batch delete |
| DELETE | [`/api/v1/documents?all=true`](/api/documents#clear) | Admin | Delete every document |
| GET | [`/api/v1/collections`](/api/documents#collections) | `documents:read` | Collection names |
| GET | [`/api/v1/stats`](/api/documents#stats) | `documents:read` | Document/chunk statistics |
| GET | [`/api/v1/sync/status`](/api/sync#status) | Ingester `sync:read` | Sync status |
| GET | [`/api/v1/sync/documents`](/api/sync#list) | Ingester `sync:read` | Document states |
| GET | [`/api/v1/sync/documents/{id}`](/api/sync#get) | Ingester `sync:read` | One document's state |
| POST | [`/api/v1/sync/check`](/api/sync#check) | Ingester `sync:read` | Batch check states |
| POST | [`/api/v1/sync`](/api/sync#prune) | Ingester `sync:write` | Mark sync complete / prune |
| POST | [`/api/v1/query`](/api/query#query) | `query` | Retrieve and answer |
| POST | [`/api/v1/query/stream`](/api/query#stream) | `query` | Retrieve and answer, as Server-Sent Events |
| POST | [`/api/v1/search`](/api/query#search) | `query` | Retrieve only (no LLM) |
| POST | [`/api/v1/chat/sessions`](/api/chat#create-session) | `query` | Create a chat session |
| GET | [`/api/v1/chat/sessions`](/api/chat#list-sessions) | `query` | List own sessions (admins: all) |
| GET | [`/api/v1/chat/sessions/{id}`](/api/chat#get-session) | `query` | Session with messages |
| PUT | [`/api/v1/chat/sessions/{id}`](/api/chat#update-session) | `query` | Rename / archive / re-scope |
| DELETE | [`/api/v1/chat/sessions/{id}`](/api/chat#delete-session) | `query` | Delete a session |
| POST | [`/api/v1/chat/sessions/{id}/messages`](/api/chat#send-message) | `query` | Send a message |
| POST | [`/api/v1/chat/sessions/{id}/messages/stream`](/api/chat#stream-message) | `query` | Send a message, SSE reply |
| PUT | [`/api/v1/chat/messages/{id}/rate`](/api/chat#rate-message) | `query` | Rate an answer |
| POST | [`/api/v1/feedback`](/api/analytics#feedback) | The query's asker, or admin (tokens: `query`) | Rate a logged query |
| GET | [`/api/v1/analytics/queries`](/api/analytics#list) | Admin | Query log |
| GET | [`/api/v1/analytics/queries/summary`](/api/analytics#summary) | Admin | Query analytics summary |
| GET | [`/api/v1/analytics/queries/{id}`](/api/analytics#get) | Admin | One query log entry |
| GET | [`/api/v1/analytics/suggestions`](/api/analytics#suggestions) | Admin | Optimization suggestions |
| GET | [`/api/v1/analytics/suggestions/summary`](/api/analytics#suggestions-summary) | Admin | Suggestion counts |
| GET | [`/api/v1/analytics/suggestions/collection/{name}`](/api/analytics#collection-suggestions) | Admin | Suggestions for one collection |
| POST | [`/api/v1/experiments`](/api/experiments#create) | Admin | Create an A/B experiment |
| GET | [`/api/v1/experiments`](/api/experiments#list) | Admin | List experiments |
| GET | [`/api/v1/experiments/running`](/api/experiments#running) | Admin | The running experiment |
| GET | [`/api/v1/experiments/{id}`](/api/experiments#get) | Admin | One experiment |
| PATCH | [`/api/v1/experiments/{id}`](/api/experiments#update) | Admin | Update a draft |
| PUT | [`/api/v1/experiments/{id}/start`](/api/experiments#lifecycle) | Admin | Start |
| PUT | [`/api/v1/experiments/{id}/stop`](/api/experiments#lifecycle) | Admin | Stop |
| PUT | [`/api/v1/experiments/{id}/archive`](/api/experiments#lifecycle) | Admin | Archive |
| DELETE | [`/api/v1/experiments/{id}`](/api/experiments#lifecycle) | Admin | Delete a draft |
| GET | [`/api/v1/experiments/{id}/results`](/api/experiments#results) | Admin | Results with significance tests |
| GET | [`/api/v1/config`](/api/config#get) | Authenticated | Static configuration |
| GET | [`/api/v1/config/providers`](/api/config#providers) | Authenticated | Providers and strategies |
| GET | [`/api/v1/config/runtime`](/api/config#runtime) | Authenticated | Runtime settings |
| PUT | [`/api/v1/config/runtime`](/api/config#runtime-update) | Admin | Change runtime settings |
| POST | [`/api/v1/config/runtime/validate`](/api/config#runtime-validate) | Admin | Validate proposed settings |
| GET | [`/api/v1/config/runtime/history`](/api/config#runtime-history) | Admin | Change history |
| GET | [`/api/v1/config/runtime/meta`](/api/config#runtime-meta) | Authenticated | Setting descriptions |
| GET | [`/api/v1/collections/configs`](/api/chunking#collection-configs) | Authenticated | Every collection's chunking config |
| GET | [`/api/v1/collections/{name}/config`](/api/chunking#collection-config) | Authenticated | One collection's chunking config |
| PUT | [`/api/v1/collections/{name}/config`](/api/chunking#collection-config-update) | Admin | Set a collection's chunking config |
| DELETE | [`/api/v1/collections/{name}/config`](/api/chunking#collection-config-delete) | Admin | Remove it |
| POST | [`/api/v1/collections/{name}/config/preview`](/api/chunking#collection-config-preview) | Authenticated | Effective config for a file |
| POST | [`/api/v1/chunking/preview`](/api/chunking#preview) | Authenticated | Preview chunking |
| POST | [`/api/v1/chunking/compare`](/api/chunking#compare) | Authenticated | Compare strategies |
| GET | [`/api/v1/chunking/strategies`](/api/chunking#strategies) | Authenticated | Strategies |
| GET | [`/api/v1/chunking/presets`](/api/chunking#presets) | Authenticated | Presets |
| GET | [`/api/v1/visibility/*`](/api/visibility) | Admin | Detailed config, chunks, stats |
| POST | [`/api/v1/ingest/directory`](/api/ingestion#directory) | Admin | Ingest a server directory |
| POST | [`/api/v1/ingest/url`](/api/ingestion#url) | Admin | Ingest URLs |
| POST | [`/api/v1/ingest/run`](/api/ingestion#run) | Admin | Run all configured sources |
| POST | [`/api/v1/ingest/run/{source}`](/api/ingestion#run-source) | Admin | Run one source |
| GET | [`/api/v1/ingest/status`](/api/ingestion#status) | Admin | Ingestion status |
| GET | [`/api/v1/ingest/sources`](/api/ingestion#sources) | Admin | Configured sources |
| | [`/api/v1/principals/**`](/api/permissions#principals) | Admin | Users and groups for permissions (permission mode) |
| | [`/api/v1/collections/**`](/api/permissions#collections) | Mixed | Collections and their permissions (permission mode) |
| GET | [`/metrics`](/api/health#metrics) | Admin | Prometheus metrics (default when `METRICS_ENABLED=true`) |
| GET | [`/ws`](/api/websocket) | Authenticated | WebSocket updates |

## Errors

Error responses are JSON with this shape:

```json
{
  "error": "bad_request",
  "message": "Human-readable message",
  "details": "Optional extra detail"
}
```

| Status | `error` | When |
|--------|---------|------|
| 400 | `bad_request`, `validation_failed` | Invalid request body (malformed JSON, a missing or mistyped field; `details` names it) or query parameter (`details` names it, e.g. `Invalid 'page': abc`) |
| 400 | `configuration_error` | Answer generation requested but no LLM is configured |
| 401 | `unauthorized` | Missing or invalid credentials |
| 403 | `forbidden` | Not an administrator, missing token scope, or collection outside a token's restriction |
| 404 | `not_found` | Resource not found, not visible to the caller, or an id that cannot exist (e.g. a query-log id that is not a UUID) |
| 409 | `conflict` | The request conflicts with the current state: an existing collection, an ingestion run already in progress |
| 415 | `unsupported_media_type` | The body is not JSON where JSON is expected |
| 413 | `payload_too_large` | Request body larger than `MAX_BODY_SIZE_MB` (default 10). `details`: `maxSizeBytes=<n>`; also the `X-Max-Body-Size` header |
| 429 | `rate_limited` | Rate limit exceeded (`RATE_LIMIT_ENABLED=true`, or too many failed logins). `details`: `limit=<n>; window=<s>s; retryAfter=<s>`; plus the `Retry-After` header |
| 500 | `internal_error` | Server error. The message is always `Internal server error`; the cause is in the server log, never in the response |

Every endpoint uses this shape except 413 and 429 (above). Paging parameters are clamped rather
than rejected: `page` below 1 is 1, and `pageSize` is kept between 1 and the endpoint's maximum.
Rely on the HTTP status and the `error` field rather than on `message`/`details` text.

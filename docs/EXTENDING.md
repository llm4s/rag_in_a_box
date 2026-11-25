---
layout: page
title: Extending
parent: User Guide
nav_order: 14
---

# Extending RAG in a Box

This guide shows where to change RAG in a Box when you fork it: adding providers, ingestion
sources, endpoints, pipeline features and persistence. For the overall architecture see
[DESIGN.md](https://github.com/llm4s/rag_in_a_box/blob/main/DESIGN.md).

## Code Layout

All server code is in `src/main/scala/org/llm4s/ragbox/`:

```
org/llm4s/ragbox/
├── Main.scala              # Entry point: config, connection pool, migrations, RAGService, server
├── RagboxApp.scala         # Builds registries/services and the HTTP stack (routes + middleware)
├── auth/                   # Principal, AuthService (passwords, JWT), UserRegistry,
│                           # AccessTokenRegistry (rat_ tokens), OIDC (OidcService, validator,
│                           # ClaimMapper, SessionRegistry), PermissionContext
├── config/                 # AppConfig (application.conf), DatabaseUrl, RuntimeConfigManager,
│                           # RuntimeSettings (settings changeable at runtime)
├── db/                     # ConnectionPool (the shared HikariCP pool), Migrations (Flyway)
├── ingestion/              # IngestionConfig (sources), IngestionService (per-source sync),
│                           # IngestionScheduler, IngestionPolicy (path and URL policy)
├── middleware/             # Authenticator, RateLimitMiddleware, RequestSizeMiddleware,
│                           # RequestLogger, ClientIp
├── model/                  # Request/response types and JSON codecs
├── registry/               # Document, collection-config, query-log, chat-session and
│                           # experiment registries (PostgreSQL)
├── routes/                 # One object per endpoint group, plus Authz (authorization checks),
│                           # ErrorHandling (errors -> ErrorResponse) and Params (query
│                           # parameter, paging and id validation)
├── service/                # RAGService (the facade routes use) over RagPipelines (llm4s
│                           # pipelines, LLM, embedder, runtime settings), DocumentIngestion,
│                           # QueryService, StatsService, SystemInfoService; ServiceError
│                           # (typed errors); Llm4sPipeline, PipelinePool, ChunkingService,
│                           # SuggestionService, ExperimentService, Statistics, QueryMetricsTracing
├── store/                  # ChunkStore (chunk text for the visibility API)
└── validation/             # InputValidation
src/main/resources/
├── application.conf        # Defaults and environment-variable mappings
├── db/migration/           # Flyway migrations (V1__baseline.sql, V2__index_tuning.sql, ...)
└── logback.xml             # Logging (LOG_LEVEL, LOG_FORMAT)
```

`Main` creates the connection pool, applies migrations, builds `RAGService` and then
`RagboxApp.resource(config, pool, ragService)`, and serves `RagboxApp.httpApp`. Tests build the
same `RagboxApp` over a test database (see `src/test/scala/.../integration/AuthStackSpec.scala`),
so whatever you wire into `RagboxApp` is exercised by the integration tests too.

## Extension Points

### 1. LLM and Embedding Providers

Providers are not hard-coded. `Llm4sPipeline` resolves the provider named in configuration
(`LLM_MODEL=<provider>/<model>`, `EMBEDDING_PROVIDER`) through llm4s's `ProviderRegistry`, which
discovers provider modules on the classpath with `ServiceLoader`
(`META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule`). ragbox ships the
`llm4s-openai`, `llm4s-anthropic`, `llm4s-voyage` and `llm4s-ollama` modules.

To add a provider:

1. Add its llm4s module to `libraryDependencies` in `build.sbt` (the assembly merge strategy
   already concatenates the `ServiceLoader` registrations).
2. If it needs an API key, add the key to `api-keys` in `application.conf`, to `ApiKeysConfig`
   in `AppConfig.scala` and to `ApiKeysConfig.getKey`, which is how `Llm4sPipeline` looks up a
   provider's key by its id.
3. Set `LLM_MODEL=<provider>/<model>` or `EMBEDDING_PROVIDER=<provider>`; use `LLM_BASE_URL` /
   `EMBEDDING_BASE_URL` for non-default endpoints.

`GET /api/v1/config/providers` lists the providers the registry knows and whether each is
configured. Tests can pass their own `ProviderRegistry` to `RAGService` (it is a constructor
parameter) to register fake providers.

### 2. Pipeline Features

`Llm4sPipeline.ragConfig` turns `AppConfig` into an llm4s `RAGConfig`: embeddings, chunking,
top-k, fusion, system prompt, tracing, the PostgreSQL hybrid store (`withPgHybrid`), the
permission-aware `SearchIndex` when available, reranking (`withCohereReranking` /
`withLLMReranking`) and query transformation (`withQueryTransformer(LLMQueryRewriter(...))`).
That is the place to enable another llm4s `RAGConfig` option.

An llm4s `RAG` instance bakes its settings in when it is built, so ragbox keeps built
pipelines in a `PipelinePool` keyed by `Llm4sPipeline.PipelineSettings` (the settings that
affect the pipeline). A runtime-config change or an experiment variant with different
settings gets its own instance; in-flight requests keep the instance they leased, and retired
instances are closed when their last lease is released. If you add a pipeline setting that can
change at runtime, add it to `PipelineSettings` so it becomes part of the pool key.

Settings that llm4s takes with each call - topK, and the answer's completion options (the
temperature) - are not part of the key: they are passed with the call.

### 3. Ingestion Sources

Sources are configured under `ingestion.sources` in `application.conf` (or `INGEST_DIR` for a
single directory) and parsed by `IngestionConfig.parseSource` into a `SourceConfig`
(`directory`, `url`, `web`, `database`, `s3`). To add a source type:

1. Add a case class extending `SourceConfig` in `IngestionConfig.scala` and parse it in
   `parseSource`:

   ```scala
   final case class MySourceConfig(
     name: String,
     endpoint: String,
     metadata: Map[String, String] = Map.empty,
     enabled: Boolean = true
   ) extends SourceConfig

   // in parseSource
   case "my-source" =>
     Some(MySourceConfig(name, config.getString("endpoint"), metadata, enabled))
   ```

2. In `IngestionService`, build an llm4s `DocumentLoader` for it and add a case to
   `runSourceConfig` (and `sourceTypeOf`) that runs it through `syncSource`, as the directory,
   URL and web sources do. `syncSource` upserts every document the loader lists, records the
   source as the documents' owner and, with `removeUnlisted = true`, deletes the documents the
   source owns that it no longer lists. It never deletes on a listing failure, and documents
   that fail to load keep their indexed version.

3. If the source fetches from the network or the file system, apply the `IngestionPolicy`
   (`PathPolicy` for directories, `UrlPolicy` for URLs: allowed hosts, and refusal of private,
   loopback and link-local addresses unless `INGEST_URL_ALLOW_PRIVATE_NETWORKS=true`).

Sources run on the schedule (`IngestionScheduler`), at startup, or on demand with
`POST /api/v1/ingest/run` and `POST /api/v1/ingest/run/{source}`. Runs are serialised, and
progress is broadcast to WebSocket clients (`/ws`).

For documents that live in another system, an [external ingester](guide/external-ingesters.md)
using the sync API (`/api/v1/sync`) is often simpler than a new source type.

### 4. API Endpoints

Protected endpoints are `AuthedRoutes[Principal, IO]`: the `Authenticator` has already turned
the request's credentials into a `Principal`, and the route checks authorization with the
`Authz` combinators:

```scala
package org.llm4s.ragbox.routes

import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.llm4s.ragbox.auth.Principal
import org.llm4s.ragbox.service.RAGService

object MyRoutes {
  def routes(ragService: RAGService): AuthedRoutes[Principal, IO] = AuthedRoutes.of[Principal, IO] {
    case GET -> Root / "api" / "v1" / "my-endpoint" as principal =>
      Authz.requireAdmin(principal) {
        ragService.getStats.flatMap(stats => Ok(stats.asJson))
      }
  }
}
```

Then add it to the `authed` sequence in `RagboxApp.resource` (or, for an unauthenticated
endpoint, to `public` as an `HttpRoutes[IO]`). Notes:

- `Authz.requireAdmin`, `requireScope`, `requireCollection` / `requirePattern` and
  `adminOnlyWhen` cover the common cases. Access tokens without the `admin` scope are limited
  to an allowlist of paths (`Authz.tokenAllowlist`); extend it if a scoped token should reach
  your endpoint.
- Errors: raise a typed `ServiceError` (`InvalidRequestError` → 400, `NotFoundError` → 404,
  `ConflictError` → 409, `LlmUnavailableError` → 400 `configuration_error`) and let
  `ErrorHandling`, which wraps every route in `RagboxApp`, turn it into an `ErrorResponse`
  (`{"error": ..., "message": ..., "details": ...}`). Body decode failures become 400s and any
  other exception a generic 500 (logged, never echoed). Use `Params` for query parameters, so a
  bad value is a 400 naming the parameter.
- Add the path to `admin-ui/public/openapi.yaml`; the build checks that every route is
  documented there.

### 5. Middleware

Middleware is assembled in `RagboxApp.httpApp`. From the outside in: identity-header
stripping, request logging (credentials redacted), CORS, the request-size limit, rate limits,
then the public routes and the authenticator in front of every other route. Wrap the routes at
the appropriate layer there; for example, a response header for every API response:

```scala
import cats.data.Kleisli
import org.http4s._
import org.typelevel.ci._

object MyHeader {
  def apply(app: HttpApp[IO]): HttpApp[IO] =
    Kleisli((req: Request[IO]) => app(req).map(_.putHeaders(Header.Raw(ci"X-Custom-Header", "value"))))
}
```

### 6. Authentication

`middleware/Authenticator.scala` is the single authenticator. It reads a credential
(`X-API-Key`, `Authorization: Bearer`, `?access_token=` on `/ws` only, or the OIDC session
cookie) and produces a `Principal` (`auth/Principal.scala`) or a 401. To add a credential type,
extend `credentialOf` and `authenticate` to produce a `Principal`; routes need no change. See
the [authentication guide](guide/authentication.md) for the behaviour of each mode.

### 7. Persistence and Schema

All components share one HikariCP pool (`db/ConnectionPool`, sized by `DB_POOL_SIZE`). Registries
are small traits with a PostgreSQL implementation (`DocumentRegistry` / `PgDocumentRegistry`,
`CollectionConfigRegistry` / `PgCollectionConfigRegistry`, `QueryLogRegistryBase` /
`QueryLogRegistry`, `ChunkStore` / `PgChunkStore`, plus `ChatSessionRegistry`,
`ExperimentRegistry`, `UserRegistry`, `AccessTokenRegistry`, `PgSessionRegistry`). Tests use
in-memory implementations of the traits (`src/test/scala/.../testkit`).

Components never create tables themselves. To change the schema, add a Flyway migration
`src/main/resources/db/migration/V<n>__<description>.sql` with the next version number; never
edit one that has been applied anywhere. See CONTRIBUTING.md, "Database migrations".

Tables owned by llm4s (the vector/keyword table, `llm4s_collections`, `llm4s_principals`) are
created by llm4s, not by the migrations.

### 8. Vector Store

ragbox uses llm4s's PostgreSQL stores: `withPgHybrid` (pgvector plus PostgreSQL full-text) and,
in permission mode, llm4s's PostgreSQL `SearchIndex` with its collection and principal tables.
llm4s supports other vector stores, but ragbox's registries, permissions and sync all assume
PostgreSQL, so swapping the store means more than changing `Llm4sPipeline.ragConfig`.

## Configuration

To add a setting:

1. Add it to `application.conf` with a default and an environment variable:

   ```hocon
   my-feature {
     option1 = "default"
     option1 = ${?MY_FEATURE_OPTION1}
   }
   ```

2. Add a field to the relevant case class in `AppConfig.scala` and read it in `parseConfig`
   (use the `getOptionalString` / `getOptionalInt` helpers for optional values).
3. Validate it in `AppConfig.validate` if a bad value should stop startup.
4. Document it in `.env.example` and the [configuration guide](guide/configuration.md).

Settings that should be changeable without a restart belong in the runtime configuration
(`RuntimeConfigManager`, `GET/PUT /api/v1/config/runtime`).

## Building Custom Docker Images

The repository's `Dockerfile` builds the admin UI, then the fat JAR (`ragbox-assembly.jar`,
which bundles the UI), then a small JRE image running as a non-root user. To ship your own
configuration file on top of it:

```dockerfile
FROM ghcr.io/llm4s/rag_in_a_box:latest
COPY custom-config.conf /app/custom-config.conf
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dconfig.file=/app/custom-config.conf"
```

A custom `config.file` replaces the bundled `application.conf`, so start it with
`include "application"` or copy the defaults.

## Upgrade Guide

When upgrading a fork:

1. **Merge upstream changes**:
   ```bash
   git remote add upstream https://github.com/llm4s/rag_in_a_box.git
   git fetch upstream
   git merge upstream/main
   ```

2. **Check for breaking changes** in the release notes.

3. **Database schema**: upstream migrations are applied automatically at startup (Flyway,
   `src/main/resources/db/migration`). Put your own schema changes in migrations too and never
   edit a migration that has been applied anywhere. Flyway refuses to start when a migration
   with a lower version than the database's appears later, so if your fork adds migrations,
   upstream's new ones must keep higher versions than yours when you merge. See CONTRIBUTING.md,
   "Database migrations".

4. **Test your extensions** against the new version: `sbt test` runs the unit tests and the
   integration tests (the latter need Docker for PostgreSQL).

## Troubleshooting

**Dependency conflicts**: if you add dependencies, check the assembly merge strategy in
`build.sbt`.

**Type mismatches after upgrading llm4s**: the llm4s API may change between versions; check
the llm4s release notes.

**Provider not found**: `LLM provider '<id>' ...` at startup means the provider module is not
on the classpath or the id is misspelled; `GET /api/v1/config/providers` lists what is
available.

## Getting Help

- Open an issue on [GitHub](https://github.com/llm4s/rag_in_a_box/issues)
- See the [llm4s documentation](https://llm4s.org) for the RAG API

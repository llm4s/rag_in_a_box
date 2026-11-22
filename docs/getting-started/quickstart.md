---
layout: page
title: Quick Start
parent: Getting Started
nav_order: 1
---

# Quick Start Guide
{: .no_toc }

Get RAG in a Box running locally in a few minutes.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Prerequisites

- Docker with Docker Compose v2
- An OpenAI API key (the defaults use `openai/gpt-4o` and `text-embedding-3-small`; see
  [Configuration](/guide/configuration) for Anthropic, Voyage, Ollama and others)

## Option 1: Docker Compose (recommended)

The repository's `docker-compose.yml` starts PostgreSQL with pgvector and builds the server
image, including the admin UI:

```bash
git clone https://github.com/llm4s/rag_in_a_box.git
cd rag_in_a_box

cp .env.example .env          # then set OPENAI_API_KEY in .env
docker compose up -d --build
```

- The API and admin UI are on `http://localhost:8080` (admin UI at `/admin`).
- PostgreSQL is published on host port `15432` (user/password/database `rag`/`rag`/`ragdb`).
- The host directory `./data` (or `INGEST_HOST_DIR`) is mounted read-only at `/data` in the
  container as the ingestion source; set `INGEST_ON_STARTUP=true` in `.env` to ingest it at
  startup.
- The schema is created by Flyway migrations on startup.

Compose only passes a fixed list of variables into the container (API keys, `LLM_*`,
`EMBEDDING_*`, `RAG_*`, `INGEST_*`, `API_KEY`, logging, metrics and hardening settings). To use
others, e.g. `AUTH_MODE` or `JWT_SECRET`, add them to the `api` service's `environment:`.

{: .note }
Compose starts in `open` auth mode: every request is an anonymous administrator. That is fine
on your laptop; before exposing the server anywhere, set `API_KEY` or switch to `basic`/`oauth`
mode (see [Authentication](/guide/authentication)).

## Option 2: Prebuilt Image

Images are published to GitHub Container Registry as `ghcr.io/llm4s/rag_in_a_box` (`latest`
follows `main`; releases are tagged with their version). With an existing PostgreSQL that has
the `vector` extension:

```bash
docker run -d -p 8080:8080 \
  -e OPENAI_API_KEY=$OPENAI_API_KEY \
  -e DATABASE_URL=postgresql://rag:secret@db.example.com:5432/ragdb \
  -e INGEST_DIR=/data -e INGEST_ON_STARTUP=true \
  -v /path/to/docs:/data:ro \
  ghcr.io/llm4s/rag_in_a_box:latest
```

(`PG_HOST`, `PG_PORT`, `PG_DATABASE`, `PG_USER` and `PG_PASSWORD` work too; `DATABASE_URL`
overrides them.)

## Option 3: From Source

Requires JDK 21, sbt and Node.js 20+.

```bash
# 1. PostgreSQL with pgvector, on localhost:15432 (the default the server expects)
docker compose up -d postgres

# 2. Build the admin UI. sbt copies admin-ui/dist into the server's resources;
#    skip this and /admin answers with a "Resource not found" error.
cd admin-ui && npm ci && npm run build && cd ..

# 3. Run the server (run.sh loads .env, then runs `sbt run`)
cp .env.example .env          # set OPENAI_API_KEY
./run.sh
```

For admin UI development with hot reload, run `npm run dev` in `admin-ui/` instead; it proxies
API calls to the server on port 8080.

## Verify

```bash
curl http://localhost:8080/health
curl http://localhost:8080/health/ready
```

```json
{ "status": "healthy", "version": "0.1.0", "uptime": 12345, "system": { ... } }
```

`/health/ready` answers `200` with `"ready": true` once the database is reachable and the
llm4s RAG pipeline is built; otherwise `503` with the failing check.

## Add a Document

```bash
curl -X POST http://localhost:8080/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{
    "content": "RAG in a Box is a turnkey RAG server built on llm4s. It supports hybrid search with RRF fusion.",
    "filename": "about-ragbox.txt",
    "metadata": { "category": "documentation" }
  }'
```

```json
{
  "documentId": "3f6c...",
  "chunks": 1,
  "message": "Successfully indexed 1 chunks in default"
}
```

Upload files (PDF, DOCX, HTML, Markdown, ...) with
`curl -F "file=@handbook.pdf" http://localhost:8080/api/v1/documents/upload`.

## Ask a Question

```bash
curl -X POST http://localhost:8080/api/v1/query \
  -H "Content-Type: application/json" \
  -d '{ "question": "What search does RAG in a Box support?" }'
```

```json
{
  "answer": "It supports hybrid search with RRF fusion.",
  "contexts": [
    {
      "content": "RAG in a Box is a turnkey RAG server built on llm4s...",
      "score": 0.92,
      "metadata": { "filename": "about-ragbox.txt", "category": "documentation" },
      "documentId": "3f6c...",
      "chunkIndex": 0
    }
  ],
  "usage": { "promptTokens": 210, "completionTokens": 12, "totalTokens": 222 },
  "metrics": { "...": "latencies, tokens, estimated cost" }
}
```

Retrieval only, without the LLM:

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{ "query": "hybrid search" }'
```

## The Admin UI

Open `http://localhost:8080/admin`. It covers the dashboard (live via WebSocket), documents and
upload, chat, configuration, runtime settings and per-collection chunking, chunking preview,
visibility (chunks and statistics), ingestion, analytics and suggestions, A/B experiments, users,
groups and collection permissions, and interactive API docs. Access tokens are managed with the
[Tokens API](/api/tokens). The OpenAPI description is at
`http://localhost:8080/admin/openapi.yaml`.

## Automatic Ingestion

Point `INGEST_DIR` at a directory (with Compose: the host directory `INGEST_HOST_DIR`, mounted
at `/data`):

| Variable | Default | Description |
|----------|---------|-------------|
| `INGEST_DIR` | | Directory to ingest (enables the `env-directory` source) |
| `INGEST_PATTERNS` | `*.md,*.txt,*.pdf` | Glob patterns |
| `INGEST_RECURSIVE` | `true` | Include subdirectories |
| `INGEST_ON_STARTUP` | `false` | Ingest at startup |
| `INGEST_SCHEDULE` | | Re-ingest periodically: `hourly`, `daily`, `weekly` or an interval such as `30m`, `1h` |

Only new and changed files are re-indexed, and deleted files are removed. Trigger a run with
`POST /api/v1/ingest/run` (see the [Ingestion API](/api/ingestion)).

## Next Steps

- [Configuration Guide](/guide/configuration) - models, chunking, search, reranking
- [Authentication](/guide/authentication) - API keys, basic login, OAuth/OIDC
- [External Ingesters](/guide/external-ingesters) - push documents with the sync API
- [API Reference](/api/) - every endpoint, with its auth requirements

## Troubleshooting

**`/admin` shows "Resource not found"**: the admin UI was not built before `sbt run`; run
`npm ci && npm run build` in `admin-ui/` and restart.

**Database connection failed**: check PostgreSQL is reachable (default `localhost:15432`) and
has the extension (`CREATE EXTENSION IF NOT EXISTS vector;`, which `scripts/init-db.sql` runs in
the Compose container).

**`/query` answers `400` with `configuration_error`**: no LLM could be built. Check `LLM_MODEL`
and the provider's API key; the server log says why. `GET /api/v1/config/providers` shows which
providers are configured.

**No results**: check `GET /api/v1/stats`; if `documentCount` is 0, check your ingestion
settings or upload a document.

See the [Troubleshooting guide](/troubleshooting) for more.

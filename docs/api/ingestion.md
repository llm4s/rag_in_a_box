---
layout: page
title: Ingestion API
parent: API Reference
nav_order: 12
---

# Ingestion API
{: .no_toc }

Server-side ingestion: one-shot directory and URL ingestion, and running the configured sources
(see [Configuration](/guide/configuration) and [S3 Ingestion](/guide/s3-ingestion)). External
systems that push documents use the [Documents](/api/documents) and [Sync](/api/sync) APIs
instead.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

**Auth:** every endpoint is **Admin** only.

## Ingestion Result

Every ingestion answers with one or more results:

```json
{
  "sourceName": "env-directory",
  "sourceType": "directory",
  "documentsAdded": 12,
  "documentsUpdated": 3,
  "documentsDeleted": 1,
  "documentsUnchanged": 140,
  "documentsFailed": 0,
  "durationMs": 5321,
  "error": null
}
```

A result with an `error` is returned with status `500`.

---

## Ingest a Directory {#directory}

```
POST /api/v1/ingest/directory
```

```json
{ "path": "/data/handbook", "patterns": ["*.md", "*.pdf"], "recursive": true, "metadata": { "team": "hr" } }
```

| Field | Default | Description |
|-------|---------|-------------|
| `path` | | Directory on the **server** |
| `patterns` | `["*.md", "*.txt", "*.pdf"]` | Glob patterns |
| `recursive` | `true` | Include subdirectories |
| `metadata` | `{}` | Added to every document |

The path must be inside an allowed root (`INGEST_ALLOWED_ROOTS`, comma-separated; by default the
configured directory sources such as `INGEST_DIR`). With no allowed roots the endpoint is
disabled. Paths are compared after resolving symlinks. A path outside the allowed roots answers
`400`.

---

## Ingest URLs {#url}

```
POST /api/v1/ingest/url
```

```json
{ "urls": ["https://docs.example.com/guide.html"], "metadata": { "source": "docs-site" } }
```

URLs must be `http`/`https`. When `INGEST_URL_ALLOWED_HOSTS` is set (comma-separated;
`*.example.com` matches subdomains) only those hosts are allowed. Private, loopback and
link-local addresses are refused unless `INGEST_URL_ALLOW_PRIVATE_NETWORKS=true`. Refused URLs
answer `400`.

---

## Run All Sources {#run}

```
POST /api/v1/ingest/run
```

Runs every enabled configured source and answers with an array of results. `409 Conflict` when
an ingestion run is already in progress. Progress is broadcast on the [WebSocket](/api/websocket).

## Run One Source {#run-source}

```
POST /api/v1/ingest/run/{source}
```

One result; `404` for an unknown source name, `409` when a run is in progress. The source
configured from `INGEST_DIR` is named `env-directory`.

---

## Status {#status}

```
GET /api/v1/ingest/status
```

```json
{
  "running": false,
  "lastRun": "2026-01-15T10:30:00Z",
  "lastResults": [ { ...result... } ],
  "nextScheduledRun": "2026-01-15T11:30:00Z",
  "sources": [ { ...source... } ]
}
```

## Sources {#sources}

```
GET /api/v1/ingest/sources
```

```json
[
  {
    "name": "env-directory",
    "sourceType": "directory",
    "enabled": true,
    "config": { "path": "/data", "patterns": "*.md,*.txt,*.pdf", "recursive": "true" }
  }
]
```

`sourceType` is `directory`, `url`, `database`, `web` or `s3`. Database passwords in
connection URLs are masked.

---
layout: page
title: Config API
parent: API Reference
nav_order: 9
---

# Config API
{: .no_toc }

Read the static configuration, list providers, and change the runtime settings without a
restart. Static settings come from environment variables (see
[Configuration](/guide/configuration)).
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

**Auth:** reads are open to any authenticated caller (access tokens need the `admin` scope);
changes, validation and the change history are **Admin** only.

## Get Configuration {#get}

```
GET /api/v1/config
```

```json
{
  "embedding": { "provider": "openai", "model": "text-embedding-3-small", "dimensions": 1536 },
  "llm": { "model": "openai/gpt-4o", "temperature": 0.1 },
  "rag": {
    "chunkingStrategy": "sentence",
    "chunkSize": 800,
    "chunkOverlap": 150,
    "topK": 5,
    "fusionStrategy": "rrf",
    "rrfK": 60,
    "searchMode": "hybrid",
    "fusionApplies": true,
    "reranking": "none",
    "queryTransform": "none",
    "limitations": []
  },
  "database": {
    "host": "localhost",
    "port": 15432,
    "database": "ragdb",
    "tableName": "rag_embeddings",
    "connected": true
  }
}
```

`rag.searchMode` is `hybrid` (vector + keyword, fused) or `vector` in permission mode, where
llm4s search is vector-only ([llm4s/llm4s#2716](https://github.com/llm4s/llm4s/issues/2716));
`fusionApplies` says whether `fusionStrategy`/`rrfK` have any effect, and `limitations` lists
settings that do not take effect and why.

These settings are static: they change only with a restart with new environment variables. Use
the [runtime settings](#runtime) for live changes.

---

## Get Providers {#providers}

```
GET /api/v1/config/providers
```

The LLM and embedding providers registered with llm4s's provider registry, with the models
llm4s knows for each and whether the provider is usable (its API key is set, or it needs none).

```json
{
  "embeddingProviders": [
    { "name": "openai", "models": ["text-embedding-3-large", "text-embedding-3-small"], "configured": true }
  ],
  "llmProviders": [
    { "name": "anthropic", "models": ["claude-sonnet-4-5", "..."], "configured": false },
    { "name": "openai", "models": ["gpt-4o", "gpt-4o-mini", "..."], "configured": true }
  ],
  "chunkingStrategies": ["simple", "sentence", "markdown", "semantic"],
  "fusionStrategies": ["rrf", "weighted", "vector_only", "keyword_only"]
}
```

---

## Runtime Settings {#runtime}

Settings that can change while the server runs. They are stored in PostgreSQL, so they survive
restarts and are shared by replicas.

```
GET /api/v1/config/runtime
```

```json
{
  "config": {
    "topK": 5,
    "fusionStrategy": "rrf",
    "rrfK": 60,
    "systemPrompt": "You are a helpful assistant...",
    "llmTemperature": 0.1,
    "chunkingStrategy": "sentence",
    "chunkSize": 800,
    "chunkOverlap": 150
  },
  "lastModified": "2026-01-15T10:30:00Z",
  "hotSettings": ["topK", "fusionStrategy", "rrfK", "systemPrompt", "llmModel", "llmTemperature"],
  "warmSettings": ["chunkingStrategy", "chunkSize", "chunkOverlap"]
}
```

*Hot* settings apply to the next query; *warm* settings apply to documents ingested from now
on (existing documents keep their chunks until re-ingested).

### Update {#runtime-update}

```
PUT /api/v1/config/runtime
```

Send only the fields to change:

```json
{ "topK": 8, "systemPrompt": "Answer in one paragraph." }
```

```json
{
  "success": true,
  "changes": [
    { "field": "topK", "oldValue": "5", "newValue": "8", "changeability": "hot", "effectiveImmediately": true, "note": null }
  ],
  "current": { ...config... },
  "message": "..."
}
```

Invalid values answer `400` with `error: "validation_failed"` and the failing fields in
`details`.

### Validate {#runtime-validate}

```
POST /api/v1/config/runtime/validate
```

Same body as update; nothing is changed.

```json
{
  "valid": false,
  "validations": [
    { "field": "topK", "valid": false, "currentValue": "5", "proposedValue": "0", "error": "...", "warning": null }
  ],
  "warnings": []
}
```

### History {#runtime-history}

```
GET /api/v1/config/runtime/history?page=1&pageSize=50
```

```json
{
  "changes": [
    { "id": 12, "field": "topK", "oldValue": "5", "newValue": "8", "changedAt": "2026-01-15T10:30:00Z", "changedBy": "user:alice" }
  ],
  "total": 12,
  "page": 1,
  "pageSize": 50,
  "hasMore": false
}
```

`changedBy` is the id of the principal that made the change: `user:<name>` (password login),
`oidc:<subject>`, `token:<id>` (access token), `api-key` (the static `API_KEY`) or `anonymous`
(open mode). It is `null` for changes recorded before ragbox recorded it.

### Metadata {#runtime-meta}

```
GET /api/v1/config/runtime/meta
```

A description of each runtime setting:

```json
[
  {
    "key": "topK",
    "value": "5",
    "type": "hot",
    "description": "Number of chunks to retrieve for context (a request's topK takes precedence)",
    "requiresRestart": false
  }
]
```

Descriptions end with where a setting does not apply (e.g. fusion in permission mode).

---

## Related

- [Chunking API](/api/chunking): per-collection chunking configuration and previews
- [Visibility API](/api/visibility): detailed configuration with changeability annotations

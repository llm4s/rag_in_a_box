---
layout: page
title: Query API
parent: API Reference
nav_order: 4
---

# Query API
{: .no_toc }

Retrieve relevant chunks and generate answers with the configured LLM.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

**Auth (all endpoints):** Authenticated; access tokens need the `query` scope. A
collection-restricted token must pass a `collection` pattern inside its restriction (the default
`*` is refused with `403`).

## Collection Patterns

| Pattern | Matches |
|---------|---------|
| `*` | All collections (default) |
| `exact/name` | That collection only |
| `parent/*` | Direct children of `parent` |
| `parent/**` | All descendants of `parent` |

## Who the Query Runs As

In permission mode (PostgreSQL search index available), results are filtered by the
authenticated caller's collection permissions and each document's `readableBy`
(see [Permissions](/guide/permissions)). `X-User-Id` / `X-Group-Ids` are only honoured from an
administrator credential with `AUTH_TRUSTED_PROXY_HEADERS=true`. Without permission mode every
caller sees every document.

---

## Query with Answer {#query}

```
POST /api/v1/query
```

### Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `question` | string | Yes | The question (at most 10,000 characters) |
| `collection` | string | No | Collection pattern (default `*`) |
| `topK` | integer | No | Chunks to retrieve (default: the runtime `topK`, initially `RAG_TOP_K` = 5) |
| `includeMetadata` | boolean | No | `false` returns contexts with an empty `metadata` (`documentId` and `chunkIndex` remain). Default `true` |
| `experimentId` | string | No | Route the query through this running [experiment](/api/experiments); otherwise the single running experiment (if any) applies |
| `overrides` | object | No | Per-request settings, see below |

`overrides` fields: `topK`, `fusionStrategy` (`rrf`, `weighted`, `vector_only`,
`keyword_only`), `systemPrompt`, `similarityThreshold`. Precedence is experiment group config,
then `overrides`, then `topK`, then the runtime settings.

`similarityThreshold` (0 to 1) drops retrieved chunks whose vector (cosine) similarity is below
it: they are neither sent to the LLM nor returned. It is compared with each chunk's vector
similarity, not with `score`, whose scale depends on the fusion strategy (with `rrf` it is a rank
score of about 1/(k + rank)); chunks found by keyword search only have no similarity and are
kept. With a threshold, ragbox runs llm4s's retrieval and answer steps itself (same prompt,
temperature 0.0) so the filter sits between them.

`overrides.llmTemperature` is rejected with `400`: llm4s 0.6.0 answers `/query` at temperature
0.0 ([llm4s/llm4s#2715](https://github.com/llm4s/llm4s/issues/2715)); it will be supported once
ragbox adopts the next llm4s release. The runtime `llmTemperature` applies to chat.

### Example

```bash
curl -X POST http://localhost:8080/api/v1/query \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $RAGBOX_TOKEN" \
  -d '{
    "question": "How do I configure authentication?",
    "collection": "docs/**",
    "topK": 5
  }'
```

### Response

```json
{
  "answer": "Set the AUTH_MODE environment variable...",
  "contexts": [
    {
      "content": "Authentication can be configured using...",
      "score": 0.92,
      "metadata": { "filename": "auth-guide.md", "collection": "docs/guides" },
      "documentId": "doc-123",
      "chunkIndex": 0
    }
  ],
  "usage": {
    "promptTokens": 450,
    "completionTokens": 120,
    "totalTokens": 570
  },
  "metrics": {
    "embeddingLatencyMs": 41,
    "searchLatencyMs": 12,
    "llmLatencyMs": 1830,
    "chunksRetrieved": 5,
    "chunksUsed": 5,
    "embeddingTokens": 9,
    "promptTokens": 450,
    "completionTokens": 120,
    "costUsd": 0.0023
  },
  "queryId": "5b0c2f4e-8a1d-4c7b-9e3f-0a1b2c3d4e5f"
}
```

`queryId` is the query's log id: pass it to [`POST /api/v1/feedback`](/api/analytics#feedback)
to rate the answer (only you, or an administrator, may).

`usage` is the answer completion's token usage. `metrics` comes from llm4s tracing: the
query's embedding, search and answer-generation latencies, tokens over every completion it
made (including query rewriting and LLM reranking, when enabled), and an estimated cost for
models whose pricing llm4s knows. Optional figures are `null` when unknown. Every query
(successful or failed) is recorded in the [query log](/api/analytics).

---

## Streaming Query {#stream}

```
POST /api/v1/query/stream
```

Same request body as [`/query`](#query). The response is `text/event-stream` with these
events, each `data` a JSON object:

| Event | Data |
|-------|------|
| `start` | `{"queryId", "timestamp"}` |
| `context` | `{"context": <context item>, "index", "timestamp"}`, one per retrieved chunk |
| `answer` | `{"answer", "timestamp"}`, the whole answer |
| `usage` | `{"usage": {"promptTokens", "completionTokens", "totalTokens"}, "timestamp"}`, when known |
| `complete` | `{"queryId", "totalContexts", "timestamp"}`; `queryId` (as in `start`) is the query-log id for `POST /feedback` |
| `error` | `{"error": "query_failed", "message", "timestamp"}`, instead of the events after `start` |

```
event: start
data: {"queryId":"5b0c...","timestamp":"2026-01-15T10:30:00Z"}

event: context
data: {"context":{"content":"...","score":0.92,"metadata":{},"documentId":"doc-123","chunkIndex":0},"index":0,"timestamp":"..."}

event: answer
data: {"answer":"Set the AUTH_MODE environment variable...","timestamp":"..."}

event: complete
data: {"queryId":"5b0c...","totalContexts":1,"timestamp":"..."}
```

The answer is not streamed token by token: llm4s 0.6.0 has no streaming RAG answer API
([llm4s/llm4s#2717](https://github.com/llm4s/llm4s/issues/2717)), so the full answer is
generated first and sent as one `answer` event. Validation and authorization failures are
ordinary JSON error responses, not events.

---

## Search Only {#search}

Retrieve chunks without calling the LLM.

```
POST /api/v1/search
```

### Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `query` | string | Yes | The search query (at most 10,000 characters) |
| `collection` | string | No | Collection pattern (default `*`) |
| `topK` | integer | No | Chunks to return (default: runtime `topK`) |

### Example

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $RAGBOX_TOKEN" \
  -d '{"query": "authentication configuration", "topK": 10}'
```

### Response

```json
{
  "results": [
    {
      "content": "Authentication can be configured using...",
      "score": 0.92,
      "metadata": { "filename": "auth-guide.md", "collection": "docs/guides" },
      "documentId": "doc-123",
      "chunkIndex": 0
    }
  ],
  "count": 1
}
```

Searches are not recorded in the query log.

---

## Error Responses

| Status | `error` | When |
|--------|---------|------|
| 400 | `bad_request` | Empty or too long question/query, invalid body |
| 400 | `configuration_error` | `/query` when no LLM could be built (check `LLM_MODEL` and the provider API key; the server log says why) |
| 403 | `forbidden` | Token without the `query` scope, or a pattern outside its collection restriction |
| 500 | `internal_error` | Retrieval or the LLM failed |

---
layout: page
title: Query API
parent: API Reference
nav_order: 3
---

# Query API

Endpoints for searching documents and generating answers.
{: .fs-6 .fw-300 }

## Query with Answer {#query}

Search for relevant documents and generate an answer using the configured LLM.

```
POST /api/v1/query
```

### Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `question` | string | Yes | The question to answer |
| `collection` | string | No | Collection pattern (default: `"*"`) |
| `topK` | integer | No | Number of chunks to retrieve (default: 5) |

### Collection Patterns

| Pattern | Description |
|---------|-------------|
| `*` | All collections (default) |
| `exact-name` | Exact collection match |
| `parent/*` | Direct children of parent |
| `parent/**` | All descendants of parent |

### Permissions

When permissions are enabled, results are filtered by the authenticated caller's permissions
(see [Permissions](../guide/permissions.md#who-the-query-runs-as)). `X-User-Id` / `X-Group-Ids`
are only honoured from an administrator credential with `AUTH_TRUSTED_PROXY_HEADERS=true`.

### Example Request

```bash
curl -X POST http://localhost:8080/api/v1/query \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer your-token" \
  -d '{
    "question": "How do I configure authentication?",
    "collection": "docs/*",
    "topK": 5
  }'
```

### Example Response

```json
{
  "answer": "To configure authentication, you can set the AUTH_MODE environment variable...",
  "contexts": [
    {
      "content": "Authentication can be configured using...",
      "documentId": "doc-123",
      "score": 0.92,
      "metadata": {
        "source": "auth-guide.md",
        "collection": "docs/guides"
      }
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
  }
}
```

`usage` is the answer completion's token usage. `metrics` comes from llm4s tracing: the
query's embedding, search and answer-generation latencies, tokens over every completion it
made (including query rewriting and LLM reranking, when enabled), and an estimated cost for
models whose pricing llm4s knows. `embeddingLatencyMs` and `searchLatencyMs` are present
when the embedding provider reports usage. The same figures are stored in the query log.

Streaming (`POST /api/v1/query/stream`) sends the contexts and then the whole answer as one
`answer` event: llm4s 0.6.0 has no streaming RAG answer API
([llm4s/llm4s#2717](https://github.com/llm4s/llm4s/issues/2717), added upstream for the next release).

---

## Search Only {#search}

Search for relevant documents without generating an answer. Useful for retrieval-only use cases.

```
POST /api/v1/search
```

### Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `query` | string | Yes | The search query |
| `collection` | string | No | Collection pattern (default: `"*"`) |
| `topK` | integer | No | Number of chunks to retrieve (default: 5) |

### Example Request

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "authentication configuration",
    "topK": 10
  }'
```

### Example Response

```json
{
  "results": [
    {
      "content": "Authentication can be configured using...",
      "documentId": "doc-123",
      "score": 0.92,
      "metadata": {
        "source": "auth-guide.md",
        "collection": "docs/guides"
      }
    }
  ]
}
```

---

## Error Responses

| Status | Error | Description |
|--------|-------|-------------|
| 400 | `bad_request` | Invalid query or missing required fields |
| 400 | `config_error` | LLM not configured (for /query endpoint) |
| 500 | `internal_error` | Search or LLM failed |

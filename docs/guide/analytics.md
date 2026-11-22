---
layout: page
title: Analytics
parent: User Guide
nav_order: 11
---

# Analytics Guide

Monitor query performance and collect feedback for RAG evaluation.
{: .fs-6 .fw-300 }

## Overview

RAG in a Box logs every answered query in PostgreSQL (the `query_logs` table) and provides
analytics for:

- **Performance monitoring**: latency across pipeline stages, from llm4s tracing
- **Cost and tokens**: embedding, prompt and completion tokens and an estimated cost
- **Quality evaluation**: user ratings and relevant-chunk feedback
- **Usage insights**: query volume and latency by collection pattern

The analytics endpoints expose every user's query text, so they are **administrator-only**
(`403` for anyone else). The [optimization suggestions](optimization.md) and
[experiments](experimentation.md) are built on the same logs.

---

## Query Logging

`POST /api/v1/query` (and its streaming variant) and every chat message are logged; failed
queries are logged too (`failed: true` with `errorMessage`), except requests rejected as
invalid. `POST /api/v1/search` (retrieval only) is not logged.

A log entry (`QueryLogEntry`) has:

| Field | Description |
|-------|-------------|
| `id` | Log entry id (UUID) |
| `queryText` | The question |
| `collectionPattern` | The collection pattern queried (`*` when none was given) |
| `userId` | The authenticated principal (e.g. `user:alice`, `api-key`, `token:<id>`) |
| `embeddingLatencyMs`, `searchLatencyMs`, `llmLatencyMs` | Stage latencies from llm4s tracing, when known |
| `totalLatencyMs` | End-to-end latency measured by the server |
| `chunksRetrieved`, `chunksUsed` | Chunks retrieved and used as context |
| `answerTokens` | Completion tokens |
| `promptTokens`, `embeddingTokens` | Prompt and embedding tokens |
| `costUsd` | Estimated cost, for models whose pricing llm4s knows |
| `userRating` | Feedback rating (1-5), once given |
| `experimentId`, `variant`, `configSnapshot` | The experiment and group (`baseline` / `variant`) the query was routed to, and the settings it ran with (`topK`, `searchMode`, `fusionStrategy`, `rrfK`, `reranking`, `systemPrompt`, and the `similarityThreshold` override when given; in permission mode `searchMode` is `vector-only` and fusion, `rrfK` and reranking, which do not apply there, are `null`) |
| `failed`, `errorMessage` | Whether answering failed, and why |
| `createdAt` | When the query was made |

---

## Analytics API

The examples assume `$TOKEN` is an administrator credential (`API_KEY`, an admin user's JWT,
or an admin-scoped access token).

### List Query Logs

Newest first, paginated:

```bash
curl "http://localhost:8080/api/v1/analytics/queries?page=1&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `from` | - | Start timestamp (ISO-8601, e.g. `2026-01-14T00:00:00Z`) |
| `to` | - | End timestamp (ISO-8601) |
| `collection` | - | Only queries whose collection pattern is exactly this |
| `page` | 1 | Page number |
| `pageSize` | 50 | Results per page (capped at 100) |

Response: `{"queries": [QueryLogEntry...], "total": 1500, "page": 1, "pageSize": 20}`.

### Get Query Details

```bash
curl http://localhost:8080/api/v1/analytics/queries/<id> \
  -H "Authorization: Bearer $TOKEN"
```

Returns one `QueryLogEntry`, or `404`.

### Get Analytics Summary

Aggregated statistics for a time period (default: the last 7 days):

```bash
curl "http://localhost:8080/api/v1/analytics/queries/summary?from=2026-01-01T00:00:00Z&to=2026-01-15T00:00:00Z" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "totalQueries": 1500,
  "averageLatencyMs": 850.0,
  "p50LatencyMs": 720,
  "p95LatencyMs": 1800,
  "p99LatencyMs": 2600,
  "averageChunksRetrieved": 4.2,
  "averageChunksUsed": 4.0,
  "averageRating": 4.1,
  "ratedQueriesCount": 320,
  "queriesWithFeedback": 320,
  "topCollections": [
    {"collection": "docs/**", "queryCount": 800, "averageLatencyMs": 790.5, "averageRating": 4.3}
  ],
  "periodStart": "2026-01-01T00:00:00Z",
  "periodEnd": "2026-01-15T00:00:00Z",
  "p95RetrievalLatencyMs": 240,
  "totalCostUsd": 1.73
}
```

`p95RetrievalLatencyMs` is the p95 of total minus answer-generation latency, over queries whose
LLM latency was recorded; `totalCostUsd` sums the estimated costs that are known.

---

## User Feedback

### Rating chat messages

The admin UI's chat rates answers with `PUT /api/v1/chat/messages/{messageId}/rate`
(`{"rating": 1-5}`), available to the session's owner and administrators. The rating is stored
on the message and on the query log entry of the answer.

### Submitting feedback for a query log entry

`POST /api/v1/query` returns the query's log id as `queryId` (the query stream sends it in its
`start` and `complete` events). Only the user who asked the query, or an administrator, may rate
it; anyone else gets `404`, as for an unknown id. Access tokens need the `query` scope.

```bash
curl -X POST http://localhost:8080/api/v1/feedback \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "queryId": "<query log id>",
    "rating": 4,
    "relevantChunks": ["chunk-1", "chunk-3"],
    "comment": "Good answer but second chunk was not relevant"
  }'
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `queryId` | string | Yes | The `queryId` of the `/query` response (the query log entry id) |
| `rating` | integer | Yes | 1-5 (anything else answers `400`) |
| `relevantChunks` | array | No | IDs of the chunks that were actually relevant |
| `comment` | string | No | Free-form feedback |

Response: `{"success": true, "message": "Feedback recorded"}`, or `404` for an unknown id.
Any authenticated user may call it (access tokens need the `admin` scope).

{: .note }
The `POST /api/v1/query` response does not include the query log id, so a client cannot
attach feedback to its own query without looking the id up in the (admin-only) analytics.
Rate chat messages instead.

### Rating Scale

| Rating | Meaning |
|--------|---------|
| 1 | Very poor - completely wrong |
| 2 | Poor - mostly unhelpful |
| 3 | Okay - partially helpful |
| 4 | Good - mostly helpful |
| 5 | Excellent - exactly what was needed |

---

## Performance Monitoring

### Latency Breakdown

| Metric | Description |
|--------|-------------|
| `embeddingLatencyMs` | Embedding the query |
| `searchLatencyMs` | The rest of retrieval (search, reranking, query rewriting) |
| `llmLatencyMs` | Answer generation |
| `totalLatencyMs` | End-to-end |

The same values are returned in each query response's `metrics`.

### Identifying Bottlenecks

```bash
# Recent slow queries (> 2 seconds)
curl -s "http://localhost:8080/api/v1/analytics/queries?pageSize=100" \
  -H "Authorization: Bearer $TOKEN" | jq '.queries | map(select(.totalLatencyMs > 2000))'
```

### Typical Latency Ranges

| Component | Typical | Warning |
|-----------|---------|---------|
| Embedding | 50-200ms | > 500ms |
| Search | 20-100ms | > 300ms |
| LLM | 500-2000ms | > 5000ms |
| Total | 700-2500ms | > 5000ms |

For server-wide Prometheus metrics enable `/metrics` with `METRICS_ENABLED=true`; see
[Configuration](configuration.md#metrics).

---

## Using the Logs for Evaluation

### Collect ground truth

```bash
# Highly rated queries
curl -s "http://localhost:8080/api/v1/analytics/queries?pageSize=100" \
  -H "Authorization: Bearer $TOKEN" | jq '[.queries[] | select(.userRating >= 4)]'
```

### Track retrieval quality

With `relevantChunks` feedback you can compute precision (relevant chunks / retrieved chunks)
offline.

### Export for analysis

```bash
for page in 1 2 3; do
  curl -s "http://localhost:8080/api/v1/analytics/queries?from=2026-01-01T00:00:00Z&pageSize=100&page=$page" \
    -H "Authorization: Bearer $TOKEN" \
    | jq -r '.queries[] | [.createdAt, .queryText, .totalLatencyMs, .userRating] | @csv'
done > queries.csv
```

### Alerting on low ratings

```bash
rating=$(curl -s "http://localhost:8080/api/v1/analytics/queries/summary" \
  -H "Authorization: Bearer $TOKEN" | jq '.averageRating // 5')
if (( $(echo "$rating < 3.5" | bc -l) )); then
  echo "Warning: average rating dropped to $rating"
fi
```

---

## Best Practices

- Review analytics regularly: latency spikes, ratings, failed queries.
- Make feedback easy to give (the chat UI's rating buttons) and watch trends over time.
- Capture a baseline summary before changing settings, or better, run an
  [experiment](experimentation.md).
- Suggested alert thresholds: p95 latency > 3s (warning) / > 5s (critical); average rating
  < 3.5 / < 3.0.

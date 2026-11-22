---
layout: page
title: Analytics API
parent: API Reference
nav_order: 7
---

# Analytics API
{: .no_toc }

The query log, analytics summaries, feedback and optimization suggestions. See the
[Analytics guide](/guide/analytics) and [Optimization guide](/guide/optimization).
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

**Auth:** everything under `/api/v1/analytics` is **Admin** only (the query log contains every
user's questions). `POST /api/v1/feedback` is for the user who asked the query, or an
administrator (access tokens need the `query` scope).

Time parameters (`from`, `to`) are ISO-8601 instants such as `2026-01-01T00:00:00Z`.

## List Query Logs {#list}

```
GET /api/v1/analytics/queries
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `from` | | Only queries at or after this instant |
| `to` | | Only queries before this instant |
| `collection` | | Collection pattern the query was made with |
| `page` | `1` | 1-based page |
| `pageSize` | `50` | Page size, capped at 100 |

```json
{
  "queries": [
    {
      "id": "q-123",
      "queryText": "How do I configure authentication?",
      "collectionPattern": "*",
      "userId": "user:alice",
      "embeddingLatencyMs": 41,
      "searchLatencyMs": 12,
      "llmLatencyMs": 1830,
      "totalLatencyMs": 1900,
      "chunksRetrieved": 5,
      "chunksUsed": 5,
      "answerTokens": 120,
      "userRating": 4,
      "experimentId": null,
      "configSnapshot": { "topK": 5, "fusionStrategy": "rrf", "systemPrompt": "...", "llmTemperature": null },
      "createdAt": "2026-01-15T10:30:00Z",
      "promptTokens": 450,
      "embeddingTokens": 9,
      "costUsd": 0.0023,
      "variant": null,
      "failed": false,
      "errorMessage": null
    }
  ],
  "total": 1234,
  "page": 1,
  "pageSize": 50
}
```

Failed queries are logged too (`failed: true`, `errorMessage`). `variant` is `baseline` or
`variant` when the query ran in an [experiment](/api/experiments).

---

## Analytics Summary {#summary}

```
GET /api/v1/analytics/queries/summary?from=...&to=...
```

Defaults to the last 7 days.

```json
{
  "totalQueries": 1234,
  "averageLatencyMs": 1450.5,
  "p50LatencyMs": 1200,
  "p95LatencyMs": 2800,
  "p99LatencyMs": 4100,
  "averageChunksRetrieved": 5.0,
  "averageChunksUsed": 4.2,
  "averageRating": 4.1,
  "ratedQueriesCount": 150,
  "queriesWithFeedback": 150,
  "topCollections": [
    { "collection": "docs/**", "queryCount": 800, "averageLatencyMs": 1400.0, "averageRating": 4.3 }
  ],
  "periodStart": "2026-01-08T00:00:00Z",
  "periodEnd": "2026-01-15T00:00:00Z",
  "p95RetrievalLatencyMs": 60,
  "totalCostUsd": 2.75
}
```

---

## Get Query Log Entry {#get}

```
GET /api/v1/analytics/queries/{id}
```

One entry (same shape as in the list), or `404`.

---

## Submit Feedback {#feedback}

Rate a logged query.

```
POST /api/v1/feedback
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `queryId` | string | Yes | The `queryId` of the `POST /query` response, or of the query stream's `start`/`complete` events |
| `rating` | integer | Yes | 1 to 5 |
| `relevantChunks` | string[] | No | Chunks the user found relevant |
| `comment` | string | No | Free-text comment |

```json
{ "success": true, "message": "Feedback recorded" }
```

`400` for a rating outside 1 to 5. `404` for an unknown `queryId`, and also for a query someone
else asked (unless the caller is an administrator), so query ids are not revealed.

---

## Optimization Suggestions {#suggestions}

Rule-based suggestions derived from the query log (latency, ratings, chunk usage, zero-result
queries) and the current configuration.

```
GET /api/v1/analytics/suggestions
```

| Parameter | Description |
|-----------|-------------|
| `from`, `to` | Analysis window |
| `collection` | Only analyse this collection |
| `type` | Filter: `chunking`, `search`, `embedding`, `content` |
| `severity` | Filter: `critical`, `warning`, `info` |

```json
{
  "suggestions": [
    {
      "id": "s-1",
      "suggestionType": "search",
      "severity": "warning",
      "title": "Low average rating",
      "description": "...",
      "collection": "docs/**",
      "evidence": {
        "metric": "avgRating",
        "currentValue": 2.8,
        "threshold": 3.5,
        "sampleSize": 120,
        "timeRange": "7 days"
      },
      "recommendation": "...",
      "estimatedImpact": "...",
      "currentValue": "5",
      "suggestedValue": "8",
      "createdAt": "2026-01-15T10:30:00Z"
    }
  ],
  "analyzedQueries": 1234,
  "analyzedCollections": 3,
  "analysisTimestamp": "2026-01-15T10:30:00Z"
}
```

### Suggestions Summary {#suggestions-summary}

```
GET /api/v1/analytics/suggestions/summary?from=...&to=...
```

```json
{ "criticalCount": 0, "warningCount": 2, "infoCount": 3, "total": 5, "topIssues": ["..."] }
```

### Suggestions for a Collection {#collection-suggestions}

```
GET /api/v1/analytics/suggestions/collection/{name}?from=...&to=...
```

Same shape as the full list, limited to suggestions for that collection.

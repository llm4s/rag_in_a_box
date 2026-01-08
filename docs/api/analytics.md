---
layout: page
title: Analytics API
parent: API Reference
nav_order: 5
---

# Analytics API

Endpoints for query analytics and feedback collection.
{: .fs-6 .fw-300 }

## List Query Logs {#list}

Retrieve paginated query logs for analysis.

```
GET /api/v1/analytics/queries
```

### Query Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `from` | ISO-8601 | - | Start timestamp filter |
| `to` | ISO-8601 | - | End timestamp filter |
| `collection` | string | - | Filter by collection |
| `page` | integer | 1 | Page number |
| `pageSize` | integer | 50 | Results per page (max 100) |

### Example Request

```bash
curl "http://localhost:8080/api/v1/analytics/queries?from=2024-01-01T00:00:00Z&pageSize=20" \
  -H "Authorization: Bearer your-token"
```

### Example Response

```json
{
  "queries": [
    {
      "id": "query-123",
      "queryText": "How do I configure auth?",
      "collectionPattern": "docs/*",
      "userId": "user-456",
      "timestamp": "2024-01-15T10:30:00Z",
      "totalLatencyMs": 1250,
      "chunksRetrieved": 5,
      "chunksUsed": 3,
      "answerTokens": 120,
      "feedback": {
        "rating": 4,
        "comment": "Helpful answer"
      }
    }
  ],
  "total": 1,
  "page": 1,
  "pageSize": 20
}
```

---

## Get Query Details {#get}

Retrieve a single query log entry.

```
GET /api/v1/analytics/queries/:id
```

### Example Response

```json
{
  "id": "query-123",
  "queryText": "How do I configure auth?",
  "collectionPattern": "docs/*",
  "userId": "user-456",
  "timestamp": "2024-01-15T10:30:00Z",
  "embeddingLatencyMs": 150,
  "searchLatencyMs": 80,
  "llmLatencyMs": 1020,
  "totalLatencyMs": 1250,
  "chunksRetrieved": 5,
  "chunksUsed": 3,
  "answerTokens": 120
}
```

---

## Analytics Summary {#summary}

Get aggregated analytics for a time period.

```
GET /api/v1/analytics/queries/summary
```

### Query Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `from` | ISO-8601 | 7 days ago | Start timestamp |
| `to` | ISO-8601 | now | End timestamp |

### Example Request

```bash
curl "http://localhost:8080/api/v1/analytics/queries/summary?from=2024-01-01T00:00:00Z" \
  -H "Authorization: Bearer your-token"
```

### Example Response

```json
{
  "period": {
    "from": "2024-01-01T00:00:00Z",
    "to": "2024-01-15T10:30:00Z"
  },
  "totalQueries": 1500,
  "averageLatencyMs": 850,
  "p50LatencyMs": 720,
  "p95LatencyMs": 1800,
  "averageChunksRetrieved": 4.2,
  "averageRating": 4.1,
  "feedbackCount": 320,
  "queriesByCollection": {
    "docs/guides": 800,
    "docs/api": 500,
    "other": 200
  }
}
```

---

## Submit Feedback {#feedback}

Submit user feedback for a query.

```
POST /api/v1/feedback
```

### Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `queryId` | string | Yes | Query ID to provide feedback for |
| `rating` | integer | Yes | Rating from 1-5 |
| `relevantChunks` | array | No | IDs of relevant chunks |
| `comment` | string | No | Optional feedback text |

### Example Request

```bash
curl -X POST http://localhost:8080/api/v1/feedback \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer your-token" \
  -d '{
    "queryId": "query-123",
    "rating": 4,
    "relevantChunks": ["chunk-1", "chunk-3"],
    "comment": "The second chunk was not relevant"
  }'
```

### Example Response

```json
{
  "success": true,
  "message": "Feedback recorded"
}
```

---

## Error Responses

| Status | Error | Description |
|--------|-------|-------------|
| 400 | `bad_request` | Invalid rating (must be 1-5) |
| 404 | `not_found` | Query ID not found |
| 500 | `internal_error` | Database error |

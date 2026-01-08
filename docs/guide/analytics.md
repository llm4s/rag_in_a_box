---
layout: page
title: Analytics
parent: User Guide
nav_order: 7
---

# Analytics Guide

Monitor query performance and collect feedback for RAG evaluation.
{: .fs-6 .fw-300 }

## Overview

RAG in a Box automatically logs queries and provides analytics for:

- **Performance monitoring**: Latency tracking across pipeline stages
- **Quality evaluation**: User feedback collection
- **Usage insights**: Query patterns and collection usage

---

## Query Logging

Every query is automatically logged with:

| Field | Description |
|-------|-------------|
| `queryText` | The user's question |
| `collectionPattern` | Target collection(s) |
| `userId` | User who made the query (if available) |
| `timestamp` | When the query was made |
| `totalLatencyMs` | End-to-end latency |
| `chunksRetrieved` | Number of chunks found |
| `chunksUsed` | Chunks included in context |
| `answerTokens` | Tokens used for response |

---

## Analytics API

### List Query Logs

Retrieve paginated query history:

```bash
curl "http://localhost:8080/api/v1/analytics/queries?page=1&pageSize=20" \
  -H "Authorization: Bearer your-token"
```

#### Query Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `from` | - | Start timestamp (ISO-8601) |
| `to` | - | End timestamp (ISO-8601) |
| `collection` | - | Filter by collection |
| `page` | 1 | Page number |
| `pageSize` | 50 | Results per page (max 100) |

#### Example with Filters

```bash
# Last 24 hours, docs collection only
curl "http://localhost:8080/api/v1/analytics/queries?\
from=2024-01-14T00:00:00Z&\
collection=docs/*&\
pageSize=50"
```

### Get Query Details

Retrieve a specific query log:

```bash
curl http://localhost:8080/api/v1/analytics/queries/query-123 \
  -H "Authorization: Bearer your-token"
```

### Get Analytics Summary

Aggregated statistics for a time period:

```bash
curl "http://localhost:8080/api/v1/analytics/queries/summary?\
from=2024-01-01T00:00:00Z&\
to=2024-01-15T00:00:00Z" \
  -H "Authorization: Bearer your-token"
```

Default period is the last 7 days.

---

## User Feedback

Collect feedback on query quality for RAGA (RAG Assessment) evaluation.

### Submit Feedback

```bash
curl -X POST http://localhost:8080/api/v1/feedback \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer your-token" \
  -d '{
    "queryId": "query-123",
    "rating": 4,
    "relevantChunks": ["chunk-1", "chunk-3"],
    "comment": "Good answer but second chunk was not relevant"
  }'
```

### Feedback Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `queryId` | string | Yes | Query to provide feedback for |
| `rating` | integer | Yes | Rating from 1-5 |
| `relevantChunks` | array | No | IDs of actually relevant chunks |
| `comment` | string | No | Free-form feedback text |

### Rating Scale

| Rating | Meaning |
|--------|---------|
| 1 | Very poor - completely wrong |
| 2 | Poor - mostly unhelpful |
| 3 | Okay - partially helpful |
| 4 | Good - mostly helpful |
| 5 | Excellent - exactly what was needed |

---

## Summary Response

The analytics summary provides aggregated metrics:

```json
{
  "period": {
    "from": "2024-01-01T00:00:00Z",
    "to": "2024-01-15T00:00:00Z"
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

## Performance Monitoring

### Latency Breakdown

Each query log includes latency breakdown (when available):

| Metric | Description |
|--------|-------------|
| `embeddingLatencyMs` | Time to generate query embedding |
| `searchLatencyMs` | Time for vector search |
| `llmLatencyMs` | Time for answer generation |
| `totalLatencyMs` | End-to-end time |

### Identifying Bottlenecks

```bash
# Get recent slow queries (> 2 seconds)
curl "http://localhost:8080/api/v1/analytics/queries?\
pageSize=100" | jq '.queries | map(select(.totalLatencyMs > 2000))'
```

### Typical Latency Ranges

| Component | Typical | Warning |
|-----------|---------|---------|
| Embedding | 50-200ms | > 500ms |
| Search | 20-100ms | > 300ms |
| LLM | 500-2000ms | > 5000ms |
| Total | 700-2500ms | > 5000ms |

---

## RAGA Evaluation

Use analytics data for RAG Assessment:

### 1. Collect Ground Truth

Build a test set from real queries with user feedback:

```bash
# Export highly-rated queries as ground truth
curl "http://localhost:8080/api/v1/analytics/queries?\
pageSize=1000" | jq '[.queries[] | select(.feedback.rating >= 4)]'
```

### 2. Track Retrieval Quality

Use `relevantChunks` feedback to measure:

- **Precision**: Relevant chunks / Retrieved chunks
- **Recall**: Relevant chunks found / Total relevant

### 3. Monitor Trends

Track metrics over time:

```bash
# Weekly summaries
for week in {1..4}; do
  curl "http://localhost:8080/api/v1/analytics/queries/summary?\
from=2024-01-$((week*7-6))T00:00:00Z&\
to=2024-01-$((week*7))T00:00:00Z"
done
```

---

## Integration Examples

### Dashboard Integration

Fetch summary data for a monitoring dashboard:

```javascript
async function fetchAnalytics() {
  const response = await fetch('/api/v1/analytics/queries/summary', {
    headers: { 'Authorization': `Bearer ${token}` }
  });
  return response.json();
}
```

### Alerting on Low Ratings

Monitor for quality degradation:

```bash
# Check average rating (alert if < 3.5)
rating=$(curl -s "http://localhost:8080/api/v1/analytics/queries/summary" \
  | jq '.averageRating')

if (( $(echo "$rating < 3.5" | bc -l) )); then
  echo "Warning: Average rating dropped to $rating"
fi
```

### Export for Analysis

Export query logs for external analysis:

```bash
# Export last month to CSV
curl "http://localhost:8080/api/v1/analytics/queries?\
from=2024-01-01T00:00:00Z&\
pageSize=10000" | jq -r \
  '.queries[] | [.timestamp, .queryText, .totalLatencyMs, .feedback.rating] | @csv' \
  > queries.csv
```

---

## Best Practices

### 1. Regular Review

Review analytics weekly to catch issues early:
- Check for latency spikes
- Monitor feedback ratings
- Identify problematic queries

### 2. Feedback Loop

Encourage users to provide feedback:
- Make feedback easy to submit
- Use ratings to identify improvement areas
- Track trends over time

### 3. Set Baselines

Establish performance baselines:

```bash
# Capture baseline metrics
curl "http://localhost:8080/api/v1/analytics/queries/summary" \
  > baseline-metrics.json
```

### 4. Alert Thresholds

Set up alerts for:

| Metric | Warning | Critical |
|--------|---------|----------|
| p95 Latency | > 3s | > 5s |
| Average Rating | < 3.5 | < 3.0 |
| Error Rate | > 1% | > 5% |

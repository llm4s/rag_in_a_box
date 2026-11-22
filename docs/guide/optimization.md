---
layout: page
title: Optimization
parent: User Guide
nav_order: 12
---

# Optimization Guide

Get automated suggestions to improve RAG performance based on your query analytics.
{: .fs-6 .fw-300 }

## Overview

RAG in a Box includes an intelligent suggestion engine that analyzes your query patterns, user feedback, and system configuration to provide actionable optimization recommendations.

The system analyzes:

- **Query performance**: Latency patterns
- **Retrieval quality**: Chunk utilization
- **User feedback**: Ratings
- **Configuration**: The current runtime chunking and search settings and the embedding model

---

## Suggestions API

### Get All Suggestions

Retrieve optimization suggestions for your system. Like the rest of `/api/v1/analytics`,
the suggestion endpoints are **administrator-only**.

```bash
curl "http://localhost:8080/api/v1/analytics/suggestions" \
  -H "Authorization: Bearer your-token"
```

#### Query Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `from` | 7 days ago | Start of analysis period (ISO-8601) |
| `to` | now | End of analysis period (ISO-8601) |
| `collection` | - | Only evaluate this collection pattern's per-collection rules (global suggestions are still returned) |
| `type` | - | Filter by type: chunking, search, embedding, content |
| `severity` | - | Filter by severity: critical, warning, info |

#### Example: Filter by Severity

```bash
curl "http://localhost:8080/api/v1/analytics/suggestions?severity=critical" \
  -H "Authorization: Bearer your-token"
```

### Get Suggestions Summary

Get a count summary without full details:

```bash
curl "http://localhost:8080/api/v1/analytics/suggestions/summary" \
  -H "Authorization: Bearer your-token"
```

Response:

```json
{
  "criticalCount": 1,
  "warningCount": 3,
  "infoCount": 5,
  "total": 9,
  "topIssues": [
    "High Zero-Result Rate for 'docs'",
    "Low Chunk Utilization",
    "High Query Latency"
  ]
}
```

The full response is `{"suggestions": [...], "analyzedQueries": 120, "analyzedCollections": 3, "analysisTimestamp": "..."}`.

### Get Collection-Specific Suggestions

Only the suggestions for one collection pattern (`from` / `to` are accepted too):

```bash
curl "http://localhost:8080/api/v1/analytics/suggestions/collection/docs" \
  -H "Authorization: Bearer your-token"
```

---

## Suggestion Types

The engine is rule-based: it compares the period's query-log summary (see
[Analytics](analytics.md#get-analytics-summary)) and the current runtime settings with fixed
thresholds. These are all the rules:

### Global rules

| Type | Severity | Title | Trigger |
|------|----------|-------|---------|
| chunking | warning | Low Chunk Utilization | chunks used / retrieved < 40% and > 5 chunks retrieved on average |
| chunking | info | High Chunk Utilization with Moderate Ratings | chunks used / retrieved > 90% and average rating < 4.0 |
| chunking | warning | Low Retrieval Volume | < 3 chunks retrieved on average and average rating < 3.5 |
| chunking | info | High Retrieval Volume | > 15 chunks retrieved on average |
| chunking | info | No Chunk Overlap Configured | runtime `chunkOverlap` is 0 |
| search | info | TopK May Be Too High | `topK` > 3 x average chunks used |
| search | warning | High Retrieval Latency | p95 retrieval latency (total minus answer generation) > 500 ms |
| embedding | warning | Low Ratings Despite Retrieval | average rating < 2.5 with > 3 chunks retrieved on average |

### Per-collection rules

Evaluated for each collection pattern in the summary's `topCollections` with at least 5
queries:

| Type | Severity | Title | Trigger |
|------|----------|-------|---------|
| chunking | warning | Low Chunk Utilization in '...' | as the global rule |
| chunking | info | Consider Markdown Strategy for '...' | the name contains `docs` or `readme` and the runtime strategy is not `markdown` |
| content | warning | Low Ratings for '...' | the collection's average rating < 2.5 |
| content | critical | High Zero-Result Rate for '...' | > 10% of the collection's queries returned no chunks |
| search | warning | High Latency for '...' | estimated p95 latency > 750 ms |

{: .note }
The per-collection analysis is approximate: it reuses the global chunk averages, estimates the
p95 latency as 1.5 x the collection's average latency, and does not yet count zero-result
queries per collection (so the zero-result rule never fires).

With fewer than 10 queries in the period, the only suggestion is an `info` "Insufficient
Data" entry (its `suggestionType` is `info`).

---

## Response Format

Each suggestion includes:

```json
{
  "id": "abc-123",
  "suggestionType": "chunking",
  "severity": "warning",
  "title": "Low Chunk Utilization",
  "description": "Only 35% of retrieved chunks are being used...",
  "collection": "docs",
  "evidence": {
    "metric": "chunk_utilization",
    "currentValue": 0.35,
    "threshold": 0.4,
    "sampleSize": 150,
    "timeRange": "Last 7 days"
  },
  "recommendation": "Reduce chunk size to improve precision...",
  "estimatedImpact": "Better retrieval precision, lower token usage",
  "currentValue": "800 chars",
  "suggestedValue": "480 chars",
  "createdAt": "2024-01-15T10:30:00Z"
}
```

### Severity Levels

| Severity | Priority | Description |
|----------|----------|-------------|
| `critical` | 1 | Major performance or quality issue requiring immediate attention |
| `warning` | 2 | Noticeable improvement opportunity |
| `info` | 3 | Minor optimization or best practice recommendation |

---

## Acting on Suggestions

### Chunking Changes

Update chunking configuration via the Runtime Config API:

```bash
# Update chunk size (applies to documents ingested from now on)
curl -X PUT http://localhost:8080/api/v1/config/runtime \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer your-token" \
  -d '{"chunkSize": 500}'
```

Or configure per-collection:

```bash
# Set collection-specific chunking
curl -X PUT http://localhost:8080/api/v1/collections/docs/config \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer your-token" \
  -d '{
    "strategy": "markdown",
    "targetSize": 500,
    "overlap": 75
  }'
```

### Search Parameter Changes

Update search settings:

```bash
curl -X PUT http://localhost:8080/api/v1/config/runtime \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer your-token" \
  -d '{"topK": 5}'
```

The new value applies to the next query, and survives a restart (see
[Runtime Settings](configuration.md#runtime-settings)).

### Re-indexing After Changes

Chunking changes apply to documents ingested afterwards. Ingestion sources skip unchanged
documents (content hashes), so re-running a source does not re-chunk them; to re-chunk, re-upload
the documents (or delete and re-ingest them).

```bash
# Run all configured ingestion sources (admin)
curl -X POST http://localhost:8080/api/v1/ingest/run \
  -H "Authorization: Bearer your-token"
```

---

## Admin UI

Access the Optimization dashboard at `/admin/suggestions`:

### Dashboard Features

- **Summary cards**: Quick view of critical, warning, and info counts
- **Type filters**: Filter by chunking, search, embedding, or content
- **Severity filters**: Focus on critical issues first
- **Collection filters**: Drill into specific collections
- **Evidence details**: Expand cards to see metrics and thresholds
- **Configuration guidance**: Current vs. suggested values

### Accessing from Analytics

The Analytics page includes a banner linking to suggestions when you have enough query data (10+ queries).

---

## Minimum Data Requirements

The suggestion engine requires sufficient data for meaningful analysis:

| Requirement | Value |
|-------------|-------|
| Minimum queries in the period | 10 |
| Per-collection minimum | 5 queries |

Rating-based rules only fire when there are rated queries.

If you don't have enough data, the system will display an "Insufficient Data" info message.

---

## Best Practices

### 1. Regular Review

Check suggestions weekly to catch issues early:

```bash
# Quick summary check
curl -s "http://localhost:8080/api/v1/analytics/suggestions/summary" \
  -H "Authorization: Bearer $TOKEN" | jq '"\(.criticalCount) critical, \(.warningCount) warnings"'
```

### 2. Address Critical Issues First

Focus on critical severity suggestions before warnings:

```bash
curl "http://localhost:8080/api/v1/analytics/suggestions?severity=critical" \
  -H "Authorization: Bearer $TOKEN"
```

### 3. Test Changes Incrementally

1. Note current metrics (avg rating, latency)
2. Apply one suggestion at a time
3. Wait for new data (24-48 hours)
4. Compare metrics
5. Proceed to next suggestion if improved

### 4. Monitor After Changes

After applying suggestions, watch for:

- Improved chunk utilization ratios
- Better user ratings
- Lower latency percentiles
- Fewer zero-result queries

### 5. Per-Collection Optimization

Different content types may need different settings:

| Content Type | Recommended Strategy | Chunk Size |
|--------------|---------------------|------------|
| Documentation | markdown | 600-800 |
| Code | sentence | 400-600 |
| Prose/Articles | sentence | 800-1000 |
| Log files | simple | 400-500 |

---

## Integration Examples

### Automated Alerting

Set up alerts for new critical suggestions:

```bash
#!/bin/bash
critical=$(curl -s "http://localhost:8080/api/v1/analytics/suggestions/summary" \
  -H "Authorization: Bearer $TOKEN" | jq '.criticalCount')

if [ "$critical" -gt 0 ]; then
  echo "Alert: $critical critical optimization suggestions found"
  # Send to Slack, PagerDuty, etc.
fi
```

### Scheduled Reports

Generate weekly optimization reports:

```bash
# Weekly optimization report
curl -s "http://localhost:8080/api/v1/analytics/suggestions" \
  -H "Authorization: Bearer $TOKEN" | jq '{
    total: .suggestions | length,
    byType: .suggestions | group_by(.suggestionType) | map({type: .[0].suggestionType, count: length}),
    topIssues: .suggestions[:5] | map(.title)
  }'
```

### CI/CD Integration

Check for critical issues in deployment pipelines:

```bash
# Fail if critical issues exist
critical=$(curl -s "$RAG_URL/api/v1/analytics/suggestions?severity=critical" \
  -H "Authorization: Bearer $TOKEN" | jq '.suggestions | length')

if [ "$critical" -gt 0 ]; then
  echo "Error: $critical critical RAG optimization issues detected"
  exit 1
fi
```

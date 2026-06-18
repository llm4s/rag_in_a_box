---
layout: page
title: Optimization
parent: User Guide
nav_order: 8
---

# Optimization Guide

Get automated suggestions to improve RAG performance based on your query analytics.
{: .fs-6 .fw-300 }

## Overview

RAG in a Box includes an intelligent suggestion engine that analyzes your query patterns, user feedback, and system configuration to provide actionable optimization recommendations.

The system analyzes:

- **Query performance**: Latency patterns and bottlenecks
- **Retrieval quality**: Chunk utilization and relevance
- **User feedback**: Ratings and satisfaction trends
- **Configuration**: Chunking, search, and embedding settings

---

## Suggestions API

### Get All Suggestions

Retrieve optimization suggestions for your system:

```bash
curl "http://localhost:8080/api/v1/analytics/suggestions" \
  -H "Authorization: Bearer your-token"
```

#### Query Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `from` | 7 days ago | Start of analysis period (ISO-8601) |
| `to` | now | End of analysis period (ISO-8601) |
| `collection` | - | Filter to specific collection |
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

### Get Collection-Specific Suggestions

Focus on a single collection:

```bash
curl "http://localhost:8080/api/v1/analytics/suggestions/collection/docs" \
  -H "Authorization: Bearer your-token"
```

---

## Suggestion Types

### Chunking Suggestions

Related to document chunking configuration:

| Trigger | Suggestion |
|---------|------------|
| chunks_used / chunks_retrieved < 40% | Reduce chunk size for better precision |
| chunks_used / chunks_retrieved > 90% with low ratings | Increase chunk size or topK |
| avg_chunks_retrieved < 3 with low ratings | Chunk size too large |
| avg_chunks_retrieved > 15 | Chunk size too small |
| overlap = 0 | Add overlap (10-20% of chunk size) |
| .md files with "simple" strategy | Switch to markdown strategy |

### Search Suggestions

Related to search parameters:

| Trigger | Suggestion |
|---------|------------|
| topK > avg_chunks_used * 3 | Reduce topK for faster queries |
| topK == chunks_retrieved with low ratings | Increase topK |
| p95_latency > 500ms | Optimize search configuration |

### Embedding Suggestions

Related to embedding model selection:

| Trigger | Suggestion |
|---------|------------|
| Low ratings despite retrieving chunks | Try different embedding model |
| Expensive model with low query volume | Consider cheaper model |

### Content Suggestions

Related to document quality:

| Trigger | Suggestion |
|---------|------------|
| Collection avg_rating < 2.5 | Review source documents |
| High % of zero-result queries | Check document indexing |
| Documents not updated in 90+ days | Consider re-ingestion |

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
# Update chunk size
curl -X PUT http://localhost:8080/api/v1/config/runtime \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer your-token" \
  -d '{
    "settings": {
      "defaultChunkSize": "500"
    }
  }'
```

Or configure per-collection:

```bash
# Set collection-specific chunking
curl -X PUT http://localhost:8080/api/v1/config/collections/docs \
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
  -d '{
    "settings": {
      "topK": "5"
    }
  }'
```

### Re-indexing After Changes

After changing chunking settings, re-ingest documents to apply new chunking:

```bash
# Trigger re-ingestion for a source
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
| Minimum queries | 10 |
| Per-collection minimum | 5 queries |
| Rated queries (for quality suggestions) | 3+ |

If you don't have enough data, the system will display an "Insufficient Data" info message.

---

## Best Practices

### 1. Regular Review

Check suggestions weekly to catch issues early:

```bash
# Quick summary check
curl "http://localhost:8080/api/v1/analytics/suggestions/summary" \
  | jq '"\(.criticalCount) critical, \(.warningCount) warnings"'
```

### 2. Address Critical Issues First

Focus on critical severity suggestions before warnings:

```bash
curl "http://localhost:8080/api/v1/analytics/suggestions?severity=critical"
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
  | jq '.criticalCount')

if [ "$critical" -gt 0 ]; then
  echo "Alert: $critical critical optimization suggestions found"
  # Send to Slack, PagerDuty, etc.
fi
```

### Scheduled Reports

Generate weekly optimization reports:

```bash
# Weekly optimization report
curl "http://localhost:8080/api/v1/analytics/suggestions" \
  | jq '{
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
  | jq '.suggestions | length')

if [ "$critical" -gt 0 ]; then
  echo "Error: $critical critical RAG optimization issues detected"
  exit 1
fi
```

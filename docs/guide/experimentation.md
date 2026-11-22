---
layout: page
title: Experimentation
parent: User Guide
nav_order: 9
---

# Experimentation Guide

A/B test different RAG configurations to find optimal settings for your use case.
{: .fs-6 .fw-300 }

## Overview

The Experimentation Framework enables you to compare different RAG configurations without requiring full corpus re-indexing for every test. Create experiments to test different parameter combinations and compare the groups with two-sample significance tests.

Key capabilities:

- **Per-query parameter overrides**: Test hot parameters instantly
- **A/B experiments**: Split traffic between baseline and variant configurations
- **Statistical analysis**: Determine if improvements are significant
- **Experiment tracking**: All queries are tagged with experiment and configuration info

---

## Configuration Temperature

Parameters are categorized by how quickly changes can take effect:

| Temperature | Parameters | Re-indexing? | Test Method |
|-------------|-----------|--------------|-------------|
| **Hot** | topK, fusionStrategy, rrfK, systemPrompt, reranking | No | Per-query override, instant |
| **Warm** | chunkStrategy, chunkSize, overlap | Preview only | Offline comparison (sample docs) |
| **Cold** | embeddingModel, vectorDimensions | Yes | Requires re-indexing |

### Hot Parameters

These can be changed per-query with immediate effect:

| Parameter | Description | Typical Range |
|-----------|-------------|---------------|
| `topK` | Number of chunks to retrieve | 3-20 |
| `fusionStrategy` | How to combine search results | rrf, weighted, vector_only, keyword_only |
| `rrfK` | RRF constant (with `rrf` fusion) | 1-1000 |
| `systemPrompt` | Instructions for the LLM | Custom text |
| `reranking` | Rerank candidates before top-k | none, cohere, llm |

An experiment group runs with **all** of its settings: topK is passed with each query, and the
others select an llm4s pipeline built for (and cached by) the group's settings, so a prompt or
fusion experiment really compares two pipelines. In permission mode llm4s search is vector-only,
so fusion, rrfK and reranking have no effect there
([llm4s/llm4s#2716](https://github.com/llm4s/llm4s/issues/2716)).

**Temperature is not testable on `/query` yet.** llm4s 0.6.0 `RAG.queryWithAnswer` answers at
temperature 0.0 ([llm4s/llm4s#2715](https://github.com/llm4s/llm4s/issues/2715), fixed upstream
for the next release). An `llmTemperature` in a group's config is stored but not applied, and the
logged config snapshot records no temperature.

### Warm Parameters

These affect how documents are chunked. Changes apply to new documents only:

| Parameter | Description | Typical Range |
|-----------|-------------|---------------|
| `chunkStrategy` | Chunking algorithm | simple, sentence, markdown, semantic |
| `chunkSize` | Target chunk size (chars) | 400-1200 |
| `overlap` | Overlap between chunks | 0-200 |

Use the [Chunking Preview API]({{ site.baseurl }}/guide/chunking/) to test warm parameter changes before applying.

### Cold Parameters

These require full re-indexing of your corpus:

| Parameter | Description |
|-----------|-------------|
| `embeddingModel` | Vector embedding model |
| `vectorDimensions` | Embedding vector size |

---

## Experiments API

### Create an Experiment

Create a draft experiment comparing baseline and variant configurations:

```bash
curl -X POST http://localhost:8080/api/v1/experiments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer your-token" \
  -d '{
    "name": "Test higher topK",
    "description": "Compare topK=5 vs topK=10 for retrieval quality",
    "baselineConfig": {
      "topK": 5,
      "fusionStrategy": "rrf"
    },
    "variantConfig": {
      "topK": 10,
      "fusionStrategy": "rrf"
    },
    "trafficSplit": 0.5
  }'
```

Response:

```json
{
  "success": true,
  "experiment": {
    "id": "exp-abc123",
    "name": "Test higher topK",
    "status": "draft",
    "trafficSplit": 0.5,
    "createdAt": "2024-01-15T10:00:00Z"
  }
}
```

### Configuration Snapshot Fields

| Field | Type | Description |
|-------|------|-------------|
| `topK` | int | Number of chunks to retrieve |
| `fusionStrategy` | string | Search fusion strategy |
| `rrfK` | int | RRF constant |
| `systemPrompt` | string | LLM system prompt |
| `reranking` | string | `none`, `cohere` (needs `COHERE_API_KEY`) or `llm` |
| `llmTemperature` | double | Stored, not applied to `/query` in llm4s 0.6.0 (see above) |

A setting a group leaves out is the runtime setting. Creating an experiment validates it (400
Bad Request otherwise): a name of 1-255 characters, `trafficSplit` between 0 and 1, and valid
values for each setting.

### Start an Experiment

Activate an experiment to begin traffic splitting:

```bash
curl -X PUT http://localhost:8080/api/v1/experiments/{id}/start \
  -H "Authorization: Bearer your-token"
```

**Important**: Only one experiment can be running at a time. Starting a new experiment will fail if another is already running.

Operations answer **404 Not Found** for an unknown experiment and **409 Conflict** when its status
does not allow them (starting one that is not a draft, stopping one that is not running,
archiving a running one, deleting one that is not a draft, or starting while another runs).

### Stop an Experiment

Stop traffic splitting and complete the experiment:

```bash
curl -X PUT http://localhost:8080/api/v1/experiments/{id}/stop \
  -H "Authorization: Bearer your-token"
```

### Archive an Experiment

Archive a completed experiment for historical reference:

```bash
curl -X PUT http://localhost:8080/api/v1/experiments/{id}/archive \
  -H "Authorization: Bearer your-token"
```

### Delete an Experiment

Delete a draft experiment (cannot delete started experiments):

```bash
curl -X DELETE http://localhost:8080/api/v1/experiments/{id} \
  -H "Authorization: Bearer your-token"
```

### List Experiments

Get all experiments with optional status filtering:

```bash
# All experiments
curl http://localhost:8080/api/v1/experiments \
  -H "Authorization: Bearer your-token"

# Filter by status
curl "http://localhost:8080/api/v1/experiments?status=completed" \
  -H "Authorization: Bearer your-token"
```

### Get Running Experiment

Get the currently running experiment (if any):

```bash
curl http://localhost:8080/api/v1/experiments/running \
  -H "Authorization: Bearer your-token"
```

---

## Traffic Routing

When an experiment is running, queries are automatically routed based on the traffic split:

- **trafficSplit: 0.5**: 50% baseline, 50% variant
- **trafficSplit: 0.2**: 80% baseline, 20% variant
- **trafficSplit: 0.9**: 10% baseline, 90% variant

The routing is deterministic per query - each query is assigned to either baseline or variant randomly based on the split percentage.

### Query Tracking

All queries during an experiment are tagged with:

| Field | Description |
|-------|-------------|
| `experimentId` | ID of the experiment |
| `variant` | "baseline" or "variant" |
| `configSnapshot` | Exact configuration used |
| `failed` | Whether the query failed (with `errorMessage`) |

This data is stored in `query_logs` (`experiment_id` and `variant` are separate columns; rows
from earlier versions, which stored `<experiment>:<group>` in `experiment_id`, are split at
startup) and used for results analysis. Failed queries are logged too, so each group's error
rate is part of the results; analytics summaries leave them out.

---

## Manual Overrides

You can also manually specify configuration overrides for testing without creating an experiment:

```bash
curl -X POST http://localhost:8080/api/v1/query \
  -H "Content-Type: application/json" \
  -d '{
    "question": "What is PostgreSQL?",
    "experimentId": "my-test-run",
    "overrides": {
      "topK": 10,
      "fusionStrategy": "weighted",
      "systemPrompt": "Answer in one sentence."
    }
  }'
```

Manual overrides are useful for:

- Quick one-off tests
- Debugging specific queries
- Exploring parameter effects

The overrides are applied (topK, fusionStrategy and systemPrompt; `llmTemperature` is not, see
above), and the query is logged with your custom `experimentId` and the configuration it ran with.
A running experiment's group config takes precedence over request overrides.

---

## Results Analysis

### Get Experiment Results

Retrieve metrics comparison and statistical analysis:

```bash
curl http://localhost:8080/api/v1/experiments/{id}/results \
  -H "Authorization: Bearer your-token"
```

Response:

```json
{
  "experimentId": "exp-abc123",
  "experimentName": "Test higher topK",
  "status": "completed",
  "duration": "3 days",
  "baseline": {
    "queryCount": 150, "failedCount": 3, "errorRate": 0.02,
    "avgLatencyMs": 320.5, "latencyStdDevMs": 80.1,
    "avgRating": 3.8, "ratingStdDev": 0.9, "ratedCount": 60, "positiveCount": 31,
    "avgChunksRetrieved": 5.0, "avgChunksUsed": 5.0
  },
  "variant": {
    "queryCount": 148, "failedCount": 2, "errorRate": 0.0135,
    "avgLatencyMs": 285.3, "latencyStdDevMs": 75.4,
    "avgRating": 4.1, "ratingStdDev": 0.8, "ratedCount": 58, "positiveCount": 41,
    "avgChunksRetrieved": 10.0, "avgChunksUsed": 10.0
  },
  "analysis": {
    "latencyChange": "-11.0%",
    "ratingChange": "+7.9%",
    "chunksUsedChange": "+100.0%",
    "errorRateChange": "-32.4%",
    "tests": [
      { "metric": "latencyMs", "test": "welch-t", "baseline": 320.5, "variant": 285.3,
        "statistic": -3.86, "pValue": 0.00014, "significant": true },
      { "metric": "rating", "test": "welch-t", "baseline": 3.8, "variant": 4.1,
        "statistic": 1.92, "pValue": 0.058, "significant": false },
      { "metric": "errorRate", "test": "two-proportion-z", "baseline": 0.02, "variant": 0.0135,
        "statistic": -0.43, "pValue": 0.66, "significant": false },
      { "metric": "positiveRate", "test": "two-proportion-z", "baseline": 0.517, "variant": 0.707,
        "statistic": 2.10, "pValue": 0.036, "significant": true }
    ]
  }
}
```

### Understanding Metrics

| Metric | Description | Good Direction |
|--------|-------------|----------------|
| `queryCount` | Queries routed to the group, failed ones included | - |
| `errorRate` | Share of the group's queries that failed | Lower is better |
| `avgLatencyMs` | Average response time of the successful queries | Lower is better |
| `avgRating` | Average user rating (1-5) | Higher is better |
| `positiveCount` | Ratings of 4 or 5 | Higher is better |
| `avgChunksRetrieved` | Chunks returned from search | Depends on use case |
| `avgChunksUsed` | Chunks sent to the LLM (llm4s sends every retrieved chunk) | Depends on use case |

### Significance Tests

`analysis.tests` holds a two-sample test for each metric that has enough data:

| Metric | Test | Needs |
|--------|------|-------|
| `latencyMs` | Welch's unequal-variances t-test | 2+ successful queries per group, some variance |
| `rating` | Welch's unequal-variances t-test | 2+ ratings per group, some variance |
| `errorRate` | Two-proportion z-test (pooled, no continuity correction) | a query per group, both outcomes seen |
| `positiveRate` | Two-proportion z-test of the share of 4-5 ratings | a rating per group, both outcomes seen |

`pValue` is the two-sided p-value: the probability of a difference at least this large if the
two configurations performed the same. `significant` is `pValue < 0.05`. A non-significant result
does not show the groups are the same - it may only mean there is not enough data yet. Checking
results repeatedly and stopping at the first significant one inflates false positives, so decide
the sample size up front.

Earlier versions returned a `statisticalSignificance` number that was not a p-value; it is gone.

---

## Experiment Lifecycle

```
draft → running → completed → archived
         ↓
       (stopped)
```

| Status | Description | Can Delete? |
|--------|-------------|-------------|
| `draft` | Created but not started | Yes |
| `running` | Actively splitting traffic | No |
| `completed` | Stopped, results available | No |
| `archived` | Historical record | No |

---

## Admin UI

Access the Experiments dashboard at `/admin/experiments`:

### Dashboard Features

- **Status tabs**: Filter by draft, running, completed, archived
- **Create dialog**: Wizard for creating new experiments
- **Quick actions**: Start, stop, archive buttons
- **Results view**: Metrics comparison and analysis
- **Status indicators**: Visual status badges

### Creating an Experiment

1. Click "New Experiment"
2. Enter name and description
3. Configure baseline parameters
4. Configure variant parameters (differences highlighted)
5. Set traffic split percentage
6. Click "Create"

### Viewing Results

1. Click "Results" on a completed experiment
2. Review metrics comparison table
3. Check statistical significance
4. View analysis and recommendation
5. Decide whether to apply variant configuration

---

## Best Practices

### 1. Test One Variable at a Time

Change only one parameter between baseline and variant:

```json
{
  "baselineConfig": { "topK": 5, "fusionStrategy": "rrf" },
  "variantConfig": { "topK": 10, "fusionStrategy": "rrf" }
}
```

This isolates the effect of each change.

### 2. Use Appropriate Traffic Splits

| Scenario | Recommended Split |
|----------|-------------------|
| Low-risk parameter tuning | 50/50 |
| Testing new approach | 20/80 (80% baseline) |
| Validating improvement | 30/70 |
| Gradual rollout | Start 10/90, increase over time |

### 3. Gather Sufficient Data

Aim for at least 100 queries per variant for meaningful statistics:

```bash
# Check query counts
curl http://localhost:8080/api/v1/experiments/{id}/results \
  | jq '{baseline: .baseline.queryCount, variant: .variant.queryCount}'
```

### 4. Encourage User Feedback

Results are more meaningful with user ratings. Enable feedback in your application:

```bash
# Submit feedback for a query
curl -X POST http://localhost:8080/api/v1/feedback \
  -H "Content-Type: application/json" \
  -d '{
    "queryId": "query-123",
    "rating": 4,
    "comment": "Good answer, but could include more examples"
  }'
```

### 5. Document Your Experiments

Use meaningful names and descriptions:

```json
{
  "name": "Increase topK for docs collection",
  "description": "Testing if retrieving more chunks improves answer quality for technical documentation queries. Previous experiment showed 3.5 avg rating with topK=5."
}
```

---

## Common Experiments

### Experiment 1: Optimize topK

Test different numbers of retrieved chunks:

```bash
curl -X POST http://localhost:8080/api/v1/experiments \
  -H "Content-Type: application/json" \
  -d '{
    "name": "topK optimization",
    "baselineConfig": { "topK": 5 },
    "variantConfig": { "topK": 8 },
    "trafficSplit": 0.5
  }'
```

### Experiment 2: Compare Fusion Strategies

Test RRF vs weighted fusion:

```bash
curl -X POST http://localhost:8080/api/v1/experiments \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Fusion strategy comparison",
    "baselineConfig": { "fusionStrategy": "rrf" },
    "variantConfig": { "fusionStrategy": "weighted" },
    "trafficSplit": 0.5
  }'
```

### Experiment 3: System Prompt Tuning

Test different LLM instructions:

```bash
curl -X POST http://localhost:8080/api/v1/experiments \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Concise vs detailed prompts",
    "baselineConfig": {
      "systemPrompt": "Answer the question based on the context provided."
    },
    "variantConfig": {
      "systemPrompt": "You are a helpful assistant. Answer the question based on the context provided. Be concise and cite specific sources when possible."
    },
    "trafficSplit": 0.5
  }'
```

### Experiment 4: Reranking

Test whether LLM reranking improves answers enough to pay for its latency:

```bash
curl -X POST http://localhost:8080/api/v1/experiments \
  -H "Content-Type: application/json" \
  -d '{
    "name": "LLM reranking",
    "baselineConfig": { "reranking": "none" },
    "variantConfig": { "reranking": "llm" },
    "trafficSplit": 0.5
  }'
```

(Temperature experiments wait for the llm4s release with llm4s/llm4s#2715.)

---

## Integration with Other Features

### With Analytics

Use Analytics to identify optimization opportunities, then create experiments to test improvements:

1. Check `/api/v1/analytics/queries/summary` for baseline metrics
2. Review `/api/v1/analytics/suggestions` for recommendations
3. Create experiment based on suggestions
4. After experiment, re-check analytics

### With Optimization Suggestions

The suggestion engine may recommend specific experiments:

```bash
# Get suggestions
curl http://localhost:8080/api/v1/analytics/suggestions

# If suggestion recommends increasing topK, create experiment:
curl -X POST http://localhost:8080/api/v1/experiments \
  -d '{
    "name": "Apply suggestion: increase topK",
    "description": "Testing suggestion from optimization engine",
    "baselineConfig": { "topK": 5 },
    "variantConfig": { "topK": 8 },
    "trafficSplit": 0.3
  }'
```

### With Runtime Configuration

After validating improvements via experiments, apply to runtime config:

```bash
# Experiment showed topK=10 is better
# Apply to runtime configuration
curl -X PUT http://localhost:8080/api/v1/config/runtime \
  -H "Content-Type: application/json" \
  -d '{"topK": 10}'
```

---

## Troubleshooting

### "Another experiment is already running"

Stop the current experiment before starting a new one:

```bash
# Check what's running
curl http://localhost:8080/api/v1/experiments/running

# Stop it
curl -X PUT http://localhost:8080/api/v1/experiments/{id}/stop
```

### No Significant Difference

If the tests are not significant (`pValue` of 0.05 or more):

1. Let the experiment run longer to gather more data
2. Increase traffic split to variant (if safe)
3. Consider that there may be no real difference

### No Rating Data

Without user feedback, analysis is limited to latency metrics. Enable feedback collection:

```bash
# After each query, prompt users and submit feedback
curl -X POST http://localhost:8080/api/v1/feedback \
  -d '{"queryId": "...", "rating": 4}'
```

### Results Not Updating

Results are calculated from `query_logs`. Check that:

1. Queries are being logged (check `/api/v1/analytics/queries`)
2. Queries have the experiment ID attached
3. The experiment status is correct

---
layout: page
title: Experiments API
parent: API Reference
nav_order: 8
---

# Experiments API
{: .no_toc }

A/B experiments: split `/query` traffic between a baseline and a variant configuration and
compare them with significance tests. See the [Experimentation guide](/guide/experimentation).
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

**Auth:** every endpoint is **Admin** only.

## Experiment Object

```json
{
  "id": "exp-123",
  "name": "Bigger topK",
  "description": "Does retrieving 8 chunks beat 5?",
  "status": "draft",
  "baselineConfig": { "topK": 5 },
  "variantConfig": { "topK": 8 },
  "trafficSplit": 0.5,
  "collection": null,
  "createdAt": "2026-01-15T10:30:00Z",
  "startedAt": null,
  "endedAt": null
}
```

`status` is `draft`, `running`, `completed` or `archived`. `trafficSplit` is the fraction of
queries routed to the variant (0 to 1). When `collection` is set, only queries made with
exactly that `collection` value take part.

A config (`baselineConfig` / `variantConfig`) may set:

| Field | Values |
|-------|--------|
| `topK` | 1 to 100 |
| `fusionStrategy` | `rrf`, `weighted`, `vector_only`, `keyword_only` |
| `rrfK` | 1 to 1000 |
| `systemPrompt` | string |
| `reranking` | `none`, `cohere`, `llm` |
| `llmTemperature` | 0 to 2 (validated and recorded, but not applied: llm4s 0.6.0 answers `/query` at temperature 0.0, [llm4s/llm4s#2715](https://github.com/llm4s/llm4s/issues/2715)) |

Unset fields fall back to the request's overrides and then the runtime settings.

## How Queries Are Routed

While an experiment is running, each `POST /api/v1/query` and `/api/v1/query/stream` (not chat)
is assigned at random to `baseline` or `variant` according to `trafficSplit`, runs with that
group's config, and is logged with `experimentId` and `variant`. A query may name an
experiment with `experimentId`; otherwise the running experiment applies. Only one experiment
can run at a time.

---

## Create Experiment {#create}

```
POST /api/v1/experiments
```

```json
{
  "name": "Bigger topK",
  "description": "Does retrieving 8 chunks beat 5?",
  "baselineConfig": { "topK": 5 },
  "variantConfig": { "topK": 8 },
  "trafficSplit": 0.5,
  "collection": "docs/**"
}
```

`name` and `variantConfig` are required. **201 Created**:

```json
{ "experiment": { ...experiment... }, "message": "Experiment 'Bigger topK' created successfully" }
```

`400` with every validation error when the request is invalid.

---

## List Experiments {#list}

```
GET /api/v1/experiments?status=running&limit=100&offset=0
```

Newest first. `status` filters by status (`400` for an unknown one); `limit` defaults to 100
(max 1000).

```json
{ "experiments": [ { ...experiment... } ], "total": 3 }
```

---

## Running Experiment {#running}

```
GET /api/v1/experiments/running
```

The running experiment, or `404` when none is running.

---

## Get Experiment {#get}

```
GET /api/v1/experiments/{id}
```

---

## Lifecycle {#lifecycle}

| Method | Path | Allowed from | Result |
|--------|------|--------------|--------|
| PUT | `/api/v1/experiments/{id}/start` | `draft` | `running` (`409` if another experiment is running) |
| PUT | `/api/v1/experiments/{id}/stop` | `running` | `completed` |
| PUT | `/api/v1/experiments/{id}/archive` | `draft`, `completed` | `archived` |
| DELETE | `/api/v1/experiments/{id}` | `draft` | deleted |

Each answers with:

```json
{ "success": true, "message": "Experiment 'Bigger topK' started successfully", "experiment": { ...experiment... } }
```

`404` for an unknown experiment; `409` (same shape, `success: false`) when the experiment is in
the wrong status or another one is already running.

---

## Results {#results}

```
GET /api/v1/experiments/{id}/results
```

```json
{
  "experimentId": "exp-123",
  "experimentName": "Bigger topK",
  "status": "completed",
  "duration": "2d 4h",
  "baseline": {
    "queryCount": 520,
    "avgLatencyMs": 1400.2,
    "avgRating": 3.9,
    "ratedCount": 80,
    "avgChunksUsed": 4.8,
    "avgChunksRetrieved": 5.0,
    "failedCount": 2,
    "errorRate": 0.004,
    "latencyStdDevMs": 310.5,
    "ratingStdDev": 0.9,
    "positiveCount": 60
  },
  "variant": { "...": "same fields" },
  "analysis": {
    "latencyChange": "+8.1%",
    "ratingChange": "+5.3%",
    "chunksUsedChange": "+60.0%",
    "errorRateChange": "0.0%",
    "tests": [
      {
        "metric": "latencyMs",
        "test": "welch-t",
        "baseline": 1400.2,
        "variant": 1513.6,
        "statistic": 2.41,
        "pValue": 0.016,
        "significant": true
      }
    ]
  }
}
```

`tests` holds the significance tests that had enough data: Welch's t-test for `latencyMs` and
`rating`, and two-proportion z-tests for `errorRate` and `positiveRate` (ratings of 4 or 5).
`significant` means `pValue < 0.05`.

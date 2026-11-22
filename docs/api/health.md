---
layout: page
title: Health & Metrics API
parent: API Reference
nav_order: 15
---

# Health & Metrics API
{: .no_toc }

Health checks for load balancers and Kubernetes, and the Prometheus metrics endpoint. These
paths are served at the server root, not under `/api/v1`, and are exempt from rate limiting.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Health Check {#health}

```
GET /health
```

**Auth:** Public.

```json
{
  "status": "healthy",
  "version": "0.1.0",
  "uptime": 123456,
  "system": {
    "memoryUsedMb": 180,
    "memoryMaxMb": 4096,
    "memoryFreePercent": 95,
    "cpuCount": 8,
    "javaVersion": "21.0.4"
  }
}
```

`uptime` is in milliseconds. This endpoint does not check dependencies; use
[`/health/ready`](#ready) for that.

---

## Readiness Check {#ready}

```
GET /health/ready
```

**Auth:** Public. Answers `200` when ready, `503 Service Unavailable` otherwise.

The service is ready when the database answers a query **and** the llm4s RAG pipeline could be
built (`database` and `rag_instance` are `ok`). `api_keys` is informational: `warning` when no
provider API key is configured.

```json
{
  "ready": true,
  "checks": {
    "database": { "status": "ok", "message": null },
    "rag_instance": { "status": "ok", "message": null },
    "api_keys": { "status": "ok", "message": null }
  }
}
```

Not ready (`503`):

```json
{
  "ready": false,
  "checks": {
    "database": { "status": "error", "message": "Database unreachable: Connection refused" },
    "rag_instance": { "status": "ok", "message": null },
    "api_keys": { "status": "warning", "message": "No API keys configured" }
  }
}
```

A failed RAG pipeline build is retried on the next readiness check.

---

## Liveness Check {#live}

```
GET /health/live
```

**Auth:** Public.

```json
{ "status": "ok" }
```

---

## Prometheus Metrics {#metrics}

```
GET /metrics
```

Only served when `METRICS_ENABLED=true` (default `false`; `404` otherwise).

**Auth:** Admin by default (`METRICS_REQUIRE_AUTH=true`): scrape with
`Authorization: Bearer <API_KEY>` or an access token with the `admin` scope. With
`METRICS_REQUIRE_AUTH=false` it is public, e.g. when only reachable on an internal network.

The response is Prometheus text format (`text/plain`). Metrics include:

| Metric | Description |
|--------|-------------|
| `ragbox_uptime_seconds` | Time since start |
| `ragbox_info{version}` | Build info |
| `ragbox_documents_total`, `ragbox_chunks_total`, `ragbox_vectors_total`, `ragbox_collections_total` | Index size |
| `ragbox_collection_documents{collection}`, `ragbox_collection_chunks{collection}` | Per collection |
| `ragbox_stats_available` | `0` when statistics could not be read |
| `ragbox_healthy`, `ragbox_ready` | Health and readiness (1/0) |
| `ragbox_component_status{component}` | Readiness check results (1 = ok) |
| `ragbox_jvm_memory_used_bytes{area}`, `ragbox_jvm_memory_max_bytes{area}`, `ragbox_jvm_memory_committed_bytes{area}` | JVM memory |
| `ragbox_jvm_threads_current`, `ragbox_jvm_threads_daemon`, `ragbox_jvm_threads_peak` | JVM threads |

Example scrape config:

```yaml
scrape_configs:
  - job_name: ragbox
    metrics_path: /metrics
    authorization:
      type: Bearer
      credentials_file: /etc/prometheus/ragbox-api-key
    static_configs:
      - targets: ["ragbox:8080"]
```

---

## Kubernetes Probes

```yaml
livenessProbe:
  httpGet:
    path: /health/live
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /health/ready
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
```

The Helm chart in `deploy/helm` configures these for you (see
[Helm Deployment](/guide/helm-deployment)).

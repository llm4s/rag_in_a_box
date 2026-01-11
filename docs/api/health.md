---
layout: page
title: Health API
parent: API Reference
nav_order: 6
---

# Health API

Endpoints for health and readiness checks.
{: .fs-6 .fw-300 }

## Health Check {#health}

Basic health check endpoint.

```
GET /health
```

### Example Response

```json
{
  "status": "ok",
  "timestamp": "2024-01-15T10:30:00Z",
  "components": {
    "database": "ok",
    "embedding": "ok"
  }
}
```

---

## Readiness Check {#ready}

Check if the application is ready to accept requests. Used by Kubernetes readiness probes.

```
GET /health/ready
```

### Example Response (Ready)

```json
{
  "ready": true,
  "checks": {
    "database": true,
    "embedding": true
  }
}
```

### Example Response (Not Ready)

**Status: 503 Service Unavailable**

```json
{
  "ready": false,
  "checks": {
    "database": true,
    "embedding": false
  },
  "message": "Embedding service not available"
}
```

---

## Liveness Check {#live}

Simple liveness check for Kubernetes liveness probes.

```
GET /health/live
```

### Example Response

```json
{
  "status": "ok"
}
```

---

## Kubernetes Configuration

Configure probes in your deployment:

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

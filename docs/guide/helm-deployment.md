---
layout: page
title: Helm Deployment
parent: User Guide
nav_order: 10
---

# Helm Deployment Guide

Deploy RAG in a Box to Kubernetes using the official Helm chart.

## Prerequisites

- Kubernetes 1.23+
- Helm 3.8+
- kubectl configured to access your cluster
- PV provisioner support (for PostgreSQL persistence)

## Quick Start

### 1. Add the Helm repository

```bash
helm repo add ragbox https://llm4s.github.io/rag-in-a-box
helm repo update
```

### 2. Create a values file

```bash
cat > my-values.yaml <<EOF
secrets:
  database:
    password: "your-secure-db-password"
  security:
    jwtSecret: "<output of: openssl rand -base64 32>"     # placeholders are rejected
    adminPassword: "<a password of 12+ characters>"
  apiKeys:
    openai: "sk-your-openai-api-key"
EOF
```

### 3. Install the chart

```bash
helm install ragbox ragbox/ragbox -f my-values.yaml
```

### 4. Access the application

```bash
kubectl port-forward svc/ragbox 8080:80
```

Visit [http://localhost:8080](http://localhost:8080) to access the application.

## Configuration

### Authentication Mode

RAG in a Box supports three authentication modes:

```yaml
config:
  auth:
    mode: "basic"  # Options: open, basic, oauth
```

### OAuth Configuration

```yaml
config:
  auth:
    mode: oauth
    oauth:
      provider: keycloak            # google, azure, okta, keycloak, custom
      clientId: ragbox
      redirectUri: https://ragbox.example.com/api/v1/oauth/callback
      keycloakBaseUrl: https://sso.example.com
      keycloakRealm: corp
      scopes: "openid profile email groups"
      adminGroups: ragbox-admins
      sessionStorage: postgres      # needed with more than one replica
secrets:
  oauth:
    clientSecret: "..."
```

With `secrets.existingSecret`, put the client secret under the key `oauth-client-secret` (and an
optional static API key under `api-key`).

### Reverse proxies, rate limiting and CORS

The app believes `X-Forwarded-For` only from `config.rateLimit.trustedProxies`. The default
trusts the private address ranges, where an in-cluster ingress controller runs; narrow it to your
ingress controller's or load balancer's range where you can. Failed logins are throttled per
client IP and per username (`loginMaxAttempts` per `loginWindowSeconds`).

Cross-origin browser access is off by default (the admin UI is same-origin); list origins in
`config.cors.allowedOrigins` if a browser app on another origin calls the API.

### Metrics

`/metrics` is for administrators only (`config.metrics.requireAuth: true`). The
`prometheus.io/*` pod annotations cannot send credentials: either scrape with a bearer
credential (the API key or an admin-scoped `rat_` token, e.g. in a ServiceMonitor's
`authorization`), or set `requireAuth: false` and keep `/metrics` off the public ingress.

### Using an External Database

To connect to an existing PostgreSQL instance:

```yaml
postgresql:
  enabled: false

externalDatabase:
  host: "your-postgres-host"
  port: 5432
  database: "ragdb"
  existingSecret: "your-db-secret"
  userKey: "username"
  passwordKey: "password"
```

### Ingress Configuration

Enable ingress with TLS:

```yaml
ingress:
  enabled: true
  className: nginx
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
  hosts:
    - host: ragbox.example.com
      paths:
        - path: /
          pathType: Prefix
  tls:
    - secretName: ragbox-tls
      hosts:
        - ragbox.example.com
```

### Resource Limits

Configure resource requests and limits:

```yaml
resources:
  requests:
    cpu: 250m
    memory: 512Mi
  limits:
    cpu: 2000m
    memory: 2Gi
```

### Autoscaling

Enable horizontal pod autoscaling:

```yaml
autoscaling:
  enabled: true
  minReplicas: 2
  maxReplicas: 10
  targetCPUUtilizationPercentage: 80
```

## Full Values Reference

| Parameter | Description | Default |
|-----------|-------------|---------|
| `replicaCount` | Number of API replicas | `2` |
| `image.repository` | Container image | `ghcr.io/llm4s/rag_in_a_box` |
| `image.tag` | Image tag | `Chart.AppVersion` |
| `config.auth.mode` | Auth mode (open/basic/oauth) | `basic` |
| `config.llm.model` | LLM model | `openai/gpt-4o` |
| `config.embedding.provider` | Embedding provider | `openai` |
| `config.embedding.model` | Embedding model | `text-embedding-3-small` |
| `config.rag.chunkingStrategy` | Chunking strategy | `sentence` |
| `config.rag.topK` | Chunks to retrieve | `5` |
| `postgresql.enabled` | Deploy bundled PostgreSQL + pgvector StatefulSet | `true` |
| `postgresql.persistence.size` | PVC size | `10Gi` |
| `ingress.enabled` | Enable ingress | `false` |
| `secrets.security.adminPassword` | Admin password (basic auth, 12+ characters) | generated if empty |
| `secrets.security.apiKey` | Static administrator API key | none |
| `secrets.oauth.clientSecret` | OAuth client secret (oauth mode) | none |
| `config.auth.oauth.*` | OAuth provider, client ID, redirect URI, scopes, admin groups, ... | see values.yaml |
| `config.cors.allowedOrigins` | Origins allowed to call the API from a browser (`*` for any) | same-origin only |
| `config.rateLimit.trustedProxies` | Proxies whose `X-Forwarded-For` is believed | private ranges |
| `config.rateLimit.loginMaxAttempts` | Failed logins per IP and per username per window | `10` |
| `config.metrics.requireAuth` | `/metrics` for administrators only | `true` |

See the [values.yaml](https://github.com/llm4s/rag-in-a-box/blob/main/deploy/helm/ragbox/values.yaml) for all configuration options.

## Upgrading

```bash
helm upgrade ragbox ragbox/ragbox -f my-values.yaml
```

## Uninstalling

```bash
helm uninstall ragbox
```

**Note**: PVCs are not deleted automatically. To fully clean up:

```bash
kubectl delete pvc -l app.kubernetes.io/instance=ragbox
```

## Troubleshooting

### Pod not starting

Check the pod logs:

```bash
kubectl logs -l app.kubernetes.io/name=ragbox
```

### Database connection issues

Verify the PostgreSQL service is running:

```bash
kubectl get pods -l app.kubernetes.io/component=postgresql
```

### Health check failures

The API has three health endpoints:

- `/health/live` - Liveness probe
- `/health/ready` - Readiness probe
- `/health` - General health status

Check them directly:

```bash
kubectl exec -it deployment/ragbox -- curl localhost:8080/health/ready
```

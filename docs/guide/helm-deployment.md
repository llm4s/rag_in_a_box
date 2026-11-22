---
layout: page
title: Helm Deployment
parent: Operations
nav_order: 2
---

# Helm Deployment Guide

Deploy RAG in a Box to Kubernetes using the official Helm chart.

## Prerequisites

- Kubernetes 1.23+
- Helm 3.8+
- kubectl configured to access your cluster
- PV provisioner support (for PostgreSQL persistence)

## Quick Start

### 1. Get the chart

The chart is not published to a Helm repository yet; install it from the repository checkout:

```bash
git clone https://github.com/llm4s/rag_in_a_box.git
cd rag_in_a_box
```

### 2. Create a values file

`jwtSecret`, `adminPassword` and the database password may be left empty: they are then generated
on install and kept across upgrades.

```bash
cat > my-values.yaml <<EOF
postgresql:
  auth:
    password: "your-secure-db-password"
secrets:
  security:
    jwtSecret: "<output of: openssl rand -base64 32>"     # placeholders are rejected
    adminPassword: "<a password of 12+ characters>"
  apiKeys:
    openai: "sk-your-openai-api-key"
EOF
```

### 3. Install the chart

```bash
helm install ragbox ./deploy/helm/ragbox -f my-values.yaml
```

### 4. Access the application

```bash
kubectl port-forward svc/ragbox 8080:80
```

The admin UI is at [http://localhost:8080/admin](http://localhost:8080/admin) (the image
includes the built UI); the API is under `/api/v1`.

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
  existingSecret: "your-db-secret"   # or set username / password directly
  userKey: "pg-user"                  # keys in that secret (these are the defaults)
  passwordKey: "pg-password"
```

The database needs the `vector` extension (pgvector). The server applies its schema migrations
(Flyway) at startup; see [Configuration](configuration.md#schema-migrations) and
[Schema migrations](#schema-migrations) below.

### Schema migrations

By default every server pod applies pending migrations when it starts
(`database.migrateOnStart: true`, the `DB_MIGRATE_ON_START` environment variable); replicas
starting together are serialised by Flyway's lock, and once the schema is current this is a
no-op.

To migrate once, before the new pods roll out, enable the migration job:

```yaml
migrations:
  job:
    enabled: true
database:
  migrateOnStart: false   # optional: the servers then expect the schema to be up to date
```

The job is a Helm hook that runs the release's image with `--migrate-only` (apply the migrations,
then exit) using the same database settings and credential secrets as the deployment. A failed
job fails the `helm upgrade` before any pod is replaced, and is kept for its logs
(`kubectl logs job/<release>-ragbox-migrate`); a successful one is deleted. `backoffLimit`,
`activeDeadlineSeconds`, `resources` and `annotations` are under `migrations.job`.

Helm hooks run before the release's own resources are created, so when the job runs depends on
where the database and its credentials come from:

| Database | The job runs | First install |
|----------|--------------|---------------|
| Bundled (`postgresql.enabled: true`) | before each upgrade (`pre-upgrade`) | the servers migrate: keep `database.migrateOnStart: true` for the install, then set it to `false` |
| External, credentials in the chart's secret (`externalDatabase.password`) | before each upgrade (`pre-upgrade`) | as above |
| External, credentials in `externalDatabase.existingSecret` | before the install and each upgrade (`pre-install,pre-upgrade`) | the job migrates; `database.migrateOnStart: false` is safe from the start |

`helm install` prints a warning when `database.migrateOnStart` is `false` and nothing would
migrate before the first install. Leaving `migrateOnStart: true` together with the job is also
fine: the job does the work and the servers find nothing to apply.

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
| `secrets.security.jwtSecret` | JWT signing secret (32+ characters) | generated if empty |
| `secrets.security.adminPassword` | Admin password (basic auth, 12+ characters) | generated if empty |
| `postgresql.auth.password` | Bundled PostgreSQL password | generated if empty |
| `secrets.security.apiKey` | Static administrator API key | none |
| `secrets.oauth.clientSecret` | OAuth client secret (oauth mode) | none |
| `config.auth.oauth.*` | OAuth provider, client ID, redirect URI, scopes, admin groups, ... | see values.yaml |
| `config.cors.allowedOrigins` | Origins allowed to call the API from a browser (`*` for any) | same-origin only |
| `config.rateLimit.trustedProxies` | Proxies whose `X-Forwarded-For` is believed | private ranges |
| `config.rateLimit.loginMaxAttempts` | Failed logins per IP and per username per window | `10` |
| `config.metrics.enabled` | Enable `/metrics` | `true` |
| `config.metrics.requireAuth` | `/metrics` for administrators only | `true` |
| `config.logging.format` | `json` or `text` (`LOG_FORMAT`) | `json` |
| `database.migrateOnStart` | Servers apply pending migrations at startup (`DB_MIGRATE_ON_START`) | `true` |
| `migrations.job.enabled` | Run the migrations in a pre-upgrade (and, with an external database's existing secret, pre-install) Job | `false` |

See the [values.yaml](https://github.com/llm4s/rag_in_a_box/blob/main/deploy/helm/ragbox/values.yaml) for all configuration options.

## Upgrading

```bash
helm upgrade ragbox ./deploy/helm/ragbox -f my-values.yaml
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

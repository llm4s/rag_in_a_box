# RAG in a Box Helm Chart

A Helm chart for deploying [RAG in a Box](https://github.com/llm4s/rag-in-a-box) - a turnkey RAG solution powered by LLM4S.

## Prerequisites

- Kubernetes 1.23+
- Helm 3.8+
- PV provisioner support (for PostgreSQL persistence)

## Installation

### Add the repository

```bash
helm repo add ragbox https://llm4s.github.io/rag-in-a-box
helm repo update
```

### Install the chart

```bash
# Create a values file with your configuration.
# jwtSecret, adminPassword and the database password are optional: if left
# empty they are generated randomly on install and preserved on upgrade.
cat > my-values.yaml <<EOF
# Database password (used by bundled PostgreSQL)
postgresql:
  auth:
    password: "your-secure-db-password"

# Security and API keys
secrets:
  security:
    jwtSecret: "your-32-character-jwt-secret-key"
    adminPassword: "your-admin-password"
  apiKeys:
    openai: "sk-your-openai-api-key"
EOF

# Install the chart
helm install ragbox ragbox/ragbox -f my-values.yaml
```

### Quick start (development only)

```bash
helm install ragbox ragbox/ragbox \
  --set postgresql.auth.password=devpassword \
  --set secrets.security.jwtSecret=dev-jwt-secret-32-characters!! \
  --set secrets.security.adminPassword=admin123 \
  --set secrets.apiKeys.openai=sk-your-key
```

## Configuration

See [values.yaml](values.yaml) for the full list of configurable parameters.

### Key Configuration Options

| Parameter | Description | Default |
|-----------|-------------|---------|
| `replicaCount` | Number of API replicas | `2` |
| `image.repository` | Container image | `ghcr.io/llm4s/rag_in_a_box` |
| `image.tag` | Image tag | `Chart.AppVersion` (a published commit-SHA tag) |
| `config.auth.mode` | Authentication mode (open/basic/oauth) | `basic` |
| `config.llm.model` | LLM model for answer generation | `openai/gpt-4o` |
| `config.embedding.provider` | Embedding provider | `openai` |
| `postgresql.enabled` | Deploy bundled PostgreSQL + pgvector StatefulSet | `true` |
| `secrets.security.jwtSecret` | JWT signing secret | generated if empty |
| `secrets.security.adminPassword` | Admin password (basic auth, 12+ characters) | generated if empty |
| `secrets.security.apiKey` | Static administrator API key | none |
| `secrets.oauth.clientSecret` | OAuth client secret (oauth mode) | none |
| `config.auth.oauth.*` | OAuth provider, client ID, redirect URI, scopes, admin groups, ... | see values.yaml |
| `config.cors.allowedOrigins` | Origins allowed to call the API from a browser (`*` for any) | same-origin only |
| `config.rateLimit.trustedProxies` | Proxies whose `X-Forwarded-For` is believed | private ranges |
| `config.rateLimit.loginMaxAttempts` | Failed logins per IP and per username per window | `10` |
| `config.metrics.requireAuth` | `/metrics` for administrators only | `true` |
| `podSecurityContext` / `securityContext` | Non-root UID 10001, read-only root FS, no privilege escalation, all capabilities dropped | secure defaults |

### Generated secrets

When `secrets.security.jwtSecret`, `secrets.security.adminPassword` or
`postgresql.auth.password` are empty, the chart generates random values on the
first `helm install` and reads them back from the cluster (`lookup`) on
`helm upgrade`, so they stay stable. `helm template` and client-side dry runs
cannot query the cluster and will render fresh random values each time, so set
these values explicitly (or use `existingSecret`) when rendering manifests for
GitOps tools such as Argo CD.

### Bundled PostgreSQL

The bundled database is a single-replica StatefulSet running the official
`pgvector/pgvector` image (PostgreSQL with the pgvector extension), with a
PersistentVolumeClaim from `volumeClaimTemplates`. It is intended for
evaluation and small deployments; for production use a managed PostgreSQL with
pgvector and set `postgresql.enabled=false`.
| `ingress.enabled` | Enable ingress | `false` |

### Using External PostgreSQL

To use an external PostgreSQL instance:

```yaml
postgresql:
  enabled: false

externalDatabase:
  host: "your-postgres-host"
  port: 5432
  username: "rag"
  password: "your-password"
  database: "ragdb"
```

Or with an existing secret:

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

## Upgrading

```bash
helm upgrade ragbox ragbox/ragbox -f my-values.yaml
```

## Uninstalling

```bash
helm uninstall ragbox
```

**Note**: This will not delete PVCs. To fully clean up:

```bash
kubectl delete pvc -l app.kubernetes.io/instance=ragbox
```

## Development

### Testing the chart locally

```bash
# Lint the chart
helm lint deploy/helm/ragbox

# Dry run installation
helm install ragbox deploy/helm/ragbox --dry-run --debug

# Template rendering
helm template ragbox deploy/helm/ragbox
```

## License

MIT License - see [LICENSE](../../../LICENSE) for details.

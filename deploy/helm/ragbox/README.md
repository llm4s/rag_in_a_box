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
# Create a values file with your configuration
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
| `image.repository` | Container image | `ghcr.io/llm4s/rag-in-a-box` |
| `image.tag` | Image tag | `Chart.AppVersion` |
| `config.auth.mode` | Authentication mode (open/basic/oauth) | `basic` |
| `config.llm.model` | LLM model for answer generation | `openai/gpt-4o` |
| `config.embedding.provider` | Embedding provider | `openai` |
| `postgresql.enabled` | Deploy bundled PostgreSQL | `true` |
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

For OAuth authentication:

```yaml
config:
  auth:
    mode: oauth

# Add OAuth-specific environment variables via extraEnv
# See the documentation for full OAuth setup
```

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

### Updating dependencies

```bash
cd deploy/helm/ragbox
helm dependency update
```

## License

MIT License - see [LICENSE](../../../LICENSE) for details.

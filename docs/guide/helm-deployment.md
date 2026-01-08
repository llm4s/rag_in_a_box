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
    jwtSecret: "your-32-character-jwt-secret-key"
    adminPassword: "your-admin-password"
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
| `image.repository` | Container image | `ghcr.io/llm4s/rag-in-a-box` |
| `image.tag` | Image tag | `Chart.AppVersion` |
| `config.auth.mode` | Auth mode (open/basic/oauth) | `basic` |
| `config.llm.model` | LLM model | `openai/gpt-4o` |
| `config.embedding.provider` | Embedding provider | `openai` |
| `config.embedding.model` | Embedding model | `text-embedding-3-small` |
| `config.rag.chunkingStrategy` | Chunking strategy | `sentence` |
| `config.rag.topK` | Chunks to retrieve | `5` |
| `postgresql.enabled` | Deploy bundled PostgreSQL | `true` |
| `postgresql.primary.persistence.size` | PVC size | `10Gi` |
| `ingress.enabled` | Enable ingress | `false` |

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
kubectl get pods -l app.kubernetes.io/name=postgresql
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

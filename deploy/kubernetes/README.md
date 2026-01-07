# Kubernetes Deployment

This directory contains Kubernetes manifests for deploying RAG in a Box.

## Prerequisites

- Kubernetes 1.24+
- kubectl configured
- Ingress controller (nginx, traefik, or cloud-provided)
- Storage class for persistent volumes

## Quick Start

```bash
# 1. Create namespace
kubectl apply -f namespace.yaml

# 2. Create secrets (edit values first or use kubectl create secret)
kubectl create secret generic ragbox-secrets \
  --namespace ragbox \
  --from-literal=pg-user="rag" \
  --from-literal=pg-password="$(openssl rand -base64 24)" \
  --from-literal=jwt-secret="$(openssl rand -base64 32)" \
  --from-literal=admin-password="$(openssl rand -base64 16)" \
  --from-literal=openai-api-key="sk-..."

# 3. Apply config and deployments
kubectl apply -f configmap.yaml
kubectl apply -f postgres.yaml
kubectl apply -f api.yaml

# 4. Wait for pods to be ready
kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=ragbox -n ragbox --timeout=300s

# 5. Configure ingress (edit ingress.yaml first)
kubectl apply -f ingress.yaml
```

## Files

| File | Description |
|------|-------------|
| `namespace.yaml` | Creates the ragbox namespace |
| `configmap.yaml` | Non-sensitive configuration |
| `secret.yaml` | Template for secrets (don't commit with real values) |
| `postgres.yaml` | PostgreSQL deployment with pgvector |
| `api.yaml` | API deployment, service, and PDB |
| `ingress.yaml` | Ingress templates (nginx, traefik, ALB) |

## Configuration

### Secrets

Never commit secrets to version control. Create them using:

```bash
kubectl create secret generic ragbox-secrets \
  --namespace ragbox \
  --from-literal=pg-user="rag" \
  --from-literal=pg-password="your-db-password" \
  --from-literal=jwt-secret="your-32-char-jwt-secret" \
  --from-literal=admin-password="your-admin-password" \
  --from-literal=openai-api-key="sk-..."
```

Or use external secrets operator, Vault, or sealed-secrets.

### ConfigMap

Edit `configmap.yaml` to customize:
- LLM model and temperature
- Embedding provider and model
- RAG chunking settings
- Rate limiting configuration

### Scaling

Adjust replicas in `api.yaml`:

```yaml
spec:
  replicas: 3  # Increase for higher throughput
```

### Resources

Default resource limits:
- API: 512Mi-2Gi memory, 250m-2000m CPU
- PostgreSQL: 1Gi-4Gi memory, 500m-2000m CPU

Adjust based on your workload.

## Monitoring

### Prometheus

The API pods are annotated for Prometheus scraping:

```yaml
annotations:
  prometheus.io/scrape: "true"
  prometheus.io/port: "8080"
  prometheus.io/path: "/metrics"
```

### Health Checks

- Liveness: `/health/live`
- Readiness: `/health/ready`

## Troubleshooting

```bash
# Check pod status
kubectl get pods -n ragbox

# View logs
kubectl logs -f deployment/ragbox-api -n ragbox

# Check events
kubectl get events -n ragbox --sort-by='.lastTimestamp'

# Describe pods for issues
kubectl describe pod -l app.kubernetes.io/component=api -n ragbox

# Test connectivity
kubectl run test --rm -it --image=curlimages/curl -n ragbox -- \
  curl http://ragbox-api/health
```

## Production Checklist

- [ ] Use managed PostgreSQL (RDS, Cloud SQL, Azure Database)
- [ ] Configure proper storage class for PostgreSQL PVC
- [ ] Set up TLS termination via ingress
- [ ] Configure backup strategy for PostgreSQL
- [ ] Set up monitoring and alerting
- [ ] Use external secrets management
- [ ] Configure network policies if needed
- [ ] Set appropriate resource limits
- [ ] Configure horizontal pod autoscaler (HPA)

## Horizontal Pod Autoscaler (Optional)

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: ragbox-api-hpa
  namespace: ragbox
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: ragbox-api
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
```

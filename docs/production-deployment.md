# Production Deployment Guide

This guide covers deploying RAG in a Box to production environments using Docker Compose or Kubernetes.

## Prerequisites

- Docker 20.10+ and Docker Compose 2.0+
- PostgreSQL 15+ with pgvector extension
- API keys for your chosen embedding/LLM providers
- At least 2GB RAM, 2 CPU cores recommended

## Quick Start (Docker Compose)

```bash
# Clone the repository
git clone https://github.com/llm4s/rag-in-a-box.git
cd rag-in-a-box

# Create environment file
cp .env.example .env

# Edit .env with your API keys and settings
# At minimum, set OPENAI_API_KEY or your preferred provider

# Start services
docker-compose up -d

# Check health
curl http://localhost:8080/health
```

## Environment Variables Reference

### Required

| Variable | Description | Example |
|----------|-------------|---------|
| `OPENAI_API_KEY` | OpenAI API key (if using OpenAI) | `sk-...` |
| `ANTHROPIC_API_KEY` | Anthropic API key (if using Claude) | `sk-ant-...` |
| `JWT_SECRET` | Secret for JWT token signing (32+ chars) | `your-secure-random-secret` |

### Database

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | - | Full connection string (overrides individual settings) |
| `PG_HOST` | `localhost` | PostgreSQL hostname |
| `PG_PORT` | `5432` | PostgreSQL port |
| `PG_DATABASE` | `ragdb` | Database name |
| `PG_USER` | `rag` | Database username |
| `PG_PASSWORD` | `rag` | Database password |
| `PG_TABLE_NAME` | `rag_embeddings` | Vector table name |

### Server

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_HOST` | `0.0.0.0` | Bind address |
| `SERVER_PORT` | `8080` | HTTP port |

### LLM & Embedding

| Variable | Default | Description |
|----------|---------|-------------|
| `LLM_MODEL` | `openai/gpt-4o` | LLM model (format: provider/model) |
| `LLM_TEMPERATURE` | `0.1` | Generation temperature (0.0-1.0) |
| `EMBEDDING_PROVIDER` | `openai` | Embedding provider (openai, voyage, ollama) |
| `EMBEDDING_MODEL` | `text-embedding-3-small` | Embedding model name |

### RAG Settings

| Variable | Default | Description |
|----------|---------|-------------|
| `RAG_CHUNKING_STRATEGY` | `sentence` | Chunking strategy (simple, sentence, markdown, semantic) |
| `RAG_CHUNK_SIZE` | `800` | Target chunk size in characters |
| `RAG_CHUNK_OVERLAP` | `150` | Overlap between chunks |
| `RAG_TOP_K` | `5` | Number of context chunks to retrieve |
| `RAG_FUSION_STRATEGY` | `rrf` | Search fusion (rrf, weighted, vector_only, keyword_only) |

### Security

| Variable | Default | Description |
|----------|---------|-------------|
| `AUTH_MODE` | `open` | Authentication mode (open, basic) |
| `ADMIN_USERNAME` | `admin` | Admin username (when AUTH_MODE=basic) |
| `ADMIN_PASSWORD` | - | Admin password (required when AUTH_MODE=basic) |
| `JWT_SECRET` | - | JWT signing secret (required for multi-instance) |
| `JWT_EXPIRATION` | `86400` | JWT expiration in seconds |
| `API_KEY` | - | Legacy API key authentication |
| `ALLOWED_ORIGINS` | `*` | CORS allowed origins (comma-separated) |

### Production Hardening

| Variable | Default | Description |
|----------|---------|-------------|
| `RATE_LIMIT_ENABLED` | `false` | Enable rate limiting |
| `RATE_LIMIT_MAX_REQUESTS` | `100` | Max requests per window |
| `RATE_LIMIT_WINDOW_SECONDS` | `60` | Rate limit window duration |
| `REQUEST_SIZE_ENABLED` | `true` | Enable request size limits |
| `MAX_BODY_SIZE_MB` | `10` | Maximum request body size |
| `SHUTDOWN_TIMEOUT_SECONDS` | `30` | Graceful shutdown timeout |
| `SHUTDOWN_DRAIN_SECONDS` | `5` | Connection drain time |

### Observability

| Variable | Default | Description |
|----------|---------|-------------|
| `METRICS_ENABLED` | `false` | Enable Prometheus metrics at /metrics |
| `LOG_LEVEL` | `INFO` | Log level (DEBUG, INFO, WARN, ERROR) |
| `LOG_FORMAT` | `console` | Log format (console, json) |

## Production Configuration

### Security Checklist

- [ ] Set `JWT_SECRET` to a strong random value (32+ characters)
- [ ] Enable `AUTH_MODE=basic` with strong `ADMIN_PASSWORD`
- [ ] Configure `ALLOWED_ORIGINS` to restrict CORS
- [ ] Enable `RATE_LIMIT_ENABLED=true`
- [ ] Use TLS termination (nginx, traefik, or cloud load balancer)
- [ ] Restrict database access to application only
- [ ] Use secrets management (Vault, AWS Secrets Manager, etc.)

### Resource Requirements

| Component | CPU | Memory | Storage |
|-----------|-----|--------|---------|
| API Service | 1-2 cores | 1-2 GB | 100 MB |
| PostgreSQL | 1-2 cores | 2-4 GB | 10+ GB |

Scale based on:
- Document count and chunk size
- Query throughput requirements
- Embedding dimensions (1536 for OpenAI, 1024 for Voyage)

### Database Tuning

For production PostgreSQL with pgvector:

```sql
-- Recommended settings for vector search
ALTER SYSTEM SET shared_buffers = '2GB';
ALTER SYSTEM SET effective_cache_size = '6GB';
ALTER SYSTEM SET maintenance_work_mem = '512MB';
ALTER SYSTEM SET work_mem = '256MB';

-- HNSW index settings (adjust based on data size)
ALTER SYSTEM SET max_parallel_workers_per_gather = 4;
```

Create optimized indexes:

```sql
-- HNSW index for faster vector search
CREATE INDEX IF NOT EXISTS idx_embeddings_hnsw
ON rag_embeddings
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

-- B-tree indexes for filtering
CREATE INDEX IF NOT EXISTS idx_embeddings_collection
ON rag_embeddings (collection);

CREATE INDEX IF NOT EXISTS idx_embeddings_doc_id
ON rag_embeddings (document_id);
```

## Docker Compose Production Setup

Create `docker-compose.prod.yml`:

```yaml
version: '3.8'

services:
  postgres:
    image: pgvector/pgvector:pg15
    environment:
      POSTGRES_USER: ${PG_USER}
      POSTGRES_PASSWORD: ${PG_PASSWORD}
      POSTGRES_DB: ${PG_DATABASE}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${PG_USER} -d ${PG_DATABASE}"]
      interval: 5s
      timeout: 5s
      retries: 5
    deploy:
      resources:
        limits:
          memory: 4G
        reservations:
          memory: 2G
    restart: always

  api:
    image: ghcr.io/llm4s/rag-in-a-box:latest
    environment:
      # Database
      PG_HOST: postgres
      PG_PORT: 5432
      PG_DATABASE: ${PG_DATABASE}
      PG_USER: ${PG_USER}
      PG_PASSWORD: ${PG_PASSWORD}

      # Security (required)
      JWT_SECRET: ${JWT_SECRET}
      AUTH_MODE: basic
      ADMIN_PASSWORD: ${ADMIN_PASSWORD}
      ALLOWED_ORIGINS: ${ALLOWED_ORIGINS}

      # API Keys
      OPENAI_API_KEY: ${OPENAI_API_KEY}

      # Production settings
      RATE_LIMIT_ENABLED: "true"
      METRICS_ENABLED: "true"
      LOG_FORMAT: json
    ports:
      - "8080:8080"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health/ready"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s
    depends_on:
      postgres:
        condition: service_healthy
    deploy:
      resources:
        limits:
          memory: 2G
        reservations:
          memory: 1G
    restart: always

volumes:
  postgres_data:
```

## Kubernetes Deployment

See [deploy/kubernetes/](../deploy/kubernetes/) for complete manifests.

Quick start:

```bash
# Create namespace
kubectl create namespace ragbox

# Create secrets
kubectl create secret generic ragbox-secrets \
  --namespace ragbox \
  --from-literal=jwt-secret="your-jwt-secret" \
  --from-literal=admin-password="your-admin-password" \
  --from-literal=pg-password="your-db-password" \
  --from-literal=openai-api-key="sk-..."

# Apply manifests
kubectl apply -f deploy/kubernetes/

# Check status
kubectl get pods -n ragbox
```

## Health Checks

### Endpoints

| Endpoint | Purpose | Response |
|----------|---------|----------|
| `GET /health` | Basic health | `{"status": "healthy", "uptime": ...}` |
| `GET /health/ready` | Readiness (includes DB) | `{"ready": true, "checks": {...}}` |
| `GET /health/live` | Liveness | `{"status": "ok"}` |

### Kubernetes Probes

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

## Monitoring

### Prometheus Metrics

Enable with `METRICS_ENABLED=true`. Metrics available at `/metrics`:

```
# Document and chunk counts
ragbox_documents_total
ragbox_chunks_total
ragbox_vectors_total

# Per-collection metrics
ragbox_collection_documents{collection="..."}
ragbox_collection_chunks{collection="..."}

# Health status
ragbox_healthy
ragbox_ready
ragbox_component_status{component="..."}

# JVM metrics
ragbox_jvm_memory_used_bytes{area="heap"}
ragbox_jvm_memory_used_bytes{area="nonheap"}
ragbox_jvm_threads_current
ragbox_jvm_gc_collection_seconds_total{gc="..."}

# System
ragbox_uptime_seconds
ragbox_jvm_available_processors
```

### Grafana Dashboard

Import the dashboard from `deploy/grafana/ragbox-dashboard.json` or create alerts based on:

- `ragbox_ready == 0` - Service not ready
- `ragbox_component_status{component="database"} == 0` - Database unreachable
- `rate(ragbox_jvm_gc_collection_seconds_total[5m]) > 0.1` - High GC pressure

## Scaling

### Horizontal Scaling

RAG in a Box supports horizontal scaling with shared PostgreSQL:

1. Ensure `JWT_SECRET` is identical across all instances
2. Use a load balancer with session affinity (optional)
3. Scale API replicas:

```yaml
# Kubernetes
apiVersion: apps/v1
kind: Deployment
spec:
  replicas: 3
```

### Connection Pooling

The service uses HikariCP with these defaults:
- Maximum pool size: 10 connections
- Minimum idle: 2 connections
- Connection timeout: 30 seconds

Adjust via environment variables if needed (future release).

## Backup and Recovery

### Database Backup

```bash
# Backup
pg_dump -U rag -h localhost -d ragdb > ragbox_backup.sql

# With vectors (large file)
pg_dump -U rag -h localhost -d ragdb -Fc > ragbox_backup.dump

# Restore
pg_restore -U rag -h localhost -d ragdb ragbox_backup.dump
```

### Disaster Recovery

1. Regular automated backups (daily recommended)
2. Test restore procedure quarterly
3. Keep backups in separate region/availability zone
4. Document recovery time objective (RTO) and recovery point objective (RPO)

## Troubleshooting

See [troubleshooting.md](troubleshooting.md) for common issues and solutions.

## Upgrading

### Minor Versions

```bash
# Pull latest image
docker-compose pull

# Restart with zero downtime (rolling update)
docker-compose up -d --no-deps api
```

### Major Versions

1. Read release notes for breaking changes
2. Backup database
3. Stop services
4. Apply any required migrations
5. Update and restart

```bash
docker-compose down
docker-compose pull
docker-compose up -d
```

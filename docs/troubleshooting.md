# Troubleshooting Guide

Common issues and solutions for RAG in a Box.

## Health Check Failures

### `/health/ready` returns `ready: false`

Check which component is failing:

```bash
curl -s http://localhost:8080/health/ready | jq
```

Response example:
```json
{
  "ready": false,
  "checks": {
    "database": {"status": "error", "message": "Database unreachable: Connection refused"},
    "rag_instance": {"status": "ok"},
    "api_keys": {"status": "ok"}
  }
}
```

#### Database Check Failed

**Symptoms:**
- `database` status is `error`
- Message mentions connection refused or timeout

**Solutions:**

1. Verify PostgreSQL is running:
   ```bash
   # Docker Compose
   docker-compose ps postgres
   docker-compose logs postgres

   # Kubernetes
   kubectl get pods -l app.kubernetes.io/component=postgres -n ragbox
   kubectl logs -l app.kubernetes.io/component=postgres -n ragbox
   ```

2. Check database connectivity:
   ```bash
   # From API container
   docker-compose exec api sh -c 'nc -zv postgres 5432'

   # Direct connection test
   psql -h localhost -p 15432 -U rag -d ragdb -c 'SELECT 1'
   ```

3. Verify environment variables:
   ```bash
   docker-compose exec api env | grep PG_
   ```

4. Check for connection pool exhaustion:
   - Review `ragbox_jvm_threads_current` metric
   - Check PostgreSQL `max_connections` vs active connections

#### RAG Instance Failed

**Symptoms:**
- `rag_instance` status is `error`

**Solutions:**

1. Check API key configuration:
   ```bash
   # Verify keys are set (shows if empty or not)
   docker-compose exec api sh -c 'echo "OPENAI: ${OPENAI_API_KEY:+set}"'
   ```

2. Test embedding provider:
   ```bash
   curl -X POST http://localhost:8080/api/v1/chunking/preview \
     -H "Content-Type: application/json" \
     -d '{"content": "test", "strategy": "simple"}'
   ```

3. Check logs for initialization errors:
   ```bash
   docker-compose logs api | grep -i "error\|failed\|exception"
   ```

#### API Keys Check Failed

**Symptoms:**
- `api_keys` status is `warning` or `error`

**Solutions:**

1. Verify the required API key is set for your embedding provider:
   - OpenAI: `OPENAI_API_KEY`
   - Anthropic: `ANTHROPIC_API_KEY`
   - Voyage: `VOYAGE_API_KEY`

2. Test the API key directly:
   ```bash
   # OpenAI
   curl https://api.openai.com/v1/models \
     -H "Authorization: Bearer $OPENAI_API_KEY"
   ```

## Startup Issues

### Service fails to start

**Check logs:**
```bash
docker-compose logs api
# or
kubectl logs -l app.kubernetes.io/component=api -n ragbox
```

**Common causes:**

1. **Missing required environment variables:**
   ```
   Configuration validation failed: JWT_SECRET required
   ```
   Solution: Set the required environment variable.

2. **Database not ready:**
   ```
   Connection refused
   ```
   Solution: Ensure PostgreSQL is healthy before starting API:
   ```yaml
   depends_on:
     postgres:
       condition: service_healthy
   ```

3. **Port already in use:**
   ```
   Address already in use
   ```
   Solution: Change `SERVER_PORT` or stop conflicting service.

4. **Invalid configuration:**
   ```
   Failed to parse configuration
   ```
   Solution: Check environment variable values and types.

### Slow startup

**Symptoms:**
- Takes > 60 seconds to start
- Health checks failing during startup

**Solutions:**

1. Increase startup probe timeout (Kubernetes):
   ```yaml
   startupProbe:
     failureThreshold: 60
     periodSeconds: 5
   ```

2. Pre-warm the JVM:
   - First request after startup may be slow
   - Consider adding a warmup request in init container

3. Check database connection time:
   - Use connection pooling (default: HikariCP)
   - Ensure database is in same network/region

## Query Issues

### Queries return empty results

**Symptoms:**
- `/api/v1/query` returns answer but no relevant chunks
- `/api/v1/search` returns empty results

**Solutions:**

1. Verify documents are indexed:
   ```bash
   curl http://localhost:8080/api/v1/documents
   curl http://localhost:8080/health  # Check documentCount
   ```

2. Check collection filter:
   ```bash
   # Query without collection filter
   curl -X POST http://localhost:8080/api/v1/search \
     -H "Content-Type: application/json" \
     -d '{"query": "your search term"}'
   ```

3. Increase top_k:
   ```bash
   curl -X POST http://localhost:8080/api/v1/search \
     -H "Content-Type: application/json" \
     -d '{"query": "your search term", "topK": 20}'
   ```

4. Check vector index:
   ```sql
   SELECT COUNT(*) FROM rag_embeddings;
   SELECT COUNT(*) FROM rag_embeddings WHERE embedding IS NOT NULL;
   ```

### Queries are slow

**Symptoms:**
- Query latency > 5 seconds
- Timeouts on search requests

**Solutions:**

1. Check index status:
   ```sql
   SELECT indexname, indexdef FROM pg_indexes
   WHERE tablename = 'rag_embeddings';
   ```

2. Create HNSW index if missing:
   ```sql
   CREATE INDEX IF NOT EXISTS idx_embeddings_hnsw
   ON rag_embeddings USING hnsw (embedding vector_cosine_ops)
   WITH (m = 16, ef_construction = 64);
   ```

3. Analyze table:
   ```sql
   ANALYZE rag_embeddings;
   ```

4. Check PostgreSQL performance:
   ```sql
   EXPLAIN ANALYZE
   SELECT * FROM rag_embeddings
   ORDER BY embedding <=> '[...]'::vector
   LIMIT 5;
   ```

5. Reduce top_k value

## Document Ingestion Issues

### Documents fail to ingest

**Check the response:**
```bash
curl -X POST http://localhost:8080/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{"content": "test", "documentId": "test-1"}' \
  -w "\n%{http_code}"
```

**Common errors:**

1. **Request too large (413):**
   - Increase `MAX_BODY_SIZE_MB`
   - Split large documents

2. **Rate limited (429):**
   - Wait and retry
   - Increase `RATE_LIMIT_MAX_REQUESTS`

3. **Embedding API error:**
   - Check API key validity
   - Check provider rate limits
   - View logs for specific error

### Duplicate documents

**Symptoms:**
- Same document appears multiple times
- Document count higher than expected

**Solutions:**

1. Use consistent document IDs:
   ```bash
   curl -X PUT http://localhost:8080/api/v1/documents/my-doc-id \
     -H "Content-Type: application/json" \
     -d '{"content": "updated content"}'
   ```

2. Check for duplicates:
   ```sql
   SELECT document_id, COUNT(*)
   FROM rag_embeddings
   GROUP BY document_id
   HAVING COUNT(*) > 1;
   ```

## Authentication Issues

### JWT token expired

**Symptoms:**
- 401 Unauthorized after token was working
- Error: "Token expired"

**Solutions:**

1. Re-authenticate:
   ```bash
   curl -X POST http://localhost:8080/api/v1/auth/login \
     -H "Content-Type: application/json" \
     -d '{"username": "admin", "password": "your-password"}'
   ```

2. Increase token expiration:
   - Set `JWT_EXPIRATION` (seconds)
   - Default is 86400 (24 hours)

### Multi-instance JWT failures

**Symptoms:**
- Token works on one instance but not another
- Intermittent 401 errors behind load balancer

**Solutions:**

1. Ensure `JWT_SECRET` is identical across all instances
2. Don't rely on auto-generated secrets in production

## Memory Issues

### OutOfMemoryError

**Symptoms:**
- Container killed (OOMKilled)
- `java.lang.OutOfMemoryError`

**Solutions:**

1. Increase container memory:
   ```yaml
   resources:
     limits:
       memory: "4Gi"
   ```

2. Set JVM heap size:
   ```bash
   JAVA_OPTS="-Xmx2g -Xms1g"
   ```

3. Monitor memory usage:
   ```bash
   curl http://localhost:8080/metrics | grep jvm_memory
   ```

### High GC pressure

**Symptoms:**
- Slow response times
- High CPU usage
- `ragbox_jvm_gc_collection_seconds_total` increasing rapidly

**Solutions:**

1. Increase heap size
2. Review chunk size settings (smaller chunks = more objects)
3. Consider G1GC tuning:
   ```bash
   JAVA_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=200"
   ```

## Network Issues

### Connection timeouts

**Symptoms:**
- Requests timeout
- "Connection timed out" errors

**Solutions:**

1. Check network connectivity:
   ```bash
   # From API container to database
   nc -zv postgres 5432

   # To embedding provider
   curl -v https://api.openai.com
   ```

2. Check firewall rules / security groups

3. Increase timeouts:
   - `SHUTDOWN_TIMEOUT_SECONDS`
   - Database connection timeout

### CORS errors

**Symptoms:**
- Browser console shows CORS errors
- Preflight requests fail

**Solutions:**

1. Configure allowed origins:
   ```bash
   ALLOWED_ORIGINS="https://app.example.com,https://admin.example.com"
   ```

2. For development, allow all origins:
   ```bash
   ALLOWED_ORIGINS="*"
   ```

## Logging

### Enable debug logging

```bash
LOG_LEVEL=DEBUG docker-compose up
```

### JSON logging for production

```bash
LOG_FORMAT=json
```

### View specific log categories

```bash
# Filter for errors
docker-compose logs api 2>&1 | grep -i error

# Filter for database
docker-compose logs api 2>&1 | grep -i "database\|postgres\|jdbc"

# Filter for embeddings
docker-compose logs api 2>&1 | grep -i "embed\|openai\|vector"
```

## Getting Help

If you're still having issues:

1. Check the [GitHub Issues](https://github.com/llm4s/rag-in-a-box/issues)
2. Review the [API documentation](api/index.md)
3. Open a new issue with:
   - RAG in a Box version
   - Environment (Docker, Kubernetes, etc.)
   - Relevant logs
   - Steps to reproduce

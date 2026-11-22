---
layout: page
title: Troubleshooting
parent: Operations
nav_order: 3
---

# Troubleshooting Guide

Common issues and solutions for RAG in a Box.

The `curl` examples assume `AUTH_MODE=open` without `API_KEY`. Otherwise add a credential,
e.g. `-H "Authorization: Bearer $API_KEY"` (or a `rat_` access token, or a JWT in basic mode).

Error responses have the body `{"error": "<code>", "message": "<text>", "details": "<optional>"}`
(`ErrorResponse`). A `500` never carries the cause: its message is always `Internal server error`
and the exception is in the server log (with the request's method and path), so check the log.

## Health Check Failures

### `/health/ready` returns `ready: false`

Check which component is failing:

```bash
curl -s http://localhost:8080/health/ready | jq
```

The endpoint answers `503` while not ready. Only the `database` and `rag_instance` checks
decide readiness; `api_keys` is informational (`warning` when no provider key is set).

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
   docker compose ps postgres
   docker compose logs postgres

   # Kubernetes
   kubectl get pods -l app.kubernetes.io/component=postgres -n ragbox
   kubectl logs -l app.kubernetes.io/component=postgres -n ragbox
   ```

2. Check database connectivity:
   ```bash
   # From API container
   docker compose exec api sh -c 'curl -sv telnet://postgres:5432 --max-time 3'

   # Direct connection test
   psql -h localhost -p 15432 -U rag -d ragdb -c 'SELECT 1'
   ```

3. Verify environment variables:
   ```bash
   docker compose exec api env | grep -E 'PG_|DATABASE_URL'
   ```
   `DATABASE_URL`, when set, overrides the `PG_*` settings.

4. Check for connection pool exhaustion:
   - Each instance uses one pool of `DB_POOL_SIZE` connections (default 10)
   - Check PostgreSQL `max_connections` against replicas x `DB_POOL_SIZE`

#### RAG Instance Failed

**Symptoms:**
- `rag_instance` status is `error`; its `message` says why the llm4s pipeline could not be built

The pipeline is rebuilt on the next readiness check or request (at most every few seconds), so
the check recovers by itself once the cause is fixed.

**Solutions:**

1. Check API key configuration:
   ```bash
   # Verify keys are set (shows if empty or not)
   docker compose exec api sh -c 'echo "OPENAI: ${OPENAI_API_KEY:+set}"'
   ```

2. Check the configured providers are known and configured:
   ```bash
   curl -s http://localhost:8080/api/v1/config/providers | jq
   ```

3. Test the embedding provider with a search (it embeds the query):
   ```bash
   curl -X POST http://localhost:8080/api/v1/search \
     -H "Content-Type: application/json" \
     -d '{"query": "test"}'
   ```

4. Check logs for initialization errors:
   ```bash
   docker compose logs api | grep -i "error\|failed\|exception"
   ```

If the answer LLM cannot be built (for example `LLM_MODEL` names an Anthropic model but
`ANTHROPIC_API_KEY` is unset), the server logs a warning and runs without answer generation:
search works, `/api/v1/query` and chat fail with a configuration error.

#### API Keys Check Failed

**Symptoms:**
- `api_keys` status is `warning` or `error`

**Solutions:**

1. Verify the API keys for your providers are set:
   - OpenAI (LLM or embeddings): `OPENAI_API_KEY`
   - Anthropic (LLM): `ANTHROPIC_API_KEY`
   - Voyage (embeddings): `VOYAGE_API_KEY`
   - Cohere (reranking): `COHERE_API_KEY`
   - Ollama needs no key

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
docker compose logs api
# or
kubectl logs -l app.kubernetes.io/component=api -n ragbox
```

**Common causes:**

1. **Missing or invalid settings:** every problem is logged as `Configuration error: ...`,
   followed by
   ```
   Configuration validation failed: JWT_SECRET must be explicitly set when auth mode is 'basic' (for multi-instance deployments), ...
   ```
   Solution: Set the named environment variable. In basic mode `JWT_SECRET` (32+ characters)
   and `ADMIN_PASSWORD` are required; in oauth mode `OAUTH_CLIENT_ID`, `OAUTH_CLIENT_SECRET` and
   `OAUTH_REDIRECT_URI`.

   Placeholder values from the templates (`CHANGE_ME...`, `REPLACE-ME`) are rejected as well, as
   are an `ADMIN_PASSWORD` of `admin` or one shorter than `PASSWORD_MIN_LENGTH` (12).

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

4. **Invalid configuration values:** a value of the wrong type (e.g. `SERVER_PORT=abc`) or an
   unknown `AUTH_MODE` / `OAUTH_PROVIDER` fails startup with an exception naming the setting.
   Solution: Check environment variable values and types.

5. **Schema migration failed:** Flyway logs the failing migration; the transaction is rolled
   back and the server does not start. The database user needs permission to create tables, and
   the `vector` extension must exist (`scripts/init-db.sql` creates it; it needs a superuser).

6. **`PERMISSIONS_REQUIRED=true` and the permission schema could not be initialised:**
   ```
   ... Refusing to start without permission-based RAG because PERMISSIONS_REQUIRED=true
   ```
   Without `PERMISSIONS_REQUIRED` the server logs `Falling back to legacy RAG mode (no
   permissions)` instead and starts; queries then ignore collection permissions, and
   `AUTH_MODE=oauth` refuses to start.

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

2. Pending schema migrations run before the server listens; a large first migration of an
   old database can take a while. Run them separately with `--migrate-only` if needed.

3. Check database connection time:
   - Ensure the database is in the same network/region

## Query Issues

### Queries return empty results

**Symptoms:**
- `/api/v1/query` returns answer but no relevant chunks
- `/api/v1/search` returns empty results

**Solutions:**

1. Verify documents are indexed:
   ```bash
   curl http://localhost:8080/api/v1/documents
   curl http://localhost:8080/api/v1/stats  # documentCount, chunkCount, collections
   ```

2. Check permissions: in permission mode, a non-admin caller only sees collections it may
   query, and an access token restricted to collections only those. `GET /api/v1/auth/me` shows
   who the server thinks you are.

3. Check collection filter:
   ```bash
   # Query without collection filter
   curl -X POST http://localhost:8080/api/v1/search \
     -H "Content-Type: application/json" \
     -d '{"query": "your search term"}'
   ```

4. Increase `topK`:
   ```bash
   curl -X POST http://localhost:8080/api/v1/search \
     -H "Content-Type: application/json" \
     -d '{"query": "your search term", "topK": 20}'
   ```

5. Check the vector table has rows:
   ```sql
   SELECT COUNT(*) FROM rag_embeddings;
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

   The table and its indexes are created by llm4s.

2. Analyze table:
   ```sql
   ANALYZE rag_embeddings;
   ```

3. Look at where the time goes: `/api/v1/query` responses include per-stage timings, and the
   [analytics](guide/analytics.md) endpoints aggregate them. Most of the time is usually the
   LLM call; reranking (`RAG_RERANKING_STRATEGY`) and query rewriting (`RAG_QUERY_TRANSFORM`)
   add further LLM or Cohere calls.

4. Reduce `topK`

## Document Ingestion Issues

### Documents fail to ingest

**Check the response:**
```bash
curl -X POST http://localhost:8080/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{"content": "test", "filename": "test.txt"}' \
  -w "\n%{http_code}"
```

`POST /api/v1/documents` assigns a new id to every document; use
`PUT /api/v1/documents/{id}` to choose the id (and update in place).

**Common errors:**

1. **Request too large (413):**
   - Increase `MAX_BODY_SIZE_MB`
   - Split large documents

2. **Rate limited (429):**
   - Wait and retry (the response has a `Retry-After` header)
   - Increase `RATE_LIMIT_MAX_REQUESTS`

3. **Forbidden (403):** the caller is not an administrator, or an access token lacks the
   `documents:write` scope or may not use that collection.

4. **Ingestion policy (400)** for `POST /api/v1/ingest/directory` or `/ingest/url`: the path
   is not under `INGEST_ALLOWED_ROOTS` (by default the configured directory sources; with none,
   directory ingestion is disabled), or the URL's host is not in `INGEST_URL_ALLOWED_HOSTS` or
   resolves to a private address (`INGEST_URL_ALLOW_PRIVATE_NETWORKS`).

5. **Embedding API error:**
   - Check API key validity
   - Check provider rate limits
   - View logs for specific error

### Duplicate documents

**Symptoms:**
- Same document appears multiple times
- Document count higher than expected

`POST /api/v1/documents` always creates a new document. Re-ingesting the same content with it
creates duplicates.

**Solutions:**

1. Use stable document IDs with `PUT` (unchanged content is detected by hash and skipped):
   ```bash
   curl -X PUT http://localhost:8080/api/v1/documents/my-doc-id \
     -H "Content-Type: application/json" \
     -d '{"content": "updated content"}'
   ```

2. For external systems, use the [sync API](api/sync.md), which also removes documents that
   no longer exist at the source.

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

1. Ensure `JWT_SECRET` (and `JWT_ISSUER` / `JWT_AUDIENCE`, if set) is identical across all instances

### 401 with a valid-looking credential

- A request carrying an invalid credential is rejected even if another credential on it would
  be valid; send only one.
- JWTs are only accepted in `AUTH_MODE=basic`.
- `?api_key=` is not accepted; use `X-API-Key` or `Authorization: Bearer`. `?access_token=` only
  works on the WebSocket endpoint `/ws`.
- A JWT stops working when its user is deleted.

### 403 Forbidden

- Administrative endpoints need an administrator (admin user, `API_KEY`, admin-scoped token,
  or a member of `OAUTH_ADMIN_GROUPS`).
- An access token without the `admin` scope may only use documents, sync, query/search, chat,
  `/api/v1/auth/me` and `/ws`, and only with the matching scope. See
  [Access Tokens](guide/access-tokens.md).

### Login throttled (429)

Failed `POST /api/v1/auth/login` attempts are limited per client IP and per username
(`RATE_LIMIT_LOGIN_MAX_ATTEMPTS`, default 10 per `RATE_LIMIT_LOGIN_WINDOW_SECONDS`, default
300), even when `RATE_LIMIT_ENABLED=false`. Behind a reverse proxy, set
`RATE_LIMIT_TRUSTED_PROXIES` so clients are told apart by `X-Forwarded-For` rather than all
sharing the proxy's address.

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

2. Adjust the JVM heap. The image sets `JAVA_OPTS="-XX:MaxRAMPercentage=75"` (heap relative
   to the container limit); override it, e.g.:
   ```bash
   JAVA_OPTS="-XX:MaxRAMPercentage=60"
   ```

3. Monitor memory usage (with `METRICS_ENABLED=true`; `/metrics` needs an admin credential
   unless `METRICS_REQUIRE_AUTH=false`):
   ```bash
   curl -H "Authorization: Bearer $API_KEY" http://localhost:8080/metrics | grep jvm_memory
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
   curl -sv telnet://postgres:5432 --max-time 3

   # To embedding provider
   curl -v https://api.openai.com
   ```

2. Check firewall rules / security groups

3. The database connection timeout is 30 seconds (HikariCP); `SHUTDOWN_TIMEOUT_SECONDS` only
   affects graceful shutdown.

### CORS errors

**Symptoms:**
- Browser console shows CORS errors
- Preflight requests fail

By default ragbox sends no CORS headers: only pages served from its own origin (such as the
admin UI at `/admin`) can read API responses.

**Solutions:**

1. Configure allowed origins:
   ```bash
   ALLOWED_ORIGINS="https://app.example.com,https://admin.example.com"
   ```

2. For admin UI development, use the Vite dev server (`cd admin-ui && npm run dev`): it proxies
   `/api`, `/health` and `/ws` to `localhost:8080`, so no CORS is needed. To call the API from
   another local origin, allow it explicitly (`ALLOWED_ORIGINS="http://localhost:5173"`), or
   allow all origins in development only:
   ```bash
   ALLOWED_ORIGINS="*"
   ```

## Logging

### Enable debug logging

```bash
LOG_LEVEL=DEBUG docker compose up
```

### JSON logging for production

```bash
LOG_FORMAT=json
```

### View specific log categories

```bash
# Filter for errors
docker compose logs api 2>&1 | grep -i error

# Filter for database
docker compose logs api 2>&1 | grep -i "database\|postgres\|jdbc"

# Filter for embeddings
docker compose logs api 2>&1 | grep -i "embed\|openai\|vector"
```

## Getting Help

If you're still having issues:

1. Check the [GitHub Issues](https://github.com/llm4s/rag_in_a_box/issues)
2. Review the [API documentation](api/index.md)
3. Open a new issue with:
   - RAG in a Box version
   - Environment (Docker, Kubernetes, etc.)
   - Relevant logs
   - Steps to reproduce

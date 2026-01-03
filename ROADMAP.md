# RAG in a Box - Product Roadmap

## Vision

A turnkey RAG solution: start a Docker container, point it at documents, and immediately have a production-ready RAG system with great retrieval quality. Works for simple use cases out of the box, scales to enterprise with permissions and external ingesters.

---

## Current State

**What's Built:**
- Core RAG pipeline (LLM4S): ingestion, chunking, hybrid search, answer generation
- Permission-based RAG: principals (users/groups), collections with `queryableBy`, documents with `readableBy`
- Document registry: PostgreSQL-backed with content hashing, timestamps, sync tracking
- Built-in ingesters: directory (scheduled), URL, database (JDBC)
- Sync API pattern: `GET /sync/documents`, `POST /sync` (with prune)
- Admin UI: dashboard, documents, upload, config, chunking preview
- API key authentication (simple static key)
- WebSocket infrastructure for real-time updates
- Prometheus metrics (basic stats only)

---

## Completion Checklist

### Phase 1: Authentication & Access Control (P0)

#### 1.1 Authentication Modes
- [ ] Add `auth.mode` config option (open | basic | oauth)
- [ ] Implement mode switching logic in Main.scala
- [ ] Update application.conf with auth configuration block

#### 1.2 Basic Auth Implementation
- [ ] Create `users` database table (id, username, password_hash, role, created_at)
- [ ] Create `auth/AuthService.scala` - password hashing, validation
- [ ] Create `auth/JwtService.scala` - JWT token generation/validation
- [ ] Create `routes/AuthRoutes.scala`:
  - [ ] `POST /api/v1/auth/login` - authenticate, return JWT
  - [ ] `POST /api/v1/auth/logout` - invalidate token
  - [ ] `GET /api/v1/auth/me` - current user info
- [ ] Create `routes/UserRoutes.scala`:
  - [ ] `POST /api/v1/users` - create user (admin only)
  - [ ] `GET /api/v1/users` - list users (admin only)
  - [ ] `DELETE /api/v1/users/{id}` - delete user (admin only)
  - [ ] `PUT /api/v1/users/{id}/password` - change password
- [ ] Create default admin user on first startup (if mode=basic)
- [ ] Update AuthMiddleware to validate JWT tokens

#### 1.3 Access Tokens for External Ingesters
- [ ] Create `access_tokens` database table
- [ ] Create `routes/TokenRoutes.scala`:
  - [ ] `POST /api/v1/tokens` - create token (admin only)
  - [ ] `GET /api/v1/tokens` - list tokens (admin only)
  - [ ] `DELETE /api/v1/tokens/{id}` - revoke token (admin only)
- [ ] Implement token scopes: documents:read, documents:write, sync:read, sync:write, query
- [ ] Add collection-scoped tokens (optional restriction)
- [ ] Update AuthMiddleware to accept `rat_*` tokens
- [ ] Track last_used_at for tokens

#### 1.4 Admin UI - Authentication
- [ ] Create Login.vue page
- [ ] Add authentication state to Pinia store
- [ ] Implement JWT token storage and refresh
- [ ] Create UserManagement.vue (admin only)
- [ ] Create TokenManagement.vue (admin only)
- [ ] Add logout functionality
- [ ] Protect routes requiring authentication

#### 1.5 OAuth2/OIDC (Future)
- [ ] Support OAuth2 authorization code flow
- [ ] Map OAuth claims to principals
- [ ] Support: Google, Azure AD, Okta, Keycloak

---

### Phase 2: External Ingester API (P0)

#### 2.1 Enhanced Sync API
- [ ] Add `?include=hash,updatedAt` parameter to `GET /sync/documents`
- [ ] Add `?since=timestamp` parameter for incremental sync
- [ ] Add `GET /api/v1/sync/documents/{id}` - single document state
- [ ] Add `POST /api/v1/sync/check` - batch check document states
- [ ] Update DocumentRoutes.scala with new endpoints

#### 2.2 Deleted Document Detection
- [ ] Add `dryRun` parameter to `POST /api/v1/sync`
- [ ] Return `wouldDelete` list when dryRun=true
- [ ] Add batch delete: `DELETE /api/v1/documents` with body
- [ ] Update PgDocumentRegistry with batch operations

#### 2.3 API Documentation
- [ ] Update OpenAPI spec with all sync endpoints
- [ ] Add request/response examples to Swagger UI
- [ ] Create `docs/external-ingesters.md` guide
- [ ] Document full sync pattern with curl examples
- [ ] Document incremental sync pattern
- [ ] Document event-driven sync pattern

---

### Phase 3: Built-in Ingesters (P1)

#### 3.1 Directory Watcher Enhancement
- [ ] Add `watch: true` option for real-time file watching
- [ ] Implement Java NIO WatchService integration
- [ ] Handle file create/modify/delete events
- [ ] Add debouncing for rapid file changes

#### 3.2 S3/Cloud Storage Ingester
- [ ] Add S3 client dependency (AWS SDK)
- [ ] Create S3IngestionSource.scala
- [ ] Support bucket, prefix, region, patterns configuration
- [ ] Implement incremental sync via S3 object metadata
- [ ] Add GCS and Azure Blob support (future)

#### 3.3 Web Crawler
- [ ] Create WebCrawlerSource.scala
- [ ] Implement breadth-first crawling from seed URLs
- [ ] Support max-depth, follow-patterns, exclude-patterns
- [ ] Add robots.txt compliance
- [ ] Implement rate limiting
- [ ] Extract and clean HTML content

#### 3.4 Enterprise Connectors (Future)
- [ ] Confluence connector
- [ ] SharePoint connector
- [ ] Google Drive connector

---

### Phase 4: RAGA Evaluation & Optimization (P1)

#### 4.1 Query Metrics Collection
- [ ] Create `query_logs` database table
- [ ] Instrument RAGService to log all queries
- [ ] Track: embedding_latency, search_latency, llm_latency, total_latency
- [ ] Track: chunks_retrieved, chunks_used, answer_tokens
- [ ] Add `GET /api/v1/analytics/queries` endpoint
- [ ] Add `GET /api/v1/analytics/queries/summary` endpoint

#### 4.2 Relevance Feedback System
- [ ] Create `query_feedback` database table
- [ ] Add `POST /api/v1/feedback` endpoint
- [ ] Support rating (1-5), relevant_chunks, comment
- [ ] Link feedback to query_logs

#### 4.3 Analytics Dashboard
- [ ] Create Analytics.vue in admin UI
- [ ] Display query latency distribution (p50/p95/p99)
- [ ] Show retrieval precision metrics
- [ ] Show answer satisfaction (average rating)
- [ ] List top failed queries (low ratings)
- [ ] Show chunk utilization metrics

#### 4.4 Optimization Suggestions
- [ ] Implement suggestion engine based on metrics
- [ ] "Increase topK" when all chunks are used
- [ ] "Review chunking" for low precision collections
- [ ] "Add documents" for consistently failed query patterns

#### 4.5 A/B Testing Framework (Future)
- [ ] Support multiple configurations per collection
- [ ] Route queries to different configs
- [ ] Compare metrics across configurations

---

### Phase 5: User Experience (P1)

#### 5.1 Chat Interface
- [ ] Create Chat.vue in admin UI
- [ ] Implement query input with streaming response (SSE)
- [ ] Display source citations with chunk previews
- [ ] Add conversation history (session-based)
- [ ] Add collection selector dropdown
- [ ] Add feedback buttons (thumbs up/down)
- [ ] Create ChatMessage.vue component
- [ ] Create SourceCitation.vue component

#### 5.2 Permission Management UI
- [ ] Create PrincipalManagement.vue (users/groups)
- [ ] Create CollectionPermissions.vue (queryableBy)
- [ ] Create DocumentAccess.vue (readableBy)
- [ ] Visual permission inheritance display

#### 5.3 Ingestion Dashboard
- [ ] Create IngestionDashboard.vue
- [ ] Real-time ingestion progress (WebSocket)
- [ ] Source status indicators (healthy/error)
- [ ] Ingestion history with drill-down
- [ ] Manual trigger buttons per source

---

### Phase 6: Production Readiness (P2)

#### 6.1 Security Hardening
- [ ] TLS/SSL configuration guide
- [ ] API rate limiting (per-token, per-IP)
- [ ] Request size limits
- [ ] Audit logging (who did what when)
- [ ] Secrets management (Vault integration)

#### 6.2 Observability
- [ ] Structured logging (JSON format)
- [ ] Distributed tracing (OpenTelemetry)
- [ ] Grafana dashboard templates
- [ ] Alerting rules (Prometheus AlertManager)

#### 6.3 Deployment
- [ ] Official Docker images on Docker Hub
- [ ] Kubernetes Helm charts
- [ ] Health check improvements (deep checks)
- [ ] Graceful shutdown handling
- [ ] Database migration tooling (Flyway)

#### 6.4 High Availability
- [ ] Multi-instance support (stateless design)
- [ ] Database connection pooling (HikariCP tuning)
- [ ] Cache layer (Redis for sessions, embeddings)
- [ ] Load balancer configuration guide

---

## Quick Wins (Start Now)

These can be implemented quickly with high impact:

- [ ] Add `?include=hash,updatedAt` to sync/documents endpoint
- [ ] Add `POST /sync/check` for batch document state checks
- [ ] Add `dryRun` option to prune endpoint
- [ ] Create query_logs table and start collecting metrics

---

## Decisions Made

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Auth approach | Basic auth first, OAuth later | Simpler to implement, covers most use cases |
| Multi-tenancy | Collection-based isolation | Leverages existing permission system |
| SDK approach | REST API docs only | Users can use any HTTP client |
| Licensing | Fully open source | Community-first approach, no enterprise paywall |

---

## Auth Mode Migration

**Migrating from `open` → `basic` → `oauth` is straightforward:**

1. **open → basic**:
   - Set `auth.mode = "basic"` and `ADMIN_PASSWORD`
   - Restart server - admin user is auto-created
   - Existing documents and collections remain unchanged
   - API keys and access tokens start being enforced

2. **basic → oauth**:
   - Add OAuth provider config (issuer, client-id, client-secret)
   - Set `auth.mode = "oauth"`
   - Existing users can be linked to OAuth identities via email/username matching
   - Access tokens continue to work for external ingesters

3. **Rollback**: Simply change `auth.mode` back - the database schema supports all modes simultaneously

**Key principle**: The authentication mode only controls *how* users authenticate, not *what* they can access. The permission system (principals, collections, documents) is independent and persists across mode changes.

---

## Success Criteria

A successful RAG in a Box should:

1. **Zero-config start**: `docker run ragbox` with mounted docs directory works immediately
2. **5-minute enterprise setup**: Add auth + permissions with simple config
3. **Great retrieval quality**: Built-in RAGA metrics show precision/recall
4. **Easy optimization**: Dashboard suggests improvements based on metrics
5. **Flexible ingestion**: Works with files, URLs, S3, databases, or custom ingesters
6. **Production-ready**: Scales, monitors, secures out of the box

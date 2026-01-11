# RAG in a Box - Product Roadmap

## Vision

A turnkey RAG solution: start a Docker container, point it at documents, and immediately have a production-ready RAG system with great retrieval quality. Works for simple use cases out of the box, scales to enterprise with permissions and external ingesters.

---

## Current State

**What's Built:**
- Core RAG pipeline (LLM4S): ingestion, chunking, hybrid search, answer generation
- Permission-based RAG: principals (users/groups), collections with `queryableBy`, documents with `readableBy`
- Document registry: PostgreSQL-backed with content hashing, timestamps, sync tracking
- Built-in ingesters: directory (scheduled), URL, database (JDBC), web crawler
- Sync API pattern: `GET /sync/documents`, `POST /sync` (with prune)
- Admin UI: dashboard, documents, upload, config, chunking preview, chat, analytics
- **Full authentication system**: Open, Basic (JWT), and OAuth2/OIDC modes
- **Access token management**: `rat_*` tokens for external ingesters
- WebSocket infrastructure for real-time updates
- Prometheus metrics endpoint
- Rate limiting and request size validation
- Helm charts for Kubernetes deployment
- Documentation site (Jekyll + GitHub Pages)

---

## Completion Checklist

### Phase 1: Authentication & Access Control (P0) - COMPLETE

#### 1.1 Authentication Modes
- [x] Add `auth.mode` config option (open | basic | oauth)
- [x] Implement mode switching logic in Main.scala
- [x] Update application.conf with auth configuration block

#### 1.2 Basic Auth Implementation
- [x] Create `users` database table (id, username, password_hash, role, created_at)
- [x] Create `auth/AuthService.scala` - password hashing (PBKDF2-100k), validation
- [x] JWT token generation/validation (integrated in AuthService)
- [x] Create `routes/AuthRoutes.scala`:
  - [x] `POST /api/v1/auth/login` - authenticate, return JWT
  - [x] `POST /api/v1/auth/logout` - invalidate token
  - [x] `GET /api/v1/auth/me` - current user info
- [x] Create default admin user on first startup (if mode=basic)
- [x] Update AuthMiddleware to validate JWT tokens

#### 1.3 Access Tokens for External Ingesters
- [x] Create `access_tokens` database table
- [x] Create `routes/TokenRoutes.scala`:
  - [x] `POST /api/v1/tokens` - create token (admin only)
  - [x] `GET /api/v1/tokens` - list tokens (admin only)
  - [x] `DELETE /api/v1/tokens/{id}` - revoke token (admin only)
- [x] Implement token scopes
- [x] Add collection-scoped tokens (optional restriction)
- [x] Update AuthMiddleware to accept `rat_*` tokens
- [x] Track last_used_at for tokens

#### 1.4 Admin UI - Authentication
- [x] Create Login.vue page
- [x] Add authentication state to Pinia store
- [x] Implement JWT token storage and refresh
- [x] Create TokenManagement.vue (admin only)
- [x] Add logout functionality
- [x] Protect routes requiring authentication

#### 1.5 OAuth2/OIDC
- [x] Support OAuth2 authorization code flow
- [x] Map OAuth claims to principals
- [x] Support: Google, Azure AD, Okta, Keycloak

#### 1.6 Tests
- [x] Unit tests for AuthService (password hashing, validation)
- [x] Integration tests for auth endpoints (login, logout, me)
- [x] Integration tests for access token endpoints
- [x] Unit tests for OIDC token validation

#### 1.7 Documentation
- [x] Auth configuration guide (open/basic/oauth modes) - docs/guide/authentication.md
- [x] Access token guide for external ingesters - docs/guide/access-tokens.md
- [x] OAuth configuration guide - docs/oauth-configuration.md
- [x] OAuth security guide - docs/oauth-security.md

---

### Phase 2: External Ingester API (P0) - PARTIAL

#### 2.1 Enhanced Sync API
- [ ] Add `?include=hash,updatedAt` parameter to `GET /sync/documents`
- [ ] Add `?since=timestamp` parameter for incremental sync
- [ ] Add `GET /api/v1/sync/documents/{id}` - single document state
- [ ] Add `POST /api/v1/sync/check` - batch check document states

#### 2.2 Deleted Document Detection
- [ ] Add `dryRun` parameter to `POST /api/v1/sync`
- [ ] Return `wouldDelete` list when dryRun=true
- [ ] Add batch delete: `DELETE /api/v1/documents` with body

#### 2.3 API Documentation
- [x] Create `docs/guide/external-ingesters.md` guide
- [ ] Document full sync pattern with curl examples
- [ ] Document incremental sync pattern

#### 2.4 Tests
- [ ] Integration tests for enhanced sync endpoints
- [ ] E2E test: full sync workflow (list -> upsert -> prune)

---

### Phase 3: Built-in Ingesters (P1) - MOSTLY COMPLETE

#### 3.1 Directory Ingester
- [x] Directory ingestion with pattern matching
- [x] Recursive directory scanning
- [x] Scheduled ingestion (cron-like scheduling)
- [ ] Add `watch: true` option for real-time file watching (NIO WatchService)
- [ ] Add debouncing for rapid file changes

#### 3.2 S3/Cloud Storage Ingester - IN PROGRESS (feature/s3-ingester branch)
- [ ] Add S3 client dependency (AWS SDK)
- [ ] Create S3IngestionSource.scala
- [ ] Support bucket, prefix, region, patterns configuration
- [ ] Implement incremental sync via S3 object metadata
- [ ] Add GCS and Azure Blob support (future)

#### 3.3 Web Crawler - COMPLETE
- [x] Create WebCrawlerSource.scala
- [x] Implement breadth-first crawling from seed URLs
- [x] Support max-depth, follow-patterns, exclude-patterns
- [x] Add robots.txt compliance
- [x] Implement rate limiting (delay-ms)
- [x] Extract and clean HTML content

#### 3.4 Enterprise Connectors (Future)
- [ ] Confluence connector
- [ ] SharePoint connector
- [ ] Google Drive connector

#### 3.5 Documentation
- [x] Directory ingestion configuration guide
- [ ] S3/cloud storage setup guide (pending S3 feature)
- [x] Web crawler configuration (in IngestionConfig)

---

### Phase 4: RAGA Evaluation & Optimization (P1) - PARTIAL

#### 4.1 Query Metrics Collection
- [x] Create `query_logs` database table
- [x] Instrument RAGService to log queries
- [x] Track latency, tokens, chunks
- [x] Add `GET /api/v1/analytics/queries` endpoint
- [x] Add `GET /api/v1/analytics/queries/summary` endpoint

#### 4.2 Relevance Feedback System
- [ ] Create `query_feedback` database table
- [ ] Add `POST /api/v1/feedback` endpoint
- [ ] Support rating (1-5), relevant_chunks, comment

#### 4.3 Analytics Dashboard
- [x] Create Analytics.vue in admin UI
- [x] Display query metrics
- [ ] Display query latency distribution (p50/p95/p99)
- [ ] Show retrieval precision metrics
- [ ] List top failed queries

#### 4.4 Optimization Suggestions
- [ ] Implement suggestion engine based on metrics

#### 4.5 Documentation
- [x] Analytics API reference - docs/api/analytics.md
- [ ] Query metrics reference (what's tracked, how to interpret)
- [ ] Optimization tuning guide

---

### Phase 5: User Experience (P1) - PARTIAL

#### 5.1 Chat Interface
- [x] Create Chat.vue in admin UI
- [x] Implement query input
- [x] Display source citations
- [x] Add collection selector dropdown
- [ ] Implement streaming response (SSE)
- [ ] Add conversation history (session-based)
- [ ] Add feedback buttons (thumbs up/down)

#### 5.2 Permission Management UI
- [x] Create PrincipalManagement.vue (users/groups)
- [x] Create collection permissions management
- [ ] Visual permission inheritance display

#### 5.3 Ingestion Dashboard
- [x] Real-time ingestion progress (WebSocket)
- [x] Source status indicators
- [x] Manual trigger buttons per source
- [ ] Ingestion history with drill-down

---

### Phase 6: Production Readiness (P2) - MOSTLY COMPLETE

#### 6.1 Security Hardening
- [x] API rate limiting (per-token, per-IP)
- [x] Request size limits (10MB default)
- [x] CORS configuration
- [ ] TLS/SSL configuration guide
- [ ] Audit logging (who did what when)
- [ ] Secrets management (Vault integration)

#### 6.2 Observability
- [x] Structured logging (JSON format with logback)
- [x] Prometheus metrics endpoint
- [ ] Distributed tracing (OpenTelemetry)
- [ ] Grafana dashboard templates
- [ ] Alerting rules (Prometheus AlertManager)

#### 6.3 Deployment
- [x] Docker images (multi-platform: amd64, arm64)
- [x] Kubernetes Helm charts
- [x] Health check endpoints (/health, /health/ready)
- [x] Graceful shutdown handling
- [ ] Official Docker images on Docker Hub
- [ ] Database migration tooling (Flyway)

#### 6.4 High Availability
- [x] Database connection pooling (HikariCP)
- [ ] Multi-instance support (stateless design)
- [ ] Cache layer (Redis for sessions, embeddings)
- [ ] Load balancer configuration guide

#### 6.5 Documentation
- [x] Production deployment guide - docs/production-deployment.md
- [x] Kubernetes/Helm deployment guide - docs/guide/helm-deployment.md
- [x] Troubleshooting guide - docs/troubleshooting.md
- [ ] Security hardening checklist
- [ ] Monitoring and alerting setup

---

### Phase 7: Documentation Site (P0) - MOSTLY COMPLETE

#### 7.1 Site Setup (Jekyll + GitHub Pages)
- [x] Create `docs/` directory structure
- [x] Add Jekyll configuration (`_config.yml` with just-the-docs theme)
- [x] Add Gemfile for Jekyll dependencies
- [x] Configure GitHub Actions for automatic deployment
- [x] Enable search functionality
- [x] Add "Edit on GitHub" links
- [ ] Set up custom domain (optional: docs.raginabox.dev)
- [ ] Add site logo and branding

#### 7.2 Getting Started
- [x] Quick start guide - docs/getting-started/quickstart.md
- [ ] Docker installation guide (detailed)
- [ ] First document ingestion tutorial
- [ ] First query tutorial

#### 7.3 User Guide
- [x] Configuration guide - docs/guide/configuration.md
- [x] Authentication setup - docs/guide/authentication.md
- [x] Permission system guide - docs/guide/permissions.md
- [x] Chunking strategies guide - docs/guide/chunking.md
- [x] Analytics guide - docs/guide/analytics.md
- [ ] Admin UI walkthrough

#### 7.4 API Reference
- [x] Document endpoints reference - docs/api/documents.md
- [x] Sync endpoints reference - docs/api/sync.md
- [x] Query endpoints reference - docs/api/query.md
- [x] Auth endpoints reference - docs/api/auth.md
- [x] Token endpoints reference - docs/api/tokens.md
- [x] Config endpoints reference - docs/api/config.md
- [x] Health endpoints reference - docs/api/health.md
- [x] Analytics endpoints reference - docs/api/analytics.md
- [ ] OpenAPI spec integration

#### 7.5 Ingestion Guides
- [x] External ingester guide - docs/guide/external-ingesters.md
- [ ] Built-in directory ingester guide
- [ ] S3/cloud storage ingester guide
- [ ] Web crawler guide
- [ ] Writing custom ingesters

#### 7.6 Advanced Topics
- [ ] Performance tuning guide
- [ ] Scaling and high availability
- [ ] RAGA evaluation and optimization
- [ ] Monitoring with Prometheus/Grafana
- [ ] Backup and recovery

---

## Quick Wins (Remaining)

These can be implemented quickly with high impact:

- [ ] Add `?include=hash,updatedAt` to sync/documents endpoint
- [ ] Add `POST /sync/check` for batch document state checks
- [ ] Add `dryRun` option to prune endpoint
- [ ] Complete S3 ingester (in progress on feature branch)
- [ ] Add streaming responses for chat interface (SSE)

---

## Roadmap Summary

| Phase | Priority | Status | Key Remaining Work |
|-------|----------|--------|-------------------|
| **1. Auth** | P0 | COMPLETE | - |
| **2. Ingester API** | P0 | PARTIAL | Enhanced sync endpoints |
| **3. Built-in Ingesters** | P1 | MOSTLY COMPLETE | S3 (in progress), file watching |
| **4. RAGA Eval** | P1 | PARTIAL | Feedback system, optimization suggestions |
| **5. UX** | P1 | PARTIAL | Streaming chat, conversation history |
| **6. Production** | P2 | MOSTLY COMPLETE | Audit logging, distributed tracing |
| **7. Documentation Site** | P0 | MOSTLY COMPLETE | Tutorials, advanced guides |

---

## Decisions Made

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Auth approach | Open + Basic + OAuth | Full flexibility from simple to enterprise |
| Multi-tenancy | Collection-based isolation | Leverages existing permission system |
| SDK approach | REST API docs only | Users can use any HTTP client |
| Licensing | Fully open source | Community-first approach, no enterprise paywall |
| Documentation | Jekyll + GitHub Pages | Same stack as llm4s, easy to maintain |
| Testing | Unit + Integration + E2E | Comprehensive coverage for all features |

---

## Testing Strategy

Each phase includes comprehensive testing:

| Test Type | Scope | Tools |
|-----------|-------|-------|
| **Unit Tests** | Individual functions, services | ScalaTest |
| **Integration Tests** | API endpoints, database operations | ScalaTest, Testcontainers (PostgreSQL) |
| **E2E Tests** | Full user workflows | Playwright (Admin UI), HTTP client (API) |
| **Component Tests** | Vue components in isolation | Vitest, Vue Test Utils |
| **Load Tests** | Performance under load | Gatling or k6 |

**Test Requirements:**
- All new features must have corresponding tests before merge
- Integration tests run against real PostgreSQL (via Testcontainers)
- E2E tests run in CI on every PR
- Coverage reports generated and tracked (minimum 20%)

---

## Auth Mode Migration

**Migrating from `open` -> `basic` -> `oauth` is straightforward:**

1. **open -> basic**:
   - Set `auth.mode = "basic"` and `ADMIN_PASSWORD`
   - Restart server - admin user is auto-created
   - Existing documents and collections remain unchanged
   - API keys and access tokens start being enforced

2. **basic -> oauth**:
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

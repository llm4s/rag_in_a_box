---
layout: page
title: API Reference
nav_order: 4
has_children: true
---

# API Reference

Complete REST API documentation for RAG in a Box.
{: .fs-6 .fw-300 }

## Base URL

```
http://localhost:8080/api/v1
```

## Authentication

When authentication is enabled, include the API key or access token in requests:

```bash
# API Key (basic mode)
curl -H "X-API-Key: your-api-key" ...

# Access Token (for external ingesters)
curl -H "Authorization: Bearer rat_abc123..." ...
```

## Endpoints

| Category | Endpoint | Description |
|----------|----------|-------------|
| **Auth** | [POST /auth/login](/api/auth#login) | Login and get JWT token |
| | [GET /auth/me](/api/auth#get-current-user) | Get current user info |
| | [PUT /auth/password](/api/auth#change-password) | Change password |
| **Users** | [POST /users](/api/auth#create-user) | Create user (admin) |
| | [GET /users](/api/auth#list-users) | List users (admin) |
| | [DELETE /users/:id](/api/auth#delete-user) | Delete user (admin) |
| **Tokens** | [POST /tokens](/api/tokens#create-token) | Create access token (admin) |
| | [GET /tokens](/api/tokens#list-tokens) | List tokens (admin) |
| | [GET /tokens/:id](/api/tokens#get-token) | Get token details (admin) |
| | [DELETE /tokens/:id](/api/tokens#revoke-token) | Revoke token (admin) |
| **Documents** | [POST /documents](/api/documents#upload) | Upload a document |
| | [PUT /documents/:id](/api/documents#upsert) | Upsert document (idempotent) |
| | [GET /documents](/api/documents#list) | List documents |
| | [DELETE /documents/:id](/api/documents#delete) | Delete document |
| **Query** | [POST /query](/api/query#query) | Search and generate answer |
| | [POST /search](/api/query#search) | Search only (no LLM) |
| **Sync** | [GET /sync/documents](/api/sync#list) | List synced document states |
| | [POST /sync/check](/api/sync#check) | Batch check document states |
| | [POST /sync](/api/sync#prune) | Mark sync complete / prune |
| **Analytics** | [GET /analytics/queries](/api/analytics#list) | List query logs |
| | [GET /analytics/queries/summary](/api/analytics#summary) | Analytics summary |
| | [POST /feedback](/api/analytics#feedback) | Submit query feedback |
| **Health** | [GET /health](/api/health#health) | Health check |
| | [GET /health/ready](/api/health#ready) | Readiness check |
| **Config** | [GET /config](/api/config#get) | Get configuration |
| | [GET /stats](/api/config#stats) | Get statistics |

## Response Format

All responses are JSON. Successful responses include the requested data. Error responses follow this format:

```json
{
  "error": "error_type",
  "message": "Human-readable message",
  "details": "Optional additional details"
}
```

## Error Codes

| Status | Error Type | Description |
|--------|------------|-------------|
| 400 | `bad_request` | Invalid request format or parameters |
| 401 | `unauthorized` | Missing or invalid authentication |
| 403 | `forbidden` | Insufficient permissions |
| 404 | `not_found` | Resource not found |
| 500 | `internal_error` | Server error |

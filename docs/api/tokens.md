---
layout: page
title: Tokens API
parent: API Reference
nav_order: 2
---

# Tokens API
{: .no_toc }

Access token management endpoints for external systems and ingesters.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Create Token

Create a new access token. Requires admin role.

```
POST /api/v1/tokens
```

**Auth:** Admin (admin user, `API_KEY`, or an access token with the `admin` scope).

### Request Body

```json
{
  "name": "confluence-ingester",
  "scopes": ["documents:write", "sync:read", "sync:write"],
  "collections": ["confluence"],
  "expiresAt": "2024-12-31T23:59:59Z"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | Yes | Descriptive name for the token |
| `scopes` | array | Yes | Permission scopes (see below) |
| `collections` | array | No | Restrict the token to these collections and their descendants (`confluence` also covers `confluence/ENG`). Omit for all collections |
| `expiresAt` | string | No | ISO-8601 instant, e.g. `2026-12-31T23:59:59Z`. Omit for a token that never expires |

### Available Scopes

| Scope | Description |
|-------|-------------|
| `documents:read` | List and read documents, collections and stats |
| `documents:write` | Create, upload, upsert and delete documents (not `?all=true`) |
| `sync:read` | `GET /sync/status`, `GET /sync/documents[/{id}]`, `POST /sync/check` |
| `sync:write` | `POST /sync` (mark complete, prune) |
| `query` | `/query`, `/query/stream`, `/search` and `/chat/**` |
| `admin` | Every scope, and administrator rights on every endpoint |

A token without the `admin` scope can only reach the document, collection list, stats, sync,
query/search, chat, `/auth/me` and `/ws` endpoints (see [Authorization](/api/#authorization)).

### Response

**201 Created**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "confluence-ingester",
  "token": "rat_dGVzdC10b2tlbi1oZXJlLWZvci1leGFtcGxl",
  "scopes": ["documents:write", "sync:read", "sync:write"],
  "collections": ["confluence"],
  "expiresAt": "2024-12-31T23:59:59Z"
}
```

{: .warning }
The `token` field is only returned once at creation time. Store it securely!

**400 Bad Request**
```json
{
  "error": "bad_request",
  "message": "Invalid scopes: unknown:scope"
}
```

---

## List Tokens

List all access tokens. Requires admin role.

```
GET /api/v1/tokens
```

**Auth:** Admin (admin user, `API_KEY`, or an access token with the `admin` scope).

### Response

**200 OK**
```json
{
  "tokens": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "name": "confluence-ingester",
      "tokenPrefix": "rat_dGVzdC10",
      "scopes": ["documents:write", "sync:read", "sync:write"],
      "collections": ["confluence"],
      "expiresAt": "2024-12-31T23:59:59Z",
      "lastUsedAt": "2024-01-15T10:30:00Z",
      "createdAt": "2024-01-01T00:00:00Z"
    }
  ],
  "total": 1
}
```

Note: The full token value is never returned after creation. Only the `tokenPrefix` (the first 12 characters, `rat_` plus 8) is shown for identification. Tokens are stored as hashes.

---

## Get Token

Get details about a specific token. Requires admin role.

```
GET /api/v1/tokens/{id}
```

**Auth:** Admin (admin user, `API_KEY`, or an access token with the `admin` scope).

### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | string | Token UUID |

### Response

**200 OK**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "confluence-ingester",
  "tokenPrefix": "rat_dGVzdC10",
  "scopes": ["documents:write", "sync:read", "sync:write"],
  "collections": ["confluence"],
  "expiresAt": "2024-12-31T23:59:59Z",
  "lastUsedAt": "2024-01-15T10:30:00Z",
  "createdAt": "2024-01-01T00:00:00Z"
}
```

**404 Not Found**
```json
{
  "error": "not_found",
  "message": "Token 550e8400-e29b-41d4-a716-446655440000 not found"
}
```

---

## Revoke Token

Delete an access token immediately. Requires admin role.

```
DELETE /api/v1/tokens/{id}
```

**Auth:** Admin (admin user, `API_KEY`, or an access token with the `admin` scope).

### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | string | Token UUID |

### Response

**204 No Content**

Token is immediately revoked. Any requests using this token will fail.

**404 Not Found**
```json
{
  "error": "not_found",
  "message": "Token 550e8400-e29b-41d4-a716-446655440000 not found"
}
```

---

## Using Access Tokens

Access tokens are used in the `Authorization` header:

```bash
curl -X PUT "http://localhost:8080/api/v1/documents/doc-123" \
  -H "Authorization: Bearer rat_dGVzdC10b2tlbi1oZXJlLWZvci1leGFtcGxl" \
  -H "Content-Type: application/json" \
  -d '{"content": "Document content..."}'
```

Access tokens work in every auth mode (also in `open` mode once an `API_KEY` is set). On the
WebSocket endpoint only, the token may also be passed as `?access_token=`.

### Token Authentication Errors

**401 Unauthorized**: the token is unknown, revoked or expired

```json
{
  "error": "unauthorized",
  "message": "Invalid, revoked or expired access token"
}
```

**403 Forbidden**: the token lacks the required scope

```json
{
  "error": "forbidden",
  "message": "Access token is missing the 'documents:write' scope"
}
```

**403 Forbidden**: the endpoint needs the `admin` scope

```json
{
  "error": "forbidden",
  "message": "Access token needs the 'admin' scope for this endpoint"
}
```

**403 Forbidden**: the collection (or query pattern) is outside the token's restriction

```json
{
  "error": "forbidden",
  "message": "Not allowed to use collection 'sharepoint/HR'"
}
```

For queries the message is `Access token is restricted to collections ...; pattern '...' is not within them`.

A collection-restricted token cannot query all collections (`*`): pass a `collection` inside
its restriction (e.g. `confluence` or `confluence/**`).

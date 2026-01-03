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

### Headers

```
Authorization: Bearer <admin-jwt-token>
```

### Request Body

```json
{
  "name": "confluence-ingester",
  "scopes": ["documents:write", "sync:read", "sync:write"],
  "collections": ["confluence/*"],
  "expiresAt": "2024-12-31T23:59:59Z"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | Yes | Descriptive name for the token |
| `scopes` | array | Yes | Permission scopes (see below) |
| `collections` | array | No | Restrict access to specific collections |
| `expiresAt` | string | No | ISO-8601 expiration timestamp |

### Available Scopes

| Scope | Description |
|-------|-------------|
| `documents:read` | Read document metadata |
| `documents:write` | Create, update, delete documents |
| `sync:read` | Read sync status and document list |
| `sync:write` | Mark sync complete, prune documents |
| `query` | Execute RAG queries |
| `admin` | Full administrative access |

### Response

**201 Created**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "confluence-ingester",
  "token": "rat_dGVzdC10b2tlbi1oZXJlLWZvci1leGFtcGxl",
  "scopes": ["documents:write", "sync:read", "sync:write"],
  "collections": ["confluence/*"],
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

### Headers

```
Authorization: Bearer <admin-jwt-token>
```

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
      "collections": ["confluence/*"],
      "expiresAt": "2024-12-31T23:59:59Z",
      "lastUsedAt": "2024-01-15T10:30:00Z",
      "createdAt": "2024-01-01T00:00:00Z"
    }
  ],
  "total": 1
}
```

Note: The full token value is never returned after creation. Only the `tokenPrefix` (first 12 characters) is shown for identification.

---

## Get Token

Get details about a specific token. Requires admin role.

```
GET /api/v1/tokens/{id}
```

### Headers

```
Authorization: Bearer <admin-jwt-token>
```

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
  "collections": ["confluence/*"],
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

### Headers

```
Authorization: Bearer <admin-jwt-token>
```

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

### Token Authentication Errors

**401 Unauthorized** - Token missing or invalid
```json
{
  "error": "unauthorized",
  "message": "Invalid token"
}
```

**401 Unauthorized** - Token expired
```json
{
  "error": "unauthorized",
  "message": "Token expired"
}
```

**403 Forbidden** - Token lacks required scope
```json
{
  "error": "forbidden",
  "message": "Token does not have scope: documents:write"
}
```

**403 Forbidden** - Token restricted to different collection
```json
{
  "error": "forbidden",
  "message": "Token not authorized for collection: sharepoint/HR"
}
```

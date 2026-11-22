---
layout: page
title: Permissions
parent: User Guide
nav_order: 7
---

# Permissions Guide

Configure fine-grained access control for collections and documents.
{: .fs-6 .fw-300 }

## Overview

RAG in a Box supports permission-based access control through:

- **Principals**: Users and groups that can be granted access
- **Collections**: Hierarchical organization of documents
- **Permissions**: Control which principals can query which collections

When permissions are enabled, search results are automatically filtered to only include documents the user has access to.

Permission mode needs the PostgreSQL search index with its permission schema (llm4s
`PgSearchIndex`). It is initialised at startup; if that fails the server logs a warning and runs
without permissions (queries ignore them and the `/principals` and collection-permission
endpoints are not mounted). Set `PERMISSIONS_REQUIRED=true` to make startup fail instead.

{: .note }
Managing principals, creating and deleting collections and changing their permissions are
administrator operations. The examples below omit credentials for brevity (they work as-is only
in open mode without `API_KEY`); otherwise add e.g. `-H "X-API-Key: $API_KEY"` or
`-H "Authorization: Bearer <admin token>"`. See [Authentication](authentication.md).

---

## Principals

Principals represent identities that can be granted access. There are two types:

### Users

Individual user identities, typically representing people.

```bash
# Create a user principal
curl -X POST http://localhost:8080/api/v1/principals/users \
  -H "Content-Type: application/json" \
  -d '{"externalId": "alice@example.com"}'
```

### Groups

Collections of users for easier permission management.

```bash
# Create a group principal
curl -X POST http://localhost:8080/api/v1/principals/groups \
  -H "Content-Type: application/json" \
  -d '{"externalId": "engineering"}'
```

The response (`201 Created`) is `{"id": 1, "externalId": "user:alice@example.com", "principalType": "user"}`.

### External ID Format

Principals use external IDs with a type prefix:
- Users: `user:alice@example.com`
- Groups: `group:engineering`

`queryableBy` lists take these prefixed IDs; principals that do not exist yet are created on the
fly.

---

## Collections

Collections organize documents hierarchically and define access permissions.

### Creating Collections

```bash
# Create a public collection
curl -X POST http://localhost:8080/api/v1/collections \
  -H "Content-Type: application/json" \
  -d '{
    "path": "docs/public",
    "isLeaf": true
  }'

# Create a restricted collection
curl -X POST http://localhost:8080/api/v1/collections \
  -H "Content-Type: application/json" \
  -d '{
    "path": "docs/internal",
    "queryableBy": ["user:alice@example.com", "group:engineering"],
    "isLeaf": true
  }'
```

### Collection Properties

| Property | Type | Description |
|----------|------|-------------|
| `path` | string | Hierarchical path (e.g., `docs/guides/api`) |
| `description` | string | Optional; stored in the collection's metadata as `description` |
| `queryableBy` | array | List of principal external IDs (optional; omitted = public) |
| `isLeaf` | boolean | Whether collection holds documents directly (default `true`) |
| `metadata` | object | Additional string key-value metadata |

The response (`201 Created`) describes the collection; `queryableBy` comes back as internal
principal ids:

```json
{
  "id": 3,
  "path": "docs/internal",
  "parentPath": "docs",
  "queryableBy": [1, 2],
  "isLeaf": true,
  "isPublic": false,
  "metadata": {}
}
```

### Hierarchical Paths

Collections can be nested using `/` as a separator:

```
company/
├── docs/
│   ├── public/        # Public documentation
│   └── internal/      # Internal-only docs
└── reports/
    ├── quarterly/     # Quarterly reports
    └── confidential/  # Executive reports
```

---

## Managing Permissions

### Setting Permissions on Creation

```bash
curl -X POST http://localhost:8080/api/v1/collections \
  -H "Content-Type: application/json" \
  -d '{
    "path": "finance/reports",
    "queryableBy": [
      "user:cfo@example.com",
      "group:finance-team",
      "group:executives"
    ]
  }'
```

### Updating Permissions

The `PUT .../permissions`, `DELETE` and `.../stats` routes take the collection path as a single
URL segment, so encode the `/` in nested paths as `%2F`:

```bash
curl -X PUT http://localhost:8080/api/v1/collections/finance%2Freports/permissions \
  -H "Content-Type: application/json" \
  -d '{
    "queryableBy": [
      "user:cfo@example.com",
      "group:finance-team"
    ]
  }'
```

### Public Collections

Collections without `queryableBy` restrictions are public (accessible to all authenticated users).

---

## Querying with Permissions

### Who the query runs as

The permission identity always comes from the **authenticated principal** (see
[Authentication](authentication.md)); it is never taken from request headers by default.

| Principal | Permission identity |
|-----------|---------------------|
| Administrator (admin user, `API_KEY`, admin-scoped token, open mode) | sees every collection |
| Password user (basic mode) | user principal `user:<username>` |
| SSO user (oauth mode) | user principal from the configured user-id claim, plus the groups claim |
| Access token | sees every collection within the token's collection restriction (its scopes and `collections` limit it, not `queryableBy`) |

```bash
curl -X POST http://localhost:8080/api/v1/query \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <alice's JWT>" \
  -d '{"question": "What are our deployment procedures?", "collection": "docs/**"}'
```

### Trusted proxy headers

If an authenticating gateway sits in front of RAG in a Box, it can act for its end users with
`X-User-Id` and `X-Group-Ids`. This is **off by default**: without it these headers are stripped
from every request and ignored.

```bash
export AUTH_TRUSTED_PROXY_HEADERS=true   # security.auth.trusted-proxy-headers
```

With it on, a request authenticated as an **administrator** (normally the gateway's `API_KEY`)
that carries `X-User-Id` is treated as that (non-admin) user: queries are filtered by the user's
and groups' permissions, and chat sessions belong to that user. Non-admin credentials can never
assert another identity. `X-Admin: true` additionally keeps administrator rights, and only if
`ALLOW_ADMIN_HEADER=true`.

```bash
curl -X POST http://localhost:8080/api/v1/query \
  -H "X-API-Key: $API_KEY" \
  -H "X-User-Id: alice@example.com" \
  -H "X-Group-Ids: engineering,developers" \
  -H "Content-Type: application/json" \
  -d '{"question": "What are our deployment procedures?"}'
```

{: .warning }
Only enable trusted proxy headers when every request reaches the server through a gateway that
sets (and overwrites) these headers.

### What non-admin users can see and do

- Queries, search and chat only retrieve from collections the user may query.
- `GET /api/v1/collections`, `GET /api/v1/collections/{path}` and `/collections/accessible`
  only show those collections; others answer `404`.
- Document listings and details only include documents in those collections, and documents can
  only be created, changed or deleted there (`403` otherwise).
- Administrative endpoints answer `403`: creating/deleting collections and changing their
  permissions, principals, runtime and collection configuration, experiments, ingestion,
  `DELETE /api/v1/documents?all=true`, users, tokens, the sync API, `/visibility/*` and
  `/analytics/*` (query text of every user).
- Chat sessions belong to the user who created them (see below).

### Chat sessions

Every chat session has an owner (`ownerId`, the creating principal's id, e.g. `user:alice`).
Only the owner and administrators can list, read, rename, archive, delete or post to it; other
users get `404`. Sessions created before owners were recorded have no owner and are visible to
administrators only. Chat messages are validated like queries (non-empty, length limit).

### Collection Patterns in Queries

| Pattern | Description |
|---------|-------------|
| `*` | All accessible collections |
| `docs` | Exact match |
| `docs/*` | Direct children of `docs` |
| `docs/**` | All descendants of `docs` |

A malformed pattern is rejected with `400 Bad Request`; it is never widened to `*`.

---

## Assigning Documents to Collections

When uploading documents, specify the collection:

```bash
curl -X POST http://localhost:8080/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Deployment procedures...",
    "filename": "deployment.md",
    "collection": "docs/internal"
  }'
```

Or via upsert:

```bash
curl -X PUT http://localhost:8080/api/v1/documents/deploy-guide \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Deployment procedures...",
    "collection": "docs/internal"
  }'
```

A collection path may use only letters, digits, `_` and `-` in each `/`-separated segment; an
invalid one is rejected with `400 Bad Request`.

### The default collection

A document uploaded without a `collection` - and every document from a configured ingestion
source, unless its `metadata` sets `collection` - goes into the `default` collection. It is
public (queryable by everyone) and is created at startup if it does not exist. At the same
startup, vectors stored without a collection (for example by a server that previously ran
without permissions) are moved into it, so they become searchable.

### Moving documents and changing access

Upserting a document with a different `collection` moves it: its vectors are removed from the
old collection. Upserting with a different `readableBy` re-applies the access list even when
the content is unchanged.

---

## OAuth Integration

When using OAuth authentication, group memberships can be automatically extracted from OIDC claims:

```bash
# Configure claim mapping
OAUTH_CLAIM_USER_ID=sub
OAUTH_CLAIM_EMAIL=email
OAUTH_CLAIM_GROUPS=groups
OAUTH_CLAIM_NAME=name
```

Groups from the identity provider are automatically mapped to principals and used for
permission checks on every request made with the SSO session.

---

## API Reference

### Principals

All principal endpoints are admin-only.

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/principals/users` | POST | Create (or get) a user principal: `{"externalId": "alice"}` |
| `/api/v1/principals/groups` | POST | Create (or get) a group principal |
| `/api/v1/principals/users?limit=&offset=` | GET | List user principals |
| `/api/v1/principals/groups?limit=&offset=` | GET | List group principals |
| `/api/v1/principals/lookup/{externalId}` | GET | Look up by external ID (e.g. `user:alice`) |
| `/api/v1/principals/{type}/{externalId}` | DELETE | Delete a principal (`type` is `user(s)` or `group(s)`; `204`) |

### Collections

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/api/v1/collections` | POST | admin | Create collection |
| `/api/v1/collections` | GET | any (`documents:read` for tokens) | Names of the collections that hold documents and that the caller may see: `{"collections": ["default", "docs/internal"]}` |
| `/api/v1/collections/accessible?pattern=` | GET | any | Collection objects the caller may query: `{"collections": [...]}` |
| `/api/v1/collections/{path}` | GET | any (hidden collections answer `404`) | Collection details (nested paths may be written with `/`) |
| `/api/v1/collections/{path}/stats` | GET | any (hidden collections answer `404`) | `{"path", "documentCount", "chunkCount"}` |
| `/api/v1/collections/{path}/permissions` | PUT | admin | Replace `queryableBy` |
| `/api/v1/collections/{path}` | DELETE | admin | Delete collection (`204`) |

The per-collection chunking configuration lives under `/api/v1/collections/{collection}/config`;
see [Chunking](chunking.md).

---

## Best Practices

### 1. Use Groups for Scalability

Instead of assigning permissions to individual users, use groups:

```bash
# Good: Use groups
"queryableBy": ["group:engineering", "group:product"]

# Avoid: Individual users (hard to maintain)
"queryableBy": ["user:alice", "user:bob", "user:charlie"]
```

### 2. Hierarchical Organization

Organize collections to match your organizational structure:

```
company/
├── public/           # No restrictions
├── internal/         # All employees
│   └── engineering/  # Engineering team
│       └── security/ # Security sub-team
└── confidential/     # Executives only
```

### 3. Principle of Least Privilege

Start with restrictive permissions and grant access as needed:

```bash
# Start with specific access
"queryableBy": ["group:project-alpha-team"]

# Expand if needed
"queryableBy": ["group:project-alpha-team", "group:engineering-leads"]
```

### 4. Admin Access for Debugging

Query as an administrator (the `API_KEY`, or an admin user) to bypass permission filtering;
`/api/v1/visibility/*` shows every chunk. To test what a user sees, log in as that user, or use
trusted proxy headers with the API key in a development setup.

---
layout: page
title: Permissions
parent: User Guide
nav_order: 5
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

### External ID Format

Principals use external IDs with a type prefix:
- Users: `user:alice@example.com`
- Groups: `group:engineering`

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
| `queryableBy` | array | List of principal external IDs |
| `isLeaf` | boolean | Whether collection holds documents directly |
| `metadata` | object | Additional key-value metadata |

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

```bash
curl -X PUT http://localhost:8080/api/v1/collections/finance/reports/permissions \
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

### Providing User Context

When querying, provide user identity via headers:

```bash
curl -X POST http://localhost:8080/api/v1/query \
  -H "Content-Type: application/json" \
  -H "X-User-Id: alice@example.com" \
  -H "X-Group-Ids: engineering,developers" \
  -d '{
    "question": "What are our deployment procedures?",
    "collection": "docs/**"
  }'
```

### Permission Headers

| Header | Description |
|--------|-------------|
| `X-User-Id` | User's external ID |
| `X-Group-Ids` | Comma-separated list of group memberships |
| `X-Admin` | Set to `true` for admin access (if enabled) |

### Collection Patterns in Queries

| Pattern | Description |
|---------|-------------|
| `*` | All accessible collections |
| `docs` | Exact match |
| `docs/*` | Direct children of `docs` |
| `docs/**` | All descendants of `docs` |

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

Groups from the identity provider are automatically mapped to principals.

---

## API Reference

### Principals

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/principals/users` | POST | Create user principal |
| `/api/v1/principals/groups` | POST | Create group principal |
| `/api/v1/principals/users` | GET | List users |
| `/api/v1/principals/groups` | GET | List groups |
| `/api/v1/principals/lookup/:id` | GET | Lookup by external ID |
| `/api/v1/principals/:type/:id` | DELETE | Delete principal |

### Collections

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/collections` | POST | Create collection |
| `/api/v1/collections` | GET | List collections |
| `/api/v1/collections/accessible` | GET | List accessible to current user |
| `/api/v1/collections/:path` | GET | Get collection details |
| `/api/v1/collections/:path/permissions` | PUT | Update permissions |
| `/api/v1/collections/:path` | DELETE | Delete collection |

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

Enable admin header bypass only in development:

```bash
# Development only!
ALLOW_ADMIN_HEADER=true
```

Then use `X-Admin: true` header to bypass permission checks.

---
layout: page
title: Permissions API
parent: API Reference
nav_order: 13
---

# Permissions API
{: .no_toc }

Principals (users and groups) and hierarchical collections with `queryableBy` permissions,
backed by llm4s's `PrincipalStore` and `CollectionStore`. See the
[Permissions guide](/guide/permissions).
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

{: .note }
These endpoints exist only in **permission mode**: when the PostgreSQL permission search index
could be initialized (set `PERMISSIONS_REQUIRED=true` to fail startup otherwise). Without it
they answer `404`.

Principals are written as external ids: `user:alice@example.com` or `group:engineering`.

## Principals {#principals}

**Auth:** every `/api/v1/principals` endpoint is **Admin** only.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/principals/users` | Create (or get) a user principal. Body `{"externalId": "alice@example.com"}`. `201` |
| POST | `/api/v1/principals/groups` | Create (or get) a group principal. Body `{"externalId": "engineering"}`. `201` |
| GET | `/api/v1/principals/users?limit=100&offset=0` | List user principals |
| GET | `/api/v1/principals/groups?limit=100&offset=0` | List group principals |
| GET | `/api/v1/principals/lookup/{externalId}` | Look up e.g. `user:alice@example.com`; `404` when unknown, `400` for a malformed id |
| DELETE | `/api/v1/principals/{type}/{externalId}` | `type` is `users`/`user` or `groups`/`group`. `204` |

Create and lookup answer with a principal object; the list endpoints answer
`{"principals": [...], "total": n}`.

```json
{ "id": 7, "externalId": "user:alice@example.com", "principalType": "user" }
```

Principals are also created on demand when they appear in a document's `readableBy` or a
collection's `queryableBy`, and (in oauth mode) when users log in.

---

## Collections {#collections}

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/collections` | Admin | Create a collection |
| GET | `/api/v1/collections/accessible?pattern=*` | Authenticated (tokens: `admin`) | Collections the caller may query |
| GET | `/api/v1/collections/{path}` | Authenticated (tokens: `admin`) | One collection; `path` may contain slashes. `404` when it does not exist or the caller may not query it |
| GET | `/api/v1/collections/{path}/stats` | Authenticated (tokens: `admin`) | Document and chunk counts |
| PUT | `/api/v1/collections/{path}/permissions` | Admin | Replace `queryableBy` |
| DELETE | `/api/v1/collections/{path}` | Admin | Delete a collection. `204` |

`GET /api/v1/collections` itself is the [Documents API](/api/documents#collections) list of
collection names.

Creating a collection that already exists is a `409`; a malformed principal in `queryableBy` or
an invalid `?pattern=` is a `400`.

### Create

```json
{
  "path": "company/engineering",
  "description": "Engineering handbook",
  "queryableBy": ["group:engineering", "user:cto@example.com"],
  "isLeaf": true,
  "metadata": { "owner": "platform" }
}
```

`isLeaf` defaults to `true`; a collection with an empty `queryableBy` is public. `description`
is stored in the collection's metadata (as `description`). **201 Created** with the collection
object:

```json
{
  "id": 3,
  "path": "company/engineering",
  "parentPath": "company",
  "queryableBy": [4, 7],
  "isLeaf": true,
  "isPublic": false,
  "metadata": { "owner": "platform", "description": "Engineering handbook" }
}
```

`queryableBy` in responses holds internal principal ids.

### Update Permissions

```
PUT /api/v1/collections/{path}/permissions
```

```json
{ "queryableBy": ["group:engineering"] }
```

### Stats

```json
{ "path": "company/engineering", "documentCount": 42, "chunkCount": 610 }
```

{: .warning }
`PUT .../permissions`, `DELETE` and `.../stats` match a single path segment, so for nested
collections percent-encode the slash (`company%2Fengineering`).

---
layout: page
title: Sync API
parent: API Reference
nav_order: 6
---

# Sync API
{: .no_toc }

APIs for external ingesters and document synchronization.
{: .fs-6 .fw-300 }

**Auth:** the sync API is for ingesters: access tokens with `sync:read` (reads and
`POST /sync/check`) or `sync:write` (`POST /sync`), or administrators. Other users get `403`.
A token restricted to collections only sees, and only prunes, documents in those collections.
Documents are written with the [Documents API](/api/documents) (`documents:write`).

See the [External Ingesters guide](/guide/external-ingesters) for a walkthrough.

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## List Document States {#list}

Get the current state of all synced documents.

```
GET /api/v1/sync/documents
```

### Query Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `include` | string | Comma-separated extra fields: `hash`, `updatedAt`. Without it only `id` and `collection` are returned |
| `since` | ISO-8601 | Only documents updated after this instant, e.g. `2024-01-15T00:00:00Z` (a malformed value is a `400`) |
| `page` | integer | 1-based page (default 1) |
| `pageSize` | integer | Documents per page (default 1000, max 10000) |

The listing is paged. Without `page`/`pageSize` the first 1000 documents are returned, which is
every document of most installations; when `hasMore` is true, request the next `page`. `total`
counts every matching document. Documents are ordered by indexing time (by update time with
`since`), so pages are stable while nothing changes.

### Examples

```bash
# Get all document IDs
curl -H "Authorization: Bearer $RAGBOX_TOKEN" "http://localhost:8080/api/v1/sync/documents"

# Include hashes and timestamps
curl -H "Authorization: Bearer $RAGBOX_TOKEN" "http://localhost:8080/api/v1/sync/documents?include=hash,updatedAt"

# Get documents modified since a timestamp
curl -H "Authorization: Bearer $RAGBOX_TOKEN" "http://localhost:8080/api/v1/sync/documents?since=2024-01-15T00:00:00Z&include=hash"
```

### Response

```json
{
  "documents": [
    {
      "id": "doc-001",
      "hash": "sha256:abc123...",
      "updatedAt": "2024-01-15T10:30:00Z",
      "collection": "docs/guides"
    },
    {
      "id": "doc-002",
      "hash": "sha256:def456...",
      "updatedAt": "2024-01-14T09:00:00Z",
      "collection": "confluence"
    }
  ],
  "total": 2,
  "page": 1,
  "pageSize": 1000,
  "hasMore": false
}
```

---

## Get Single Document State {#get}

Get the state of a specific document.

```
GET /api/v1/sync/documents/:id
```

### Path Parameters

| Parameter | Description |
|-----------|-------------|
| `id` | Document ID |

### Example

```bash
curl -H "Authorization: Bearer $RAGBOX_TOKEN" "http://localhost:8080/api/v1/sync/documents/doc-001"
```

### Response

```json
{
  "id": "doc-001",
  "hash": "sha256:abc123...",
  "updatedAt": "2024-01-15T10:30:00Z",
  "collection": "docs/guides"
}
```

Returns `404 Not Found` if the document doesn't exist.

---

## Batch Check Document States {#check}

Check the state of multiple documents in one request.

```
POST /api/v1/sync/check
```

### Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `documentIds` | string[] | Yes | List of document IDs to check |

### Example

```bash
curl -X POST -H "Authorization: Bearer $RAGBOX_TOKEN" http://localhost:8080/api/v1/sync/check \
  -H "Content-Type: application/json" \
  -d '{
    "documentIds": ["doc-001", "doc-002", "doc-003", "doc-new"]
  }'
```

### Response

```json
{
  "found": [
    {
      "id": "doc-001",
      "hash": "sha256:abc123...",
      "updatedAt": "2024-01-15T10:30:00Z",
      "collection": "docs/guides"
    },
    {
      "id": "doc-002",
      "hash": "sha256:def456...",
      "updatedAt": "2024-01-14T09:00:00Z"
    }
  ],
  "missing": ["doc-003", "doc-new"]
}
```

---

## Get Sync Status {#status}

Get the overall sync status.

```
GET /api/v1/sync/status
```

### Example

```bash
curl -H "Authorization: Bearer $RAGBOX_TOKEN" "http://localhost:8080/api/v1/sync/status"
```

### Response

```json
{
  "lastSyncTime": "2024-01-15T10:30:00Z",
  "documentCount": 150,
  "chunkCount": 2500
}
```

---

## Mark Sync Complete / Prune {#prune}

Mark a sync operation as complete and optionally prune orphaned documents.

```
POST /api/v1/sync
```

Pruning is **scoped to one sync source**. Every document written with
`PUT /api/v1/documents/{id}` belongs to the source named by its `source` field, or to `api` when
it names none. A prune deletes the documents of the request's `source` (default `api`) that are
not in `keepDocumentIds`, and nothing else: uploads (`POST /documents`, `/documents/upload`),
documents of configured ingestion sources and other sources' documents are kept. A
collection-restricted token only prunes within its collections. Give each ingester its own
`source` when several share a server.

`"scope": "all"` prunes every document not listed, whatever its source - the behaviour before
#54. It is an explicit, admin-only opt-in (`403` for anyone else).

### Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `keepDocumentIds` | string[] | Yes, when a body is sent | Document IDs to keep; the other documents in scope are deleted |
| `dryRun` | boolean | No | If true, preview deletions without executing |
| `source` | string | No | The sync source whose documents are pruned (default `api`) |
| `scope` | string | No | `source` (default) or `all` (admin only; not together with `source`) |

### Examples

#### Mark sync complete (no pruning)

```bash
curl -X POST -H "Authorization: Bearer $RAGBOX_TOKEN" http://localhost:8080/api/v1/sync
```

Response:
```json
{
  "message": "Sync completed",
  "prunedCount": 0,
  "dryRun": false
}
```

#### Dry-run prune preview

```bash
curl -X POST -H "Authorization: Bearer $RAGBOX_TOKEN" http://localhost:8080/api/v1/sync \
  -H "Content-Type: application/json" \
  -d '{
    "keepDocumentIds": ["doc-001", "doc-003"],
    "dryRun": true
  }'
```

Response:
```json
{
  "message": "Dry run: would delete 2 documents",
  "prunedCount": 2,
  "prunedIds": ["doc-002", "doc-004"],
  "dryRun": true,
  "scope": "source",
  "source": "api"
}
```

#### Prune one named source

```bash
curl -X POST -H "Authorization: Bearer $RAGBOX_TOKEN" http://localhost:8080/api/v1/sync \
  -H "Content-Type: application/json" \
  -d '{"keepDocumentIds": ["confluence-1"], "source": "confluence"}'
```

#### Execute prune

```bash
curl -X POST -H "Authorization: Bearer $RAGBOX_TOKEN" http://localhost:8080/api/v1/sync \
  -H "Content-Type: application/json" \
  -d '{
    "keepDocumentIds": ["doc-001", "doc-003"]
  }'
```

Response:
```json
{
  "message": "Sync completed",
  "prunedCount": 2,
  "dryRun": false,
  "scope": "source",
  "source": "api"
}
```

### Errors

An empty body just marks the sync complete. A body that is present but is not
valid JSON or does not match the schema (e.g. a misspelled `keepDocumentIds`),
an unknown `scope`, `scope: all` together with a `source`, or an invalid `source`
(1-128 letters, digits or `._:/-`) returns `400` with an `ErrorResponse`;
`scope: all` from a non-admin returns `403`. Nothing is pruned and the sync is
not marked complete.

### Upgrading: `POST /sync` is scoped by source {#sync-scope-migration}

Before #54, `POST /api/v1/sync` with `keepDocumentIds` deleted **every** document not listed,
including uploads, other ingesters' documents and documents of configured ingestion sources.
It now prunes only the documents of one **sync source**:

- `PUT /api/v1/documents/{id}` records the document's source: the request's `source`, or `api`
  when it names none. Uploads (`POST /documents`, `/documents/upload`) belong to no source.
- `POST /api/v1/sync` prunes the documents of `source` (default `api`) that are not in
  `keepDocumentIds`. `"scope": "all"` restores the old behaviour, for administrators only.

What this means for an existing ingester:

- **One ingester, no changes.** Its documents become `api`'s as it re-sends them (an unchanged
  document is re-assigned without being re-indexed), and its prune keeps working. Documents it
  stopped sending *before* the upgrade, and has not re-sent since, still belong to no source and
  are no longer pruned; remove them once with an admin `{"keepDocumentIds": [...], "scope": "all",
  "dryRun": true}` (check the list first) or `DELETE /api/v1/documents`.
- **Several ingesters.** Give each its own `source` (e.g. `confluence`, `sharepoint`) on every
  `PUT` and on its `POST /sync`, so that one ingester's prune never deletes another's
  documents. The first sync after the switch must re-send *all* of the ingester's documents
  (do not skip unchanged ones that run), so that they move to the new source.
- Pick source names that differ from the names of configured ingestion sources
  (`INGEST_SOURCES`): those own their documents under their own names.


---

## Sync Workflow

A typical full sync workflow:

```
1. GET /sync/documents?include=hash
   → Get current document states

2. For each source document:
   PUT /documents/{id} {"source": "my-ingester", ...}
   → Upsert with contentHash, under your source

3. POST /sync {"keepDocumentIds": [...], "source": "my-ingester", "dryRun": true}
   → Preview what would be deleted (only your source's documents)

4. POST /sync {"keepDocumentIds": [...], "source": "my-ingester"}
   → Execute prune and mark sync complete
```

For incremental sync:

```
1. GET /sync/status
   → Get lastSyncTime

2. GET /sync/documents?since={lastSyncTime}&include=hash
   → Get documents modified since last sync

3. For changed source documents:
   PUT /documents/{id}
   → Upsert changed documents

4. POST /sync
   → Mark sync complete
```

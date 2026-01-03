---
layout: page
title: Sync API
parent: API Reference
nav_order: 3
---

# Sync API
{: .no_toc }

APIs for external ingesters and document synchronization.
{: .fs-6 .fw-300 }

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
| `include` | string | Comma-separated fields to include: `hash`, `updatedAt` |
| `since` | ISO-8601 | Only return documents modified after this timestamp |

### Examples

```bash
# Get all document IDs
curl "http://localhost:8080/api/v1/sync/documents"

# Include hashes and timestamps
curl "http://localhost:8080/api/v1/sync/documents?include=hash,updatedAt"

# Get documents modified since a timestamp
curl "http://localhost:8080/api/v1/sync/documents?since=2024-01-15T00:00:00Z&include=hash"
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
  "total": 2
}
```

---

## Get Single Document State

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
curl "http://localhost:8080/api/v1/sync/documents/doc-001"
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
curl -X POST http://localhost:8080/api/v1/sync/check \
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

## Get Sync Status

Get the overall sync status.

```
GET /api/v1/sync/status
```

### Example

```bash
curl "http://localhost:8080/api/v1/sync/status"
```

### Response

```json
{
  "lastSyncTime": "2024-01-15T10:30:00Z",
  "documentCount": 150,
  "chunkCount": 2500,
  "pendingDeletes": 0
}
```

---

## Mark Sync Complete / Prune {#prune}

Mark a sync operation as complete and optionally prune orphaned documents.

```
POST /api/v1/sync
```

### Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `keepDocumentIds` | string[] | No | Document IDs to keep (others will be deleted) |
| `dryRun` | boolean | No | If true, preview deletions without executing |

### Examples

#### Mark sync complete (no pruning)

```bash
curl -X POST http://localhost:8080/api/v1/sync
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
curl -X POST http://localhost:8080/api/v1/sync \
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
  "dryRun": true
}
```

#### Execute prune

```bash
curl -X POST http://localhost:8080/api/v1/sync \
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
  "dryRun": false
}
```

---

## Sync Workflow

A typical full sync workflow:

```
1. GET /sync/documents?include=hash
   → Get current document states

2. For each source document:
   PUT /documents/{id}
   → Upsert with contentHash

3. POST /sync {"keepDocumentIds": [...], "dryRun": true}
   → Preview what would be deleted

4. POST /sync {"keepDocumentIds": [...]}
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

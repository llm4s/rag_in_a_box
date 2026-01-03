---
layout: page
title: Documents API
parent: API Reference
nav_order: 1
---

# Documents API
{: .no_toc }

Manage documents in RAG in a Box.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Upload Document {#upload}

Upload a new document for indexing.

```
POST /api/v1/documents
```

### Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `content` | string | Yes | Document text content |
| `filename` | string | Yes | Filename for the document |
| `metadata` | object | No | Key-value metadata pairs |
| `collection` | string | No | Collection path (e.g., "docs/guides") |
| `readableBy` | string[] | No | Principals who can read this document |

### Example

```bash
curl -X POST http://localhost:8080/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{
    "content": "This is the document content...",
    "filename": "guide.txt",
    "metadata": {
      "author": "alice",
      "version": "1.0"
    },
    "collection": "docs/guides"
  }'
```

### Response

```json
{
  "documentId": "550e8400-e29b-41d4-a716-446655440000",
  "chunks": 3,
  "message": "Document ingested successfully"
}
```

---

## Upsert Document {#upsert}

Create or update a document by ID (idempotent).

```
PUT /api/v1/documents/:id
```

### Path Parameters

| Parameter | Description |
|-----------|-------------|
| `id` | Document ID (string) |

### Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `content` | string | Yes | Document text content |
| `metadata` | object | No | Key-value metadata pairs |
| `contentHash` | string | No | Content hash for change detection |
| `collection` | string | No | Collection path |
| `readableBy` | string[] | No | Principals who can read this document |

### Example

```bash
curl -X PUT http://localhost:8080/api/v1/documents/my-doc-001 \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Updated document content...",
    "contentHash": "sha256:abc123...",
    "collection": "docs/guides"
  }'
```

### Response

```json
{
  "documentId": "my-doc-001",
  "chunks": 2,
  "action": "updated",
  "message": "Document updated successfully"
}
```

**Action Values:**
- `created` - New document indexed
- `updated` - Existing document re-indexed
- `unchanged` - Content hash matched, no update needed

---

## List Documents {#list}

List all documents in the system.

```
GET /api/v1/documents
```

### Response

```json
{
  "documents": [],
  "total": 150
}
```

Note: Currently returns count only. Use sync endpoints for document details.

---

## Get Document {#get}

Get a specific document by ID.

```
GET /api/v1/documents/:id
```

### Path Parameters

| Parameter | Description |
|-----------|-------------|
| `id` | Document ID |

### Response

Returns `404 Not Found` if the document doesn't exist.

---

## Delete Document {#delete}

Delete a specific document.

```
DELETE /api/v1/documents/:id
```

### Path Parameters

| Parameter | Description |
|-----------|-------------|
| `id` | Document ID |

### Example

```bash
curl -X DELETE http://localhost:8080/api/v1/documents/my-doc-001
```

### Response

Returns `204 No Content` on success.

---

## Clear All Documents {#clear}

Delete all documents from the system.

```
DELETE /api/v1/documents
```

### Example

```bash
curl -X DELETE http://localhost:8080/api/v1/documents
```

### Response

```json
{
  "message": "All documents cleared"
}
```

**Warning:** This operation is irreversible.

---

## List Collections

List all document collections.

```
GET /api/v1/collections
```

### Response

```json
{
  "collections": [
    "docs/guides",
    "confluence/engineering",
    "sharepoint/hr"
  ]
}
```

---

## Permissions

When using permission-aware ingestion:

### readableBy Format

Specify principals who can read the document:

```json
{
  "readableBy": [
    "user:alice@example.com",
    "group:engineering"
  ]
}
```

If `readableBy` is empty or omitted, the document inherits collection-level permissions.

### Collection Path

Collections support hierarchical organization:

```
company/
  engineering/
    guides/
    specs/
  hr/
    policies/
```

Use collection paths like `company/engineering/guides` to organize documents.

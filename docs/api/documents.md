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
| `filename` | string | No | Filename for the document. Defaults to `untitled-<id prefix>.txt` (e.g. for pasted text) |
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

## Upload File {#upload-file}

```
POST /api/v1/documents/upload
```

Upload a file as `multipart/form-data`. Its text is extracted with llm4s's
`TikaDocumentExtractor` (Apache Tika), so PDF, DOCX, HTML, Markdown, plain text and other
formats Tika reads are supported; the document is then ingested exactly like
[Upload Document](#upload).

### Form Fields

| Field | Required | Description |
|-------|----------|-------------|
| `file` | Yes | The file; its name is the document's `filename` |
| `collection` | No | Collection to ingest into |
| `metadata` | No | A JSON object of string values |
| `readableBy` | No | Comma-separated principals (permission mode), e.g. `user:alice@example.com,group:hr` |

The document's metadata also records the detected `format` and, when the file has them,
its `title` and `creator`.

### Example

```bash
curl -X POST http://localhost:8080/api/v1/documents/upload \
  -F "file=@handbook.pdf" \
  -F "collection=hr" \
  -F 'metadata={"team":"people"}'
```

### Response

`201 Created` with the same body as [Upload Document](#upload). `400 Bad Request` when the
`file` part is missing, the metadata is not a JSON object of strings, or the file has no
extractable text.

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

List documents, most recently updated first, with optional filtering and pagination.

```
GET /api/v1/documents
```

### Query Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `page` | `1` | 1-based page number (values below 1 are treated as 1) |
| `pageSize` | `50` | Items per page, capped at `500` |
| `collection` | | Only documents in this collection (exact match) |
| `search` | | Case-insensitive substring match on id, filename or `title` metadata |

### Example

```bash
curl "http://localhost:8080/api/v1/documents?collection=docs/guides&search=install&page=1&pageSize=20"
```

### Response

```json
{
  "documents": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "filename": "install.md",
      "chunkCount": 3,
      "metadata": { "filename": "install.md", "collection": "docs/guides" },
      "createdAt": "2024-01-01T00:00:00Z",
      "collection": "docs/guides",
      "updatedAt": "2024-01-02T00:00:00Z"
    }
  ],
  "total": 1,
  "page": 1,
  "pageSize": 20,
  "hasMore": false
}
```

`total` is the number of documents matching the filters across all pages.

---

## Get Document {#get}

Get a specific document's registry entry by ID. Chunk content is available from
`GET /api/v1/visibility/chunks/:id`.

```
GET /api/v1/documents/:id
```

### Path Parameters

| Parameter | Description |
|-----------|-------------|
| `id` | Document ID |

### Response

Same shape as an entry in the list response above. Returns `404 Not Found` if
the document doesn't exist.

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

## Batch Delete Documents {#batch-delete}

Delete a specific set of documents by ID.

```
DELETE /api/v1/documents
```

### Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `documentIds` | string[] | Yes | IDs to delete (1 to 1000 entries) |
| `dryRun` | boolean | No | If true, report what would be deleted without deleting |

### Example

```bash
curl -X DELETE http://localhost:8080/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{"documentIds": ["doc-001", "doc-002"]}'
```

### Response

```json
{
  "message": "Deleted 2 documents",
  "deletedCount": 2,
  "deletedIds": ["doc-001", "doc-002"],
  "dryRun": false
}
```

### Errors

| Status | When |
|--------|------|
| `400` | The body is missing, is not valid JSON, or does not match the schema (e.g. a misspelled `documentIds`) |
| `400` | `documentIds` is empty or has more than 1000 entries |

A missing or malformed body **never** clears the index.

---

## Clear All Documents {#clear}

Delete all documents from the system. This requires the explicit `all=true`
query parameter and no request body.

```
DELETE /api/v1/documents?all=true
```

### Example

```bash
curl -X DELETE "http://localhost:8080/api/v1/documents?all=true"
```

### Response

```json
{
  "message": "All documents cleared"
}
```

### Errors

| Status | When |
|--------|------|
| `400` | `all` is not a boolean, or `all=true` is combined with a request body |

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

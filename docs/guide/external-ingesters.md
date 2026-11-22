---
layout: page
title: External Ingesters
parent: User Guide
nav_order: 8
---

# External Ingesters
{: .no_toc }

Build custom ingestion pipelines for any data source.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

External ingesters allow you to push documents to RAG in a Box from any source - Confluence, SharePoint, databases, or custom applications. The sync API provides efficient patterns for:

- **Full sync**: Compare all documents and detect deletions
- **Incremental sync**: Only process changed documents
- **Content hashing**: Skip unchanged documents automatically

The built-in URL and web-crawler ingestion only fetches public addresses (see
[Ingestion policy](configuration.md#ingestion-policy-allowed-directories-and-urls)). An external
ingester is the way to index intranet systems: it runs with its own network access and pushes
the content.

The examples omit credentials for brevity. Unless the server runs in open mode without
`API_KEY`, every request needs one; see [Authentication for Ingesters](#authentication-for-ingesters).
The `/api/v1/sync/*` endpoints are for ingesters: access tokens with the `sync:read` /
`sync:write` scopes, and administrators. Document endpoints need `documents:read` /
`documents:write`.

## Sync API Pattern

### 1. Get Current Document States

Check what documents are already indexed:

```bash
# Get all document IDs with hashes and timestamps
curl "http://localhost:8080/api/v1/sync/documents?include=hash,updatedAt"
```

`include` takes `hash` and/or `updatedAt`; without it only `id`, `collection` and `source` are
returned. Add `source=<your source>` to list only your ingester's documents.
The list is paged (`page`, `pageSize`: default 1000, max 10000); keep requesting the next
`page` while `hasMore` is true. A collection-restricted token only sees documents in its
collections.

Response:
```json
{
  "documents": [
    {
      "id": "doc-001",
      "hash": "sha256:abc123...",
      "updatedAt": "2024-01-15T10:30:00Z",
      "collection": "confluence"
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

### 2. Upsert Documents

For each document from your source, upsert to RAG in a Box:

```bash
curl -X PUT "http://localhost:8080/api/v1/documents/confluence-page-123" \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Page content from Confluence...",
    "contentHash": "sha256:computed-hash",
    "source": "confluence",
    "collection": "confluence",
    "metadata": {
      "title": "Engineering Guidelines",
      "space": "ENG",
      "author": "alice@example.com"
    }
  }'
```

Response (`201 Created` for a new document, `200 OK` otherwise):
```json
{
  "documentId": "confluence-page-123",
  "chunks": 5,
  "action": "unchanged",
  "message": "Document unchanged (hash match)"
}
```

`action` is `created`, `updated` or `unchanged`.

The `contentHash` parameter enables efficient change detection:
- If the hash matches the stored one, the document is skipped (no re-chunking/embedding)
- If the hash differs, the document is re-indexed
- If omitted, RAG in a Box computes one (hex SHA-256 of the content). The value is stored as
  given, so use one format consistently (the examples use `sha256:<hex>`)

`source` names your ingester (default `api`): the prune below only removes documents of that
source. Use the same `source` on every upsert and on `POST /sync`, and one per ingester. It may
not be the name of a configured ingestion source (or `api:url` / `api:directory:*`): that is a
`409` (see [Source names](/api/sync#source-names)).

`collection` (default `default`) and `readableBy` (principal external IDs such as
`user:alice`, `group:eng`) apply in permission mode; without permission mode the collection is
only recorded in the metadata and `readableBy` is ignored.

### 3. Detect and Remove Deleted Documents

After syncing all documents from your source, prune orphans: every document of your
`source` **not** in `keepDocumentIds` is deleted. Uploads, other sources' documents and
documents of configured ingestion sources are never touched (an admin-only `"scope": "all"`
prunes everything; see [the Sync API](/api/sync#prune)). Always try `dryRun` first.

{: .note }
Upgrading an ingester written before sources existed: see
[the migration note](/api/sync#sync-scope-migration).

```bash
# First, preview what would be deleted (dry run)
curl -X POST "http://localhost:8080/api/v1/sync" \
  -H "Content-Type: application/json" \
  -d '{
    "keepDocumentIds": ["doc-001", "doc-003", "doc-004"],
    "source": "confluence",
    "dryRun": true
  }'
```

Response:
```json
{
  "message": "Dry run: would delete 1 documents",
  "prunedCount": 1,
  "prunedIds": ["doc-002"],
  "dryRun": true
}
```

A `POST /api/v1/sync` without a body only records the sync time (see
[Sync Status](#sync-status)). Then execute the prune:

```bash
curl -X POST "http://localhost:8080/api/v1/sync" \
  -H "Content-Type: application/json" \
  -d '{
    "keepDocumentIds": ["doc-001", "doc-003", "doc-004"],
    "source": "confluence"
  }'
```

Response:
```json
{
  "message": "Sync completed",
  "prunedCount": 1,
  "dryRun": false
}
```

## Batch Delete

For deleting multiple specific documents at once (up to 1000 ids; `documents:write`):

```bash
# Dry run - preview what would be deleted
curl -X DELETE "http://localhost:8080/api/v1/documents" \
  -H "Content-Type: application/json" \
  -d '{
    "documentIds": ["doc-001", "doc-002", "doc-003"],
    "dryRun": true
  }'
```

Response:
```json
{
  "message": "Dry run: would delete 3 documents",
  "deletedCount": 3,
  "deletedIds": ["doc-001", "doc-002", "doc-003"],
  "failedIds": null,
  "dryRun": true
}
```

Execute the delete:

```bash
curl -X DELETE "http://localhost:8080/api/v1/documents" \
  -H "Content-Type: application/json" \
  -d '{
    "documentIds": ["doc-001", "doc-002", "doc-003"]
  }'
```

Response:
```json
{
  "message": "Deleted 3 documents",
  "deletedCount": 3,
  "deletedIds": ["doc-001", "doc-002", "doc-003"],
  "failedIds": null,
  "dryRun": false
}
```

`DELETE /api/v1/documents` without a body answers `400`. Deleting **every** document needs
`DELETE /api/v1/documents?all=true` with no body, and an administrator.

## Incremental Sync

For large document sets, use timestamp-based incremental sync:

```bash
# Get only documents modified since last sync
curl "http://localhost:8080/api/v1/sync/documents?since=2024-01-15T00:00:00Z&include=hash"
```

This returns only documents updated after the specified timestamp, reducing the data transferred for incremental syncs.

## Batch Document Check

For efficient comparison of large document sets:

```bash
curl -X POST "http://localhost:8080/api/v1/sync/check" \
  -H "Content-Type: application/json" \
  -d '{
    "documentIds": ["doc-001", "doc-002", "doc-003", "doc-new"]
  }'
```

Response:
```json
{
  "found": [
    {"id": "doc-001", "hash": "sha256:abc...", "updatedAt": "..."},
    {"id": "doc-002", "hash": "sha256:def...", "updatedAt": "..."}
  ],
  "missing": ["doc-003", "doc-new"]
}
```

## Example: Python Confluence Ingester

```python
import hashlib
import requests
from atlassian import Confluence

class ConfluenceIngester:
    def __init__(self, ragbox_url, confluence_url, confluence_token):
        self.ragbox = ragbox_url
        self.confluence = Confluence(url=confluence_url, token=confluence_token)

    def compute_hash(self, content):
        return f"sha256:{hashlib.sha256(content.encode()).hexdigest()}"

    def sync_space(self, space_key):
        # Get current state from RAG in a Box
        resp = requests.get(
            f"{self.ragbox}/api/v1/sync/documents",
            params={"include": "hash"}
        )
        current_docs = {d["id"]: d["hash"] for d in resp.json()["documents"]}

        # Get all pages from Confluence
        source_doc_ids = []
        pages = self.confluence.get_all_pages_from_space(space_key)

        for page in pages:
            doc_id = f"confluence-{page['id']}"
            source_doc_ids.append(doc_id)

            # Get full page content
            content = self.confluence.get_page_by_id(page['id'], expand='body.storage')
            body = content['body']['storage']['value']
            content_hash = self.compute_hash(body)

            # Skip if unchanged
            if current_docs.get(doc_id) == content_hash:
                continue

            # Upsert to RAG in a Box
            requests.put(
                f"{self.ragbox}/api/v1/documents/{doc_id}",
                json={
                    "content": body,
                    "contentHash": content_hash,
                    "source": "confluence",
                    "collection": f"confluence/{space_key}",
                    "metadata": {
                        "title": page['title'],
                        "space": space_key,
                        "url": page['_links']['webui']
                    }
                }
            )

        # Prune deleted pages
        requests.post(
            f"{self.ragbox}/api/v1/sync",
            json={"keepDocumentIds": source_doc_ids, "source": "confluence"}
        )

# Usage
ingester = ConfluenceIngester(
    ragbox_url="http://localhost:8080",
    confluence_url="https://company.atlassian.net",
    confluence_token="your-token"
)
ingester.sync_space("ENG")
```

## Example: Node.js SharePoint Ingester

```javascript
const { Client } = require("@microsoft/microsoft-graph-client");
const crypto = require("crypto");

class SharePointIngester {
  constructor(ragboxUrl, graphClient) {
    this.ragboxUrl = ragboxUrl;
    this.graphClient = graphClient;
  }

  computeHash(content) {
    return `sha256:${crypto.createHash("sha256").update(content).digest("hex")}`;
  }

  async syncSite(siteId, driveId) {
    // Get current documents from RAG in a Box
    const currentResp = await fetch(
      `${this.ragboxUrl}/api/v1/sync/documents?include=hash`
    );
    const currentDocs = new Map(
      (await currentResp.json()).documents.map((d) => [d.id, d.hash])
    );

    // List all files in SharePoint
    const sourceDocIds = [];
    const files = await this.graphClient
      .api(`/sites/${siteId}/drives/${driveId}/root/children`)
      .get();

    for (const file of files.value) {
      if (file.file) {
        const docId = `sharepoint-${file.id}`;
        sourceDocIds.push(docId);

        // Get file content
        const content = await this.graphClient
          .api(`/sites/${siteId}/drives/${driveId}/items/${file.id}/content`)
          .get();

        const contentHash = this.computeHash(content);

        // Skip if unchanged
        if (currentDocs.get(docId) === contentHash) {
          continue;
        }

        // Upsert to RAG in a Box
        await fetch(`${this.ragboxUrl}/api/v1/documents/${docId}`, {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            content: content,
            contentHash: contentHash,
            source: "sharepoint",
            collection: `sharepoint/${siteId}`,
            metadata: {
              filename: file.name,
              modified: file.lastModifiedDateTime,
            },
          }),
        });
      }
    }

    // Prune deleted files
    await fetch(`${this.ragboxUrl}/api/v1/sync`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ keepDocumentIds: sourceDocIds, source: "sharepoint" }),
    });
  }
}
```

## Authentication for Ingesters

When authentication is enabled (`API_KEY` set, or `AUTH_MODE=basic`/`oauth`), ingesters need
an [access token](access-tokens.md). Give it only the scopes it needs and, ideally, restrict it
to the collections it writes:

```bash
# Create an access token (administrators: API_KEY, an admin JWT, or an admin-scoped token)
curl -X POST "http://localhost:8080/api/v1/tokens" \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "confluence-ingester",
    "scopes": ["documents:write", "sync:write", "sync:read"],
    "collections": ["confluence"]
  }'
```

A token restricted to `confluence` may write to `confluence` and its sub-collections
(`confluence/ENG`, ...). Writes to other collections answer `403`, and `POST /api/v1/sync`
with `keepDocumentIds` only prunes documents inside the token's collections.

Response (`201 Created`):
```json
{
  "id": "token-uuid",
  "name": "confluence-ingester",
  "token": "rat_abc123...",
  "scopes": ["documents:write", "sync:write", "sync:read"],
  "collections": ["confluence"],
  "expiresAt": null
}
```

Use the token in your ingester (here the collection must be inside `confluence`; without one the
document would go to `default` and be refused):

```bash
curl -X PUT "http://localhost:8080/api/v1/documents/doc-123" \
  -H "Authorization: Bearer rat_abc123..." \
  -H "Content-Type: application/json" \
  -d '{"content": "...", "collection": "confluence/ENG"}'
```

In the Python and Node.js examples above, add the same `Authorization: Bearer` header to every
request.

## Sync Status

Check when the last sync completed (`POST /api/v1/sync` records it):

```bash
curl "http://localhost:8080/api/v1/sync/status"
```

Response:
```json
{
  "lastSyncTime": "2024-01-15T10:30:00Z",
  "documentCount": 150,
  "chunkCount": 2500
}
```

Deletes (`DELETE /api/v1/documents/{id}`, batch deletes and pruning) take effect immediately,
so there is nothing pending to report.

## Best Practices

1. **Use content hashes**: Always compute and send content hashes to avoid re-indexing unchanged documents

2. **Batch operations**: For large syncs, process documents in batches to avoid timeouts

3. **Dry-run prunes**: Always preview deletions before executing them

4. **Incremental syncs**: Use timestamp-based sync for frequent updates

5. **Collection organization**: Group related documents in collections (e.g., `confluence/ENG`, `sharepoint/HR`)

6. **Error handling**: Implement retry logic for transient failures

7. **Logging**: Log sync operations for debugging and audit trails

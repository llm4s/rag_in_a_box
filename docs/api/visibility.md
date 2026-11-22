---
layout: page
title: Visibility API
parent: API Reference
nav_order: 11
---

# Visibility API
{: .no_toc }

Look inside the index: the detailed configuration, the stored chunks, and chunk statistics.
The admin UI's Visibility pages are built on these endpoints.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

**Auth:** every endpoint is **Admin** only: chunks are returned regardless of document
permissions.

## Detailed Configuration {#config}

```
GET /api/v1/visibility/config
```

The embedding, LLM, RAG and database configuration, with each setting annotated with how it can
be changed (hot, warm, or restart) and which settings are runtime-configurable.

## List Chunks {#chunks}

```
GET /api/v1/visibility/chunks?page=1&pageSize=50
```

```json
{
  "chunks": [
    {
      "id": "doc-1#0",
      "documentId": "doc-1",
      "index": 0,
      "content": "...",
      "contentLength": 640,
      "metadata": { "filename": "guide.md" },
      "chunkMetadata": { "headings": ["Intro"], "isCodeBlock": false, "language": null, "startOffset": null, "endOffset": null }
    }
  ],
  "total": 2500,
  "page": 1,
  "pageSize": 50,
  "hasMore": true
}
```

## Document Chunks {#document-chunks}

```
GET /api/v1/visibility/chunks/{documentId}
```

```json
{
  "documentId": "doc-1",
  "filename": "guide.md",
  "collection": "docs",
  "chunkCount": 3,
  "chunks": [ { ...chunk... } ],
  "chunkingConfig": { "strategy": "markdown", "targetSize": 800, "maxSize": 1200, "overlap": 150 }
}
```

`404` when the document does not exist.

## One Chunk {#chunk}

```
GET /api/v1/visibility/chunks/{documentId}/{index}
```

A single chunk object, or `404`.

## Detailed Statistics {#stats}

```
GET /api/v1/visibility/stats
```

```json
{
  "documentCount": 150,
  "chunkCount": 2500,
  "vectorCount": 2500,
  "collectionCount": 3,
  "avgChunksPerDocument": 16.7,
  "collections": [
    { "name": "docs", "documentCount": 100, "chunkCount": 1800, "avgChunksPerDoc": 18.0, "avgChunkSize": 690.5, "chunkingStrategy": "markdown" }
  ],
  "chunkSizeDistribution": {
    "min": 40, "max": 1200, "avg": 701.2, "median": 720.0, "p90": 1050.0,
    "buckets": [ { "rangeStart": 0, "rangeEnd": 200, "count": 120 } ]
  },
  "indexedSince": "2026-01-01T00:00:00Z",
  "lastIngestion": "2026-01-15T10:30:00Z",
  "currentConfig": { "strategy": "sentence", "targetSize": 800, "maxSize": 1200, "overlap": 150 }
}
```

## Collections {#collections}

```
GET /api/v1/visibility/collections
```

Each collection with its document and chunk counts, its custom chunking config (if any) and the
effective config (`source`: `collection`, `default` or `file-type`), plus the default config:

```json
{
  "collections": [
    {
      "name": "docs",
      "documentCount": 100,
      "chunkCount": 1800,
      "customConfig": { "strategy": "markdown", "targetSize": null, "maxSize": null, "overlap": null, "fileTypeStrategies": {} },
      "effectiveConfig": { "strategy": "markdown", "targetSize": 800, "maxSize": 1200, "overlap": 150, "source": "collection" }
    }
  ],
  "defaultConfig": { "strategy": "sentence", "targetSize": 800, "maxSize": 1200, "overlap": 150, "source": "default" }
}
```

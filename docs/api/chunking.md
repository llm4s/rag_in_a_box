---
layout: page
title: Chunking API
parent: API Reference
nav_order: 10
---

# Chunking API
{: .no_toc }

Preview and compare llm4s chunking strategies without storing anything, and set per-collection
chunking configuration. See the [Chunking guide](/guide/chunking).
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

**Auth:** reads and previews are open to any authenticated caller (access tokens need the
`admin` scope). Changing collection configs, and `POST .../config/preview`, are **Admin** only.

Strategies: `simple`, `sentence`, `markdown`, `semantic` (`semantic` needs embeddings).

## Preview {#preview}

```
POST /api/v1/chunking/preview
```

| Field | Required | Description |
|-------|----------|-------------|
| `content` | Yes | Text to chunk |
| `strategy` | No | Strategy (default: auto-detected from `filename`, else the runtime setting) |
| `targetSize`, `maxSize`, `overlap` | No | Sizes in characters (default: runtime settings) |
| `filename` | No | Used to auto-detect the strategy (e.g. `.md` selects `markdown`) |

```json
{
  "strategy": "markdown",
  "config": { "strategy": "markdown", "targetSize": 800, "maxSize": 1200, "overlap": 150, "source": "auto-detect" },
  "chunks": [
    { "index": 0, "content": "# Intro\n...", "length": 640, "headings": ["Intro"], "isCodeBlock": false, "language": null }
  ],
  "stats": { "chunkCount": 3, "totalLength": 2100, "avgChunkSize": 700.0, "minChunkSize": 420, "maxChunkSize": 800, "estimatedTokens": 525 },
  "warnings": [ { "level": "info", "message": "...", "suggestion": null } ]
}
```

`config.source` is `request`, `auto-detect` or `default`.

## Compare {#compare}

```
POST /api/v1/chunking/compare
```

```json
{ "content": "...", "strategies": ["simple", "sentence", "markdown"], "targetSize": 800, "overlap": 150 }
```

```json
{
  "results": [ { "strategy": "simple", "chunks": [], "stats": { }, "warnings": [] } ],
  "recommendation": { "strategy": "markdown", "reason": "..." }
}
```

## Strategies {#strategies}

```
GET /api/v1/chunking/strategies
```

```json
{
  "strategies": [
    { "name": "sentence", "displayName": "Sentence", "description": "...", "bestFor": ["..."], "tradeoffs": ["..."], "requiresEmbeddings": false }
  ],
  "currentDefault": "sentence"
}
```

## Presets {#presets}

```
GET /api/v1/chunking/presets
```

Presets `small`, `default` and `large`, plus the current configuration:

```json
{
  "presets": [
    { "name": "small", "displayName": "Small Chunks", "description": "...", "targetSize": 400, "maxSize": 600, "overlap": 75, "bestFor": ["..."] }
  ],
  "current": { "name": "current", "displayName": "Current Configuration", "...": "..." }
}
```

---

## Per-Collection Configuration

A collection may override the runtime chunking settings, and map file extensions to strategies.
Resolution order for a file: the collection's file-type override, then the collection's
settings, then the runtime defaults. The settings apply to documents ingested afterwards.

`{name}` is a single path segment; percent-encode the slashes of a nested collection
(`docs%2Fguides`).

### List All {#collection-configs}

```
GET /api/v1/collections/configs
```

```json
{
  "collections": [ { ...collection config response... } ],
  "defaultConfig": { "strategy": "sentence", "targetSize": 800, "maxSize": 1200, "overlap": 150, "source": "default", "appliedFileTypeOverride": null }
}
```

### Get {#collection-config}

```
GET /api/v1/collections/{name}/config
```

```json
{
  "collection": "docs",
  "hasCustomConfig": true,
  "config": {
    "collection": "docs",
    "strategy": "markdown",
    "targetSize": 1000,
    "maxSize": 1500,
    "overlap": 100,
    "fileTypeStrategies": { ".txt": "sentence" },
    "updatedAt": "2026-01-15T10:30:00Z"
  },
  "effectiveConfig": { "strategy": "markdown", "targetSize": 1000, "maxSize": 1500, "overlap": 100, "source": "collection", "appliedFileTypeOverride": null },
  "documentCount": 42
}
```

### Set {#collection-config-update}

```
PUT /api/v1/collections/{name}/config
```

```json
{ "strategy": "markdown", "targetSize": 1000, "maxSize": 1500, "overlap": 100, "fileTypeStrategies": { ".txt": "sentence" } }
```

All fields optional. `targetSize` 100 to 10000, `maxSize` 100 to 20000 and at least
`targetSize`, `overlap` non-negative and below `targetSize`; extensions start with a dot and are
matched case-insensitively. Invalid settings answer `400` (`validation_failed`). Success:

```json
{ "collection": "docs", "config": { ... }, "message": "Collection chunking configuration updated" }
```

### Remove {#collection-config-delete}

```
DELETE /api/v1/collections/{name}/config
```

```json
{ "collection": "docs", "message": "Custom configuration removed. Collection will use default settings." }
```

`404` when the collection has no custom configuration.

### Preview the Effective Config {#collection-config-preview}

```
POST /api/v1/collections/{name}/config/preview
```

```json
{ "filename": "notes.txt" }
```

The collection is the one in the path.

```json
{
  "collection": "docs",
  "filename": "notes.txt",
  "effectiveConfig": { "strategy": "sentence", "...": "...", "source": "file-type", "appliedFileTypeOverride": ".txt" },
  "configResolutionPath": [
    "Checked file extension: .txt",
    "Found file-type override in collection 'docs' config",
    "Using strategy: sentence"
  ]
}
```

The collection in the path is used; the body's `collection` field is required by the schema but
ignored.

---
layout: page
title: Chunking Strategies
parent: User Guide
nav_order: 10
---

# Chunking Strategies Guide

Optimize document chunking for better retrieval quality.
{: .fs-6 .fw-300 }

## Overview

Chunking is the process of splitting documents into smaller pieces for vector search. The right chunking strategy significantly impacts retrieval quality.

RAG in a Box supports four chunking strategies:
- **Simple**: Fixed-size character splits
- **Sentence**: Respects sentence boundaries
- **Markdown**: Preserves markdown structure
- **Semantic**: Uses embeddings for topic detection

---

## Available Strategies

### Simple

Splits text at fixed character boundaries.

| Aspect | Details |
|--------|---------|
| **Speed** | Fastest |
| **Best For** | Raw data, log files, unstructured text |
| **Trade-offs** | May split mid-sentence, no semantic awareness |

```bash
RAG_CHUNKING_STRATEGY=simple
```

### Sentence

Respects sentence boundaries for more coherent chunks.

| Aspect | Details |
|--------|---------|
| **Speed** | Fast |
| **Best For** | Prose, articles, general documents |
| **Trade-offs** | Slightly variable chunk sizes |

```bash
RAG_CHUNKING_STRATEGY=sentence
```

### Markdown

Preserves markdown structure including headings, code blocks, and lists.

| Aspect | Details |
|--------|---------|
| **Speed** | Fast |
| **Best For** | Documentation, README files, technical docs |
| **Trade-offs** | Only effective for markdown content |

```bash
RAG_CHUNKING_STRATEGY=markdown
```

### Semantic

Uses embeddings to detect topic boundaries. Highest quality but requires more processing.

| Aspect | Details |
|--------|---------|
| **Speed** | Slowest (requires embedding API calls) |
| **Best For** | Mixed-topic documents, research papers |
| **Trade-offs** | Higher cost, slower processing |

```bash
RAG_CHUNKING_STRATEGY=semantic
```

When indexing, ragbox builds llm4s's semantic chunker over the configured embedding
provider - the same chunker the llm4s pipeline uses. The preview and compare APIs have no
embedding provider, so they do **not** preview semantic chunking: a semantic preview shows
the sentence chunking llm4s falls back to, labelled `sentence` (config source
`semantic-fallback`) with an info warning, and a comparison reports `semantic` with no
chunks and a warning. To see real semantic chunks, index a document and open it under
Visibility.

---

## How Documents Are Chunked

Each document is chunked **once**, with the chunker and chunk size/overlap the llm4s
pipeline is configured with, and those exact chunks are both indexed (llm4s
`RAG.ingestChunks`) and kept in the chunk store. Chunk store ids are the ids llm4s
indexed the chunks under - `<documentId>-chunk-<n>`, or `coll-<collectionId>-<documentId>-chunk-<n>`
in permission mode - so a search result's id matches a chunk in the chunk store.

In permission mode llm4s 0.6.0 has no pre-chunked ingest ([llm4s/llm4s#2732](https://github.com/llm4s/llm4s/issues/2732); `RAG.ingestWithPermissions` chunks the
text itself), so it chunks with the same chunker and config, which gives the same chunks. Only
the semantic chunker could disagree (its boundaries depend on embeddings); ragbox logs a
warning if the chunk counts differ.

---

## Configuration

### Basic Settings

```bash
# Strategy selection
RAG_CHUNKING_STRATEGY=sentence

# Chunk size (characters)
RAG_CHUNK_SIZE=800

# Overlap between chunks (characters)
RAG_CHUNK_OVERLAP=150
```

### Size Presets

| Preset | Target Size | Max Size | Overlap | Use Case |
|--------|-------------|----------|---------|----------|
| Small | 400 | 600 | 75 | Q&A systems, precise answers |
| Default | 800 | 1200 | 150 | General documents |
| Large | 1500 | 2000 | 250 | Long documents, narrative content |

---

## Preview API

Test chunking strategies before committing to a configuration. The preview endpoints only
compute chunks; nothing is stored. Any authenticated user may call them (access tokens need the
`admin` scope). The examples omit credentials; add `-H "X-API-Key: $API_KEY"` or a bearer token
unless you run in open mode.

### Preview Chunking

```bash
curl -X POST http://localhost:8080/api/v1/chunking/preview \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Your document content here...",
    "strategy": "sentence",
    "targetSize": 800,
    "overlap": 150
  }'
```

All fields but `content` are optional: `strategy`, `targetSize`, `maxSize` and `overlap`
default to the current runtime settings, and `filename` lets the preview pick a strategy from
the extension (see [Auto-detection in the preview](#auto-detection-in-the-preview)).

Response includes:
- Chunked content with indices
- Statistics (count, sizes, token estimates)
- Warnings about potential issues

### Compare Strategies

```bash
curl -X POST http://localhost:8080/api/v1/chunking/compare \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Your document content here...",
    "strategies": ["simple", "sentence", "markdown"],
    "targetSize": 800
  }'
```

Returns `{"results": [{strategy, chunks, stats, warnings}], "recommendation": {strategy, reason}}`. Content that llm4s's
`ChunkerFactory.auto` detects as markdown (headings, lists, links, images or code fences)
gets a `markdown` recommendation; otherwise the strategy with the fewest warnings and chunk
sizes nearest the target is recommended.

### List Available Strategies

```bash
curl http://localhost:8080/api/v1/chunking/strategies
```

Returns `{"strategies": [{name, displayName, description, bestFor, tradeoffs, requiresEmbeddings}], "currentDefault": "sentence"}`.

### List Presets

```bash
curl http://localhost:8080/api/v1/chunking/presets
```

Returns `{"presets": [...], "current": {...}}`; each preset has `name` (`small`, `default`,
`large`), `displayName`, `description`, `targetSize`, `maxSize`, `overlap` and `bestFor`.
`current` describes the runtime settings.

---

## Per-Collection Configuration

A collection can override the runtime chunking settings, including a strategy per file
extension. Ingestion (uploads, upserts and ingestion sources) uses, in order: the collection's
file-type override for the document's file name, the collection's own settings, the runtime
settings.

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/api/v1/collections/configs` | GET | any authenticated | Every collection with a custom config |
| `/api/v1/collections/{collection}/config` | GET | any authenticated | `{collection, hasCustomConfig, config, effectiveConfig, documentCount}` |
| `/api/v1/collections/{collection}/config` | PUT | admin | Set `strategy`, `targetSize`, `maxSize`, `overlap`, `fileTypeStrategies` (all optional) |
| `/api/v1/collections/{collection}/config` | DELETE | admin | Remove the custom config (`404` if there is none) |
| `/api/v1/collections/{collection}/config/preview` | POST | any authenticated | Effective config for `{"filename"}` in the path's collection (changes nothing) |

```bash
curl -X PUT http://localhost:8080/api/v1/collections/docs/config \
  -H "Content-Type: application/json" \
  -d '{"strategy": "markdown", "targetSize": 500, "overlap": 75, "fileTypeStrategies": {".txt": "sentence"}}'
```

Nested collection names may be written with raw or encoded slashes (`/collections/eng/backend/config`
or `/collections/eng%2Fbackend/config`).
Existing documents keep their chunks until they are re-ingested.

---

## Auto-Detection in the Preview

When a preview request names no (valid) `strategy` but has a `filename`, the preview picks one
from the extension (config `source` `auto-detect`):

| Extension | Strategy |
|-----------|----------|
| `.md`, `.markdown` | markdown |
| `.json`, `.xml` | simple |
| Others | the runtime default (`RAG_CHUNKING_STRATEGY`, source `default`) |

This only applies to `POST /api/v1/chunking/preview`. Ingestion does not guess from the file
extension; use a collection's `fileTypeStrategies` for that.

---

## Tuning Guidelines

### For Q&A Systems

Use smaller chunks for precise answers:

```bash
RAG_CHUNKING_STRATEGY=sentence
RAG_CHUNK_SIZE=400
RAG_CHUNK_OVERLAP=75
RAG_TOP_K=8
```

### For Technical Documentation

Use markdown-aware chunking:

```bash
RAG_CHUNKING_STRATEGY=markdown
RAG_CHUNK_SIZE=1000
RAG_CHUNK_OVERLAP=200
```

### For Long-Form Content

Use larger chunks to preserve context:

```bash
RAG_CHUNKING_STRATEGY=sentence
RAG_CHUNK_SIZE=1500
RAG_CHUNK_OVERLAP=300
RAG_TOP_K=3
```

### For Code Documentation

Markdown strategy preserves code blocks:

```bash
RAG_CHUNKING_STRATEGY=markdown
RAG_CHUNK_SIZE=1200
RAG_CHUNK_OVERLAP=100
```

---

## Preview Response Format

```json
{
  "strategy": "sentence",
  "config": {
    "strategy": "sentence",
    "targetSize": 800,
    "maxSize": 1200,
    "overlap": 150,
    "source": "request"
  },
  "chunks": [
    {
      "index": 0,
      "content": "First chunk content...",
      "length": 756,
      "headings": ["Introduction"],
      "isCodeBlock": false,
      "language": null
    }
  ],
  "stats": {
    "chunkCount": 5,
    "totalLength": 3800,
    "avgChunkSize": 760.0,
    "minChunkSize": 650,
    "maxChunkSize": 890,
    "estimatedTokens": 950
  },
  "warnings": [
    {
      "level": "info",
      "message": "Content appears to be markdown",
      "suggestion": "Consider using 'markdown' strategy"
    }
  ]
}
```

---

## Best Practices

### 1. Test Before Deploying

Always preview chunking on representative documents:

```bash
# Test with actual content
curl -X POST http://localhost:8080/api/v1/chunking/compare \
  -H "Content-Type: application/json" \
  -d @sample-document.json
```

### 2. Balance Size and Context

- **Too small**: Loses context, more chunks to search
- **Too large**: Lower precision, may exceed context windows

Start with default (800 chars) and adjust based on results.

### 3. Match Strategy to Content

| Content Type | Recommended Strategy |
|--------------|---------------------|
| General text | sentence |
| Documentation | markdown |
| Code files | markdown |
| Log files | simple |
| Research papers | semantic |

### 4. Monitor Warnings

The preview API generates warnings for:
- Very small chunks (< 100 chars)
- Chunks exceeding max size
- A single chunk much smaller than the target size
- Potential sentence splits (simple strategy)
- Semantic chunking, which the preview cannot show (see [Semantic](#semantic))

Address warnings before production deployment.

### 5. Consider Overlap

Overlap helps maintain context across chunk boundaries:

| Scenario | Overlap |
|----------|---------|
| Q&A (precision) | 50-100 chars |
| General use | 100-200 chars |
| Narrative (context) | 200-300 chars |

---

## Troubleshooting

### Chunks Too Small

Increase target size or reduce overlap:

```bash
RAG_CHUNK_SIZE=1000
RAG_CHUNK_OVERLAP=100
```

### Chunks Too Large

Decrease target size:

```bash
RAG_CHUNK_SIZE=500
```

### Poor Retrieval Quality

1. Try different strategies (compare API)
2. Adjust chunk size
3. Increase `RAG_TOP_K` for more context
4. Consider semantic chunking for mixed-topic content

### Code Blocks Split Incorrectly

Use markdown strategy for code-heavy content:

```bash
RAG_CHUNKING_STRATEGY=markdown
```

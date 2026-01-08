---
layout: page
title: Chunking Strategies
parent: User Guide
nav_order: 6
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

Test chunking strategies before committing to a configuration.

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

Returns results for each strategy with a recommendation.

### List Available Strategies

```bash
curl http://localhost:8080/api/v1/chunking/strategies
```

### List Presets

```bash
curl http://localhost:8080/api/v1/chunking/presets
```

---

## Auto-Detection

When no strategy is specified, RAG in a Box auto-detects based on file extension:

| Extension | Strategy |
|-----------|----------|
| `.md`, `.markdown` | markdown |
| `.json`, `.xml` | simple |
| Others | Default (sentence) |

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
      "isCodeBlock": false
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
- Potential sentence splits (simple strategy)

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

---
layout: page
title: Config API
parent: API Reference
nav_order: 7
---

# Config API

Endpoints for retrieving application configuration and statistics.
{: .fs-6 .fw-300 }

## Get Configuration {#get}

Retrieve the current application configuration.

```
GET /api/v1/config
```

### Example Response

```json
{
  "embedding": {
    "provider": "openai",
    "model": "text-embedding-3-small"
  },
  "llm": {
    "model": "openai/gpt-4o",
    "temperature": 0.1
  },
  "rag": {
    "chunkingStrategy": "sentence",
    "chunkSize": 800,
    "chunkOverlap": 150,
    "topK": 5,
    "fusionStrategy": "rrf"
  },
  "auth": {
    "mode": "basic"
  }
}
```

---

## Get Providers {#providers}

List available LLM and embedding providers.

```
GET /api/v1/config/providers
```

### Example Response

```json
{
  "llm": [
    {
      "id": "openai/gpt-4o",
      "name": "GPT-4o",
      "provider": "openai",
      "available": true
    },
    {
      "id": "anthropic/claude-3-opus",
      "name": "Claude 3 Opus",
      "provider": "anthropic",
      "available": false,
      "reason": "API key not configured"
    }
  ],
  "embedding": [
    {
      "id": "openai/text-embedding-3-small",
      "name": "text-embedding-3-small",
      "provider": "openai",
      "dimensions": 1536,
      "available": true
    }
  ]
}
```

---

## Get Statistics {#stats}

Retrieve document and collection statistics.

```
GET /api/v1/stats
```

### Example Response

```json
{
  "totalDocuments": 150,
  "totalChunks": 2340,
  "collections": [
    {
      "name": "docs/guides",
      "documentCount": 45,
      "chunkCount": 720
    },
    {
      "name": "docs/api",
      "documentCount": 30,
      "chunkCount": 480
    }
  ],
  "lastIngestion": "2024-01-15T08:00:00Z"
}
```

---

## Update Configuration

Runtime configuration updates are not supported. Configuration must be changed via environment variables and requires a restart.

```
PUT /api/v1/config
```

### Response

**Status: 400 Bad Request**

```json
{
  "error": "bad_request",
  "message": "Runtime configuration updates not supported. Restart with new environment variables."
}
```

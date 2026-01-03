---
layout: page
title: Quick Start
parent: Getting Started
nav_order: 1
---

# Quick Start Guide
{: .no_toc }

Get RAG in a Box running in under 5 minutes.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Prerequisites

- Docker and Docker Compose
- An OpenAI API key (or Anthropic API key for Claude models)
- PostgreSQL with pgvector (included in Docker Compose)

## Option 1: Docker Compose (Recommended)

Create a `docker-compose.yml` file:

```yaml
version: '3.8'
services:
  ragbox:
    image: llm4s/rag-in-a-box:latest
    ports:
      - "8080:8080"
    environment:
      - OPENAI_API_KEY=${OPENAI_API_KEY}
      - DATABASE_HOST=postgres
      - DATABASE_PORT=5432
      - DATABASE_NAME=ragbox
      - DATABASE_USER=ragbox
      - DATABASE_PASSWORD=ragbox
    volumes:
      - ./docs:/data/docs
    depends_on:
      - postgres

  postgres:
    image: pgvector/pgvector:pg16
    environment:
      - POSTGRES_DB=ragbox
      - POSTGRES_USER=ragbox
      - POSTGRES_PASSWORD=ragbox
    volumes:
      - pgdata:/var/lib/postgresql/data
    ports:
      - "5432:5432"

volumes:
  pgdata:
```

Start the services:

```bash
export OPENAI_API_KEY=your-key-here
docker-compose up -d
```

## Option 2: Docker Run

If you already have a PostgreSQL database with pgvector:

```bash
docker run -d \
  -p 8080:8080 \
  -e OPENAI_API_KEY=$OPENAI_API_KEY \
  -e DATABASE_HOST=your-postgres-host \
  -e DATABASE_PORT=5432 \
  -e DATABASE_NAME=ragbox \
  -e DATABASE_USER=ragbox \
  -e DATABASE_PASSWORD=your-password \
  -v /path/to/docs:/data/docs \
  llm4s/rag-in-a-box:latest
```

## Verify Installation

Check the health endpoint:

```bash
curl http://localhost:8080/health
```

Expected response:
```json
{
  "status": "healthy",
  "version": "1.0.0",
  "uptime": 12345
}
```

Check readiness:

```bash
curl http://localhost:8080/health/ready
```

## Upload Your First Document

```bash
curl -X POST http://localhost:8080/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{
    "content": "RAG in a Box is a turnkey RAG solution that makes it easy to build AI-powered search and question answering systems. It supports multiple embedding providers including OpenAI, Anthropic, and local models.",
    "filename": "about-ragbox.txt",
    "metadata": {
      "category": "documentation",
      "version": "1.0"
    }
  }'
```

Response:
```json
{
  "documentId": "abc123...",
  "chunks": 1,
  "message": "Document ingested successfully"
}
```

## Query the System

Ask a question about your documents:

```bash
curl -X POST http://localhost:8080/api/v1/query \
  -H "Content-Type: application/json" \
  -d '{
    "question": "What embedding providers does RAG in a Box support?"
  }'
```

Response:
```json
{
  "answer": "RAG in a Box supports multiple embedding providers including OpenAI, Anthropic, and local models.",
  "contexts": [
    {
      "content": "RAG in a Box is a turnkey RAG solution...",
      "score": 0.92,
      "metadata": {
        "filename": "about-ragbox.txt",
        "category": "documentation"
      }
    }
  ]
}
```

## Search Without Answer Generation

For retrieval only (no LLM call):

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "embedding providers"
  }'
```

## Access the Admin UI

Open your browser to:

```
http://localhost:8080/admin
```

The Admin UI provides:
- Document management
- Configuration overview
- Chunking preview
- Analytics dashboard
- Real-time stats

## Automatic Ingestion

To automatically ingest documents from a directory, configure the ingestion settings:

```bash
docker run -d \
  -p 8080:8080 \
  -e OPENAI_API_KEY=$OPENAI_API_KEY \
  -e INGESTION_ENABLED=true \
  -e INGESTION_RUN_ON_STARTUP=true \
  -e INGESTION_SCHEDULE="1h" \
  -v /path/to/docs:/data/docs \
  llm4s/rag-in-a-box:latest
```

This will:
1. Ingest all documents on startup
2. Re-scan the directory every hour
3. Only re-index changed documents

## Next Steps

- [Configuration Guide](/guide/configuration) - Customize embedding models, chunking, and more
- [Document API](/api/documents) - Full document management API
- [External Ingesters](/guide/external-ingesters) - Build custom ingestion pipelines
- [Authentication](/guide/authentication) - Set up user authentication

## Troubleshooting

### Database Connection Failed

Make sure PostgreSQL is running and has the pgvector extension:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

### No LLM Response

Check that your API key is set:

```bash
curl http://localhost:8080/api/v1/config
```

The `llm.configured` field should be `true`.

### Documents Not Indexed

Check the stats endpoint:

```bash
curl http://localhost:8080/api/v1/stats
```

If `documentCount` is 0, verify your ingestion settings or manually upload documents.

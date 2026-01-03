---
layout: home
title: Home
nav_order: 1
description: "RAG in a Box - A turnkey RAG solution powered by LLM4S"
permalink: /
---

# RAG in a Box
{: .fs-9 }

A turnkey RAG solution: start a Docker container, point it at documents, and immediately have a production-ready RAG system with great retrieval quality.
{: .fs-6 .fw-300 }

[Get Started](/getting-started/quickstart){: .btn .btn-primary .fs-5 .mb-4 .mb-md-0 .mr-2 }
[View on GitHub](https://github.com/llm4s/rag-in-a-box){: .btn .fs-5 .mb-4 .mb-md-0 }

---

## What is RAG in a Box?

RAG in a Box is a complete Retrieval-Augmented Generation (RAG) system that works out of the box. It's designed for:

- **Zero-config startup**: Just mount your documents directory and start querying
- **Production-ready**: Built-in authentication, permissions, and observability
- **Great retrieval quality**: Hybrid search with semantic + keyword fusion
- **Flexible ingestion**: Built-in support for files, URLs, databases, or custom ingesters

## Key Features

### Document Ingestion
- **File watcher**: Automatically ingest from local directories
- **URL ingestion**: Fetch and index web content
- **External ingesters**: Sync API for Confluence, SharePoint, custom sources
- **Incremental sync**: Only re-index changed documents

### Intelligent Search
- **Hybrid retrieval**: Combines semantic and keyword search
- **Permission-aware**: Documents filtered by user access
- **Configurable chunking**: Multiple strategies for different content types

### Enterprise Ready
- **Authentication**: Open, Basic, or OAuth modes
- **Permission model**: Users, groups, and collection-based access
- **Analytics**: Query logging, latency metrics, feedback collection
- **Admin UI**: Dashboard for monitoring and management

## Quick Example

```bash
# Start RAG in a Box with Docker
docker run -d \
  -p 8080:8080 \
  -v /path/to/docs:/data/docs \
  -e OPENAI_API_KEY=$OPENAI_API_KEY \
  llm4s/rag-in-a-box

# Upload a document
curl -X POST http://localhost:8080/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{"content": "Your document text...", "filename": "doc.txt"}'

# Query the system
curl -X POST http://localhost:8080/api/v1/query \
  -H "Content-Type: application/json" \
  -d '{"question": "What is this document about?"}'
```

## Architecture

RAG in a Box is powered by [LLM4S](https://llm4s.org), a comprehensive Scala framework for building LLM applications.

```
┌─────────────────────────────────────────────────────────────┐
│                      RAG in a Box                            │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │   Admin UI  │  │  REST API   │  │   External Ingesters │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ Auth/Perms  │  │   RAG Core  │  │    Query Analytics   │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────────────────────┐│
│  │                      LLM4S Core                          ││
│  │  Embeddings │ LLM Clients │ Vector Search │ Chunking    ││
│  └─────────────────────────────────────────────────────────┘│
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────────────────────────────────┐│
│  │  PostgreSQL │  │           pgvector Extension            ││
│  └─────────────┘  └─────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

## Next Steps

- [Quick Start Guide](/getting-started/quickstart) - Get up and running in 5 minutes
- [Configuration](/guide/configuration) - Customize your deployment
- [API Reference](/api/documents) - Complete API documentation
- [External Ingesters](/guide/external-ingesters) - Build custom data pipelines

---

## About

RAG in a Box is open source software released under the MIT License. It's part of the [LLM4S](https://llm4s.org) ecosystem.

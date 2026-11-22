---
layout: home
title: Home
nav_order: 1
description: "RAG in a Box - A turnkey RAG server and demonstrator for LLM4S"
permalink: /
---

# RAG in a Box
{: .fs-9 }

A turnkey RAG server: start it with Docker Compose, point it at documents, and query them over a
REST API or the admin UI. It is also a working demonstrator of the [LLM4S](https://llm4s.org)
Scala library.
{: .fs-6 .fw-300 }

[Get Started](/getting-started/quickstart){: .btn .btn-primary .fs-5 .mb-4 .mb-md-0 .mr-2 }
[View on GitHub](https://github.com/llm4s/rag_in_a_box){: .btn .fs-5 .mb-4 .mb-md-0 }

---

## Features

### Ingestion
- **Directory, URL, database, web-crawler and S3 sources**, on a schedule or on demand, with
  incremental per-source sync (only changed documents are re-indexed)
- **File upload** of PDF, DOCX, HTML, Markdown and more (text extracted with llm4s's Tika
  extractor)
- **Sync API** for external ingesters (Confluence, SharePoint, custom pipelines) with scoped
  access tokens
- **Ingestion policy**: allowed directory roots and SSRF-safe URL fetching

### Retrieval and answers
- **Hybrid search**: pgvector similarity plus keyword search, fused with RRF or weighted fusion
- **Optional reranking** (Cohere or LLM) and LLM query rewriting
- **Chunking strategies**: simple, sentence, markdown and semantic, configurable per collection
  and per file type
- **Chat sessions** with conversation history, and Server-Sent Events streaming
- **Runtime settings** (topK, fusion, system prompt, chunking) changeable without a restart

### Access control
- **Auth modes**: open, basic (username/password with JWTs) and OAuth2/OIDC (Google, Azure AD,
  Okta, Keycloak, custom), plus a static API key and scoped `rat_` access tokens in any mode
- **Permission mode**: hierarchical collections with `queryableBy` users/groups and per-document
  `readableBy`, enforced at query time

### Operations
- **Query analytics**: every query logged with latencies, token usage and estimated cost
  (llm4s tracing), user feedback, and optimization suggestions
- **A/B experiments** with Welch's t-tests and two-proportion z-tests
- **Admin UI** (Vue) served at `/admin`, with live updates over WebSocket
- **Production hardening**: Flyway migrations, a shared HikariCP pool, rate limiting, request
  size limits, CORS, PBKDF2 password hashing, Prometheus metrics, health probes, Helm chart

## Quick Example

```bash
git clone https://github.com/llm4s/rag_in_a_box.git && cd rag_in_a_box
cp .env.example .env              # set OPENAI_API_KEY
docker compose up -d --build

# Add a document
curl -X POST http://localhost:8080/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{"content": "Your document text...", "filename": "doc.txt"}'

# Ask a question
curl -X POST http://localhost:8080/api/v1/query \
  -H "Content-Type: application/json" \
  -d '{"question": "What is this document about?"}'
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                       RAG in a Box                          │
├─────────────────────────────────────────────────────────────┤
│   Admin UI (/admin)   │   REST API (/api/v1)   │  /ws, /metrics │
├─────────────────────────────────────────────────────────────┤
│  Rate limit · size limit · CORS · Authenticator · Authz     │
├─────────────────────────────────────────────────────────────┤
│  Documents · Sync · Query/Chat · Ingestion · Analytics ·    │
│  Experiments · Runtime config · Permissions                 │
├─────────────────────────────────────────────────────────────┤
│                         LLM4S                               │
│  RAG pipeline · provider registry · embeddings · chunkers · │
│  pgvector hybrid search · reranking · tracing · permissions │
├─────────────────────────────────────────────────────────────┤
│        PostgreSQL + pgvector (Flyway-managed schema)        │
└─────────────────────────────────────────────────────────────┘
```

## Next Steps

- [Quick Start Guide](/getting-started/quickstart) - running in a few minutes
- [Configuration](/guide/configuration) - every setting
- [API Reference](/api/) - every endpoint, with its auth requirements
- [External Ingesters](/guide/external-ingesters) - push documents with the sync API

---

## About

RAG in a Box is open source software released under the MIT License. It's part of the
[LLM4S](https://llm4s.org) ecosystem.

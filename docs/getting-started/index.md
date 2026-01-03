---
layout: page
title: Getting Started
nav_order: 2
has_children: true
---

# Getting Started

Everything you need to get RAG in a Box up and running.
{: .fs-6 .fw-300 }

## Quick Links

- [Quick Start](/getting-started/quickstart) - 5-minute setup guide
- [Configuration](/getting-started/configuration) - Environment variables and settings
- [Docker Deployment](/getting-started/docker) - Production Docker setup

## Requirements

| Component | Minimum Version | Notes |
|-----------|-----------------|-------|
| Docker | 20.10+ | For container deployment |
| PostgreSQL | 15+ | With pgvector extension |
| Java | 21+ | For running from source |

## Supported LLM Providers

| Provider | Embedding | Chat Completion |
|----------|-----------|-----------------|
| OpenAI | text-embedding-3-small, text-embedding-3-large, text-embedding-ada-002 | gpt-4o, gpt-4o-mini, gpt-4-turbo |
| Anthropic | voyage-3 (via Voyage AI) | claude-sonnet-4-20250514, claude-3-haiku |
| Ollama | nomic-embed-text, all-minilm | llama3, mistral, mixtral |

## Architecture Overview

RAG in a Box follows a simple but powerful architecture:

1. **Document Ingestion**: Documents are chunked and embedded
2. **Vector Storage**: Embeddings stored in PostgreSQL with pgvector
3. **Hybrid Search**: Combines semantic search with keyword matching
4. **Answer Generation**: LLM synthesizes answers from retrieved context

All components are included in the Docker image - just add your documents and API keys.

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

- [Quick Start](/getting-started/quickstart) - run it with Docker Compose or from source
- [Configuration](/guide/configuration) - environment variables and settings
- [Production Deployment](/production-deployment) - Docker Compose and Kubernetes in production
- [Helm Deployment](/guide/helm-deployment) - the Helm chart

## Requirements

| Component | Version | Notes |
|-----------|---------|-------|
| Docker + Docker Compose | 20.10+ / v2 | For the container quick start |
| PostgreSQL | 15+ with pgvector | Included in `docker-compose.yml` |
| Java + sbt | JDK 21 | Only for running from source |
| Node.js | 20+ | Only for building the admin UI from source |

An API key for an LLM/embedding provider (e.g. OpenAI), or a local [Ollama](https://ollama.com).

## Providers

Providers come from [llm4s](https://llm4s.org)'s provider registry, so anything llm4s supports
can be configured:

| Setting | Examples |
|---------|----------|
| `LLM_MODEL` (`provider/model`) | `openai/gpt-4o` (default), `anthropic/claude-sonnet-4-5`, `ollama/llama3.1` |
| `EMBEDDING_PROVIDER` + `EMBEDDING_MODEL` | `openai` + `text-embedding-3-small` (default), `voyage` + `voyage-3`, `ollama` + `nomic-embed-text` |

`GET /api/v1/config/providers` lists the providers and the models llm4s knows for each, and
whether each one is configured.

## How It Works

1. **Ingestion**: documents are chunked (llm4s chunkers: simple, sentence, markdown, semantic)
   and embedded.
2. **Storage**: chunks and embeddings live in PostgreSQL with pgvector, alongside a keyword index.
3. **Hybrid search**: vector and keyword results are fused (RRF or weighted), optionally
   reranked.
4. **Answer generation**: the LLM answers from the retrieved context.

Collections, users and groups can restrict who may query what (permission mode), and every
query is logged for analytics and A/B experiments.

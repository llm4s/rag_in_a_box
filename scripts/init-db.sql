-- RAG in a Box - Database Initialization Script
-- This script runs automatically when PostgreSQL container starts for the first time

-- Enable pgvector extension for vector similarity search
CREATE EXTENSION IF NOT EXISTS vector;

-- Log success
DO $$
BEGIN
    RAISE NOTICE 'pgvector extension enabled successfully';
END $$;

-- Document registry table for tracking ingested documents
-- Persists document metadata and content hashes across restarts
CREATE TABLE IF NOT EXISTS document_registry (
    document_id TEXT PRIMARY KEY,
    content_hash TEXT NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    metadata JSONB DEFAULT '{}',
    indexed_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Index for finding stale documents
CREATE INDEX IF NOT EXISTS idx_document_registry_indexed_at
    ON document_registry(indexed_at);

-- Sync tracking table for last sync time
CREATE TABLE IF NOT EXISTS sync_status (
    id INTEGER PRIMARY KEY DEFAULT 1,
    last_sync_time TIMESTAMPTZ,
    CONSTRAINT single_row CHECK (id = 1)
);

-- Insert initial sync status row
INSERT INTO sync_status (id, last_sync_time)
VALUES (1, NULL)
ON CONFLICT (id) DO NOTHING;

-- The rag_embeddings table will be created automatically by PgVectorStore
-- when the application starts. The schema includes:
--
-- CREATE TABLE IF NOT EXISTS rag_embeddings (
--     id TEXT PRIMARY KEY,
--     embedding vector,
--     embedding_dim INTEGER NOT NULL,
--     content TEXT,
--     metadata JSONB DEFAULT '{}',
--     created_at TIMESTAMPTZ DEFAULT NOW()
-- );
--
-- Indexes are also created automatically:
-- - idx_rag_embeddings_dim ON rag_embeddings(embedding_dim)
-- - idx_rag_embeddings_created ON rag_embeddings(created_at)
-- - idx_rag_embeddings_metadata ON rag_embeddings USING GIN(metadata)

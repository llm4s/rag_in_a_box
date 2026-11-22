-- V2: index tuning (issue #18).

-- Incremental sync lists documents changed since a point in time (`WHERE updated_at > ?
-- ORDER BY updated_at`)
CREATE INDEX IF NOT EXISTS idx_document_registry_updated_at ON document_registry(updated_at);

-- Redundant: the UNIQUE (document_id, chunk_index) index already serves lookups by document_id
DROP INDEX IF EXISTS idx_chunk_registry_document;

-- Session lists filter out archived sessions and order by recency; an index on the boolean
-- alone is almost never selective enough to be used
DROP INDEX IF EXISTS idx_chat_sessions_archived;
CREATE INDEX IF NOT EXISTS idx_chat_sessions_archived_updated_at ON chat_sessions(is_archived, updated_at DESC);

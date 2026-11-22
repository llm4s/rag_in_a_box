-- The schema DDL ragbox ran from code at every startup before it adopted Flyway (release
-- 0.x up to the commit that introduced db/migration), copied verbatim from the components in
-- the order they ran. MigrationSpec builds a "legacy" database with it and checks that
-- Flyway adopts that database and ends with exactly the schema of a fresh one.
--
-- Do not edit: it records what existing deployments have.

-- PgDocumentRegistry.initialize
CREATE TABLE IF NOT EXISTS document_registry (
    document_id TEXT PRIMARY KEY,
    content_hash TEXT NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    metadata JSONB DEFAULT '{}',
    collection TEXT,
    indexed_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_document_registry_indexed_at
    ON document_registry(indexed_at);
DO $$ BEGIN
    ALTER TABLE document_registry ADD COLUMN collection TEXT;
EXCEPTION
    WHEN duplicate_column THEN NULL;
END $$;
ALTER TABLE document_registry ADD COLUMN IF NOT EXISTS readable_by INTEGER[] NOT NULL DEFAULT '{}';
ALTER TABLE document_registry ADD COLUMN IF NOT EXISTS source_id TEXT;
CREATE INDEX IF NOT EXISTS idx_document_registry_source_id ON document_registry(source_id);
CREATE INDEX IF NOT EXISTS idx_document_registry_collection
    ON document_registry(collection);
CREATE TABLE IF NOT EXISTS sync_status (
    id INTEGER PRIMARY KEY DEFAULT 1,
    last_sync_time TIMESTAMPTZ,
    CONSTRAINT single_row CHECK (id = 1)
);
INSERT INTO sync_status (id, last_sync_time)
VALUES (1, NULL)
ON CONFLICT (id) DO NOTHING;

-- PgChunkStore.initialize
CREATE TABLE IF NOT EXISTS chunk_registry (
    id SERIAL PRIMARY KEY,
    document_id TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    content_length INTEGER NOT NULL,
    metadata JSONB DEFAULT '{}',
    headings TEXT[] DEFAULT '{}',
    is_code_block BOOLEAN DEFAULT FALSE,
    language TEXT,
    start_offset INTEGER,
    end_offset INTEGER,
    collection TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(document_id, chunk_index)
);
CREATE INDEX IF NOT EXISTS idx_chunk_registry_document
    ON chunk_registry(document_id);
CREATE INDEX IF NOT EXISTS idx_chunk_registry_collection
    ON chunk_registry(collection);
CREATE INDEX IF NOT EXISTS idx_chunk_registry_length
    ON chunk_registry(content_length);
ALTER TABLE chunk_registry ADD COLUMN IF NOT EXISTS chunk_id TEXT;
UPDATE chunk_registry SET collection = NULL WHERE collection = '';

-- RuntimeConfigManager.createTables
CREATE TABLE IF NOT EXISTS config_history (
    id SERIAL PRIMARY KEY,
    field TEXT NOT NULL,
    old_value TEXT,
    new_value TEXT,
    changed_at TIMESTAMPTZ DEFAULT NOW(),
    changed_by TEXT
);
CREATE INDEX IF NOT EXISTS idx_config_history_field
    ON config_history(field);
CREATE INDEX IF NOT EXISTS idx_config_history_changed_at
    ON config_history(changed_at DESC);
CREATE TABLE IF NOT EXISTS runtime_config (
    field TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- PgCollectionConfigRegistry.initialize
CREATE TABLE IF NOT EXISTS collection_configs (
    collection TEXT PRIMARY KEY,
    strategy TEXT,
    target_size INTEGER,
    max_size INTEGER,
    overlap INTEGER,
    file_type_strategies JSONB DEFAULT '{}',
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_collection_configs_updated
    ON collection_configs(updated_at DESC);

-- QueryLogRegistry.initialize
CREATE TABLE IF NOT EXISTS query_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    query_text TEXT NOT NULL,
    collection_pattern TEXT,
    user_id TEXT,

    embedding_latency_ms INTEGER,
    search_latency_ms INTEGER,
    llm_latency_ms INTEGER,
    total_latency_ms INTEGER NOT NULL,

    chunks_retrieved INTEGER NOT NULL DEFAULT 0,
    chunks_used INTEGER NOT NULL DEFAULT 0,
    answer_tokens INTEGER,

    user_rating INTEGER,
    relevant_chunks TEXT[],
    feedback_comment TEXT,

    experiment_id VARCHAR(64),
    config_snapshot JSONB,

    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_query_logs_created_at
    ON query_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_query_logs_collection
    ON query_logs(collection_pattern);
CREATE INDEX IF NOT EXISTS idx_query_logs_user
    ON query_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_query_logs_experiment
    ON query_logs(experiment_id);
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='query_logs' AND column_name='experiment_id') THEN
        ALTER TABLE query_logs ADD COLUMN experiment_id VARCHAR(64);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='query_logs' AND column_name='config_snapshot') THEN
        ALTER TABLE query_logs ADD COLUMN config_snapshot JSONB;
    END IF;
END $$;
ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS prompt_tokens INTEGER;
ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS embedding_tokens INTEGER;
ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS cost_usd DOUBLE PRECISION;
ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS variant VARCHAR(32);
ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS failed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE query_logs ADD COLUMN IF NOT EXISTS error_message TEXT;
UPDATE query_logs
SET variant = split_part(experiment_id, ':', 2), experiment_id = split_part(experiment_id, ':', 1)
WHERE variant IS NULL AND experiment_id LIKE '%:%';
CREATE INDEX IF NOT EXISTS idx_query_logs_experiment_variant
    ON query_logs(experiment_id, variant);

-- UserRegistry.initialize
CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'user',
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_users_username
    ON users(username);

-- AccessTokenRegistry.initialize
CREATE TABLE IF NOT EXISTS access_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    token_hash TEXT NOT NULL,
    token_prefix TEXT NOT NULL,
    scopes TEXT[] NOT NULL,
    collections TEXT[],
    created_by INTEGER REFERENCES users(id),
    expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_access_tokens_name
    ON access_tokens(name);

-- ExperimentRegistry.initialize
CREATE TABLE IF NOT EXISTS experiments (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'draft',
    baseline_config JSONB NOT NULL,
    variant_config JSONB NOT NULL,
    traffic_split DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    collection VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_experiments_status
    ON experiments(status);
CREATE INDEX IF NOT EXISTS idx_experiments_created_at
    ON experiments(created_at);
UPDATE experiments SET status = 'completed', ended_at = NOW()
WHERE status = 'running'
  AND id <> (SELECT id FROM experiments WHERE status = 'running'
             ORDER BY started_at DESC NULLS LAST, id LIMIT 1);
CREATE UNIQUE INDEX IF NOT EXISTS uq_experiments_single_running
    ON experiments ((status)) WHERE status = 'running';

-- ChatSessionRegistry.initialize
CREATE TABLE IF NOT EXISTS chat_sessions (
    id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(255),
    collection_pattern VARCHAR(255) DEFAULT '*',
    message_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_archived BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_updated_at
    ON chat_sessions(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_archived
    ON chat_sessions(is_archived);
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS owner_id VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_owner
    ON chat_sessions(owner_id, updated_at DESC);
CREATE TABLE IF NOT EXISTS chat_messages (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    contexts JSONB,
    usage_info JSONB,
    rating INTEGER,
    query_log_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    message_index INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_chat_messages_session_id
    ON chat_messages(session_id, message_index);
CREATE INDEX IF NOT EXISTS idx_chat_messages_query_log_id
    ON chat_messages(query_log_id);
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_chat_messages_session_index') THEN
        UPDATE chat_messages m SET message_index = r.rn - 1
        FROM (
            SELECT id, ROW_NUMBER() OVER (
                       PARTITION BY session_id ORDER BY message_index, created_at, id) AS rn
            FROM chat_messages
            WHERE session_id IN (
                SELECT session_id FROM chat_messages
                GROUP BY session_id, message_index HAVING COUNT(*) > 1)
        ) r
        WHERE m.id = r.id AND m.message_index <> r.rn - 1;
        ALTER TABLE chat_messages
            ADD CONSTRAINT uq_chat_messages_session_index UNIQUE (session_id, message_index);
    END IF;
END $$;

-- PgSessionRegistry.initializeTables (oauth mode with postgres session storage)
CREATE TABLE IF NOT EXISTS oauth_sessions (
  session_id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(255) NOT NULL,
  email VARCHAR(255),
  name VARCHAR(255),
  groups TEXT[] NOT NULL DEFAULT '{}',
  provider VARCHAR(50) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_oauth_sessions_user_id ON oauth_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_oauth_sessions_expires_at ON oauth_sessions(expires_at);
CREATE TABLE IF NOT EXISTS oauth_auth_state (
  state VARCHAR(64) PRIMARY KEY,
  code_verifier VARCHAR(128) NOT NULL,
  redirect_after_login VARCHAR(512),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP NOT NULL,
  nonce VARCHAR(128),
  browser_binding_hash VARCHAR(128)
);
CREATE INDEX IF NOT EXISTS idx_oauth_auth_state_expires_at ON oauth_auth_state(expires_at);

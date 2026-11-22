-- A database as an early ragbox release left it, before most of the later in-code upgrades:
-- no document_registry.collection/readable_by/source_id, no chunk_registry.chunk_id, no
-- query_logs token/variant/failure columns, no chat_sessions.owner_id, no unique message index,
-- no single-running-experiment index, comma-joined oauth groups, no runtime-settings or
-- collection-config tables. MigrationSpec checks that the migrations bring it to exactly the
-- schema of a fresh database.

CREATE TABLE document_registry (
    document_id TEXT PRIMARY KEY,
    content_hash TEXT NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    metadata JSONB DEFAULT '{}',
    indexed_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_document_registry_indexed_at ON document_registry(indexed_at);

CREATE TABLE sync_status (
    id INTEGER PRIMARY KEY DEFAULT 1,
    last_sync_time TIMESTAMPTZ,
    CONSTRAINT single_row CHECK (id = 1)
);
INSERT INTO sync_status (id, last_sync_time) VALUES (1, NULL);

CREATE TABLE chunk_registry (
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
CREATE INDEX idx_chunk_registry_document ON chunk_registry(document_id);
CREATE INDEX idx_chunk_registry_collection ON chunk_registry(collection);
CREATE INDEX idx_chunk_registry_length ON chunk_registry(content_length);

CREATE TABLE query_logs (
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
CREATE INDEX idx_query_logs_created_at ON query_logs(created_at);
CREATE INDEX idx_query_logs_collection ON query_logs(collection_pattern);
CREATE INDEX idx_query_logs_user ON query_logs(user_id);
CREATE INDEX idx_query_logs_experiment ON query_logs(experiment_id);

CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'user',
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_users_username ON users(username);

CREATE TABLE access_tokens (
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
CREATE INDEX idx_access_tokens_name ON access_tokens(name);

CREATE TABLE experiments (
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
CREATE INDEX idx_experiments_status ON experiments(status);
CREATE INDEX idx_experiments_created_at ON experiments(created_at);

CREATE TABLE chat_sessions (
    id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(255),
    collection_pattern VARCHAR(255) DEFAULT '*',
    message_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_archived BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_chat_sessions_updated_at ON chat_sessions(updated_at DESC);
CREATE INDEX idx_chat_sessions_archived ON chat_sessions(is_archived);

CREATE TABLE chat_messages (
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
CREATE INDEX idx_chat_messages_session_id ON chat_messages(session_id, message_index);
CREATE INDEX idx_chat_messages_query_log_id ON chat_messages(query_log_id);

CREATE TABLE oauth_sessions (
    session_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    name VARCHAR(255),
    groups TEXT,
    provider VARCHAR(50) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_oauth_sessions_user_id ON oauth_sessions(user_id);
CREATE INDEX idx_oauth_sessions_expires_at ON oauth_sessions(expires_at);

CREATE TABLE oauth_auth_state (
    state VARCHAR(64) PRIMARY KEY,
    code_verifier VARCHAR(128) NOT NULL,
    redirect_after_login VARCHAR(512),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_oauth_auth_state_expires_at ON oauth_auth_state(expires_at);

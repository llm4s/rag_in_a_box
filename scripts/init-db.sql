-- RAG in a Box - database initialisation
--
-- Run once by the PostgreSQL container on first start (docker-entrypoint-initdb.d).
-- Only the pgvector extension is created here: ragbox creates and upgrades its own tables
-- with Flyway migrations at startup (src/main/resources/db/migration), and llm4s creates
-- the vector/keyword tables. Creating the extension needs superuser rights, which the
-- application's database user may not have.
CREATE EXTENSION IF NOT EXISTS vector;

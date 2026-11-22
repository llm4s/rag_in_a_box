-- V3: drop a redundant index (issue #48).

-- users.username is UNIQUE, so its unique constraint's index (users_username_key) already serves
-- lookups by username; idx_users_username only duplicated it, at a cost on every write
DROP INDEX IF EXISTS idx_users_username;

-- Additive and repeatable; preserve existing users/passwords/records.
ALTER TABLE users ADD COLUMN IF NOT EXISTS signature VARCHAR(240) DEFAULT '';
ALTER TABLE users ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
UPDATE users SET updated_at = COALESCE(created_at, CURRENT_TIMESTAMP) WHERE updated_at IS NULL;

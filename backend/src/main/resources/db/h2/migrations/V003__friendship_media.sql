CREATE TABLE IF NOT EXISTS friendships (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_low BIGINT NOT NULL, user_high BIGINT NOT NULL, requester_id BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
 CONSTRAINT uk_friend_pair UNIQUE(user_low,user_high)
);
CREATE INDEX IF NOT EXISTS idx_friend_low ON friendships(user_low,status);
CREATE INDEX IF NOT EXISTS idx_friend_high ON friendships(user_high,status);
CREATE TABLE IF NOT EXISTS media_assets (
 id VARCHAR(32) PRIMARY KEY, owner_id BIGINT NOT NULL, storage_name VARCHAR(80) NOT NULL,
 purpose VARCHAR(16) NOT NULL, content_type VARCHAR(50) NOT NULL, size_bytes BIGINT NOT NULL,
 width INT NOT NULL, height INT NOT NULL, created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_media_owner ON media_assets(owner_id,created_at);

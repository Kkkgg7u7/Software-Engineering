-- Additive MySQL 8 migration, after V002. No existing rows are deleted.
CREATE TABLE IF NOT EXISTS friendships (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_low BIGINT NOT NULL, user_high BIGINT NOT NULL, requester_id BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL, created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
 UNIQUE KEY uk_friend_pair(user_low,user_high),
 KEY idx_friend_low(user_low,status), KEY idx_friend_high(user_high,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS media_assets (
 id VARCHAR(32) PRIMARY KEY, owner_id BIGINT NOT NULL, storage_name VARCHAR(80) NOT NULL,
 purpose VARCHAR(16) NOT NULL, content_type VARCHAR(50) NOT NULL, size_bytes BIGINT NOT NULL,
 width INT NOT NULL, height INT NOT NULL, created_at DATETIME NOT NULL,
 KEY idx_media_owner(owner_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS conversations (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 type VARCHAR(16) NOT NULL, direct_key VARCHAR(64) UNIQUE, code VARCHAR(8) UNIQUE,
 title VARCHAR(40), created_by BIGINT NOT NULL, closed BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS conversation_members (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, conversation_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE, read_message_id BIGINT NOT NULL DEFAULT 0, joined_at TIMESTAMP NOT NULL,
 CONSTRAINT uq_conversation_member UNIQUE(conversation_id,user_id)
);
CREATE INDEX idx_member_user ON conversation_members(user_id,active);
CREATE TABLE IF NOT EXISTS conversation_messages (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, conversation_id BIGINT NOT NULL, sender_id BIGINT NOT NULL,
 client_message_id VARCHAR(64) NOT NULL, content_type VARCHAR(24) NOT NULL,
 content VARCHAR(4000), media_id VARCHAR(32), related_code VARCHAR(8), created_at TIMESTAMP NOT NULL,
 CONSTRAINT uq_chat_client UNIQUE(sender_id,client_message_id)
);
CREATE INDEX idx_chat_history ON conversation_messages(conversation_id,id);
CREATE INDEX idx_chat_media ON conversation_messages(media_id);

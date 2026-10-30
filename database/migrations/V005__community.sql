CREATE TABLE IF NOT EXISTS posts (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, author_id BIGINT NOT NULL, client_request_id VARCHAR(64) NOT NULL,
 content VARCHAR(4000) NOT NULL, deleted BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP NOT NULL,
 CONSTRAINT uq_post_request UNIQUE(author_id,client_request_id)
);
CREATE INDEX idx_post_feed ON posts(deleted,id);
CREATE INDEX idx_post_author ON posts(author_id,deleted,id);
CREATE TABLE IF NOT EXISTS post_images (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, post_id BIGINT NOT NULL, media_id VARCHAR(32) NOT NULL, sort_order INT NOT NULL,
 CONSTRAINT uq_post_image UNIQUE(post_id,media_id)
);
CREATE INDEX idx_post_image_media ON post_images(media_id);
CREATE TABLE IF NOT EXISTS post_likes (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, post_id BIGINT NOT NULL, user_id BIGINT NOT NULL, created_at TIMESTAMP NOT NULL,
 CONSTRAINT uq_post_like UNIQUE(post_id,user_id)
);
CREATE TABLE IF NOT EXISTS post_comments (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, post_id BIGINT NOT NULL, author_id BIGINT NOT NULL, client_request_id VARCHAR(64) NOT NULL,
 content VARCHAR(1000) NOT NULL, deleted BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP NOT NULL,
 CONSTRAINT uq_comment_request UNIQUE(author_id,client_request_id)
);
CREATE INDEX idx_comment_history ON post_comments(post_id,deleted,id);

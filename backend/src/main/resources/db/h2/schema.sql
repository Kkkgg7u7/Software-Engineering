-- =====================================================================
-- H2 演示 / 集成测试建表脚本（MySQL 兼容模式）
-- 生产 MySQL 脚本见 database/schema.sql
-- =====================================================================

CREATE TABLE IF NOT EXISTS users (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL,
    password    VARCHAR(120) NOT NULL,
    nickname    VARCHAR(50)  NOT NULL,
    avatar      VARCHAR(255),
    signature   VARCHAR(240) DEFAULT '',
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    total_games INT DEFAULT 0,
    win_games   INT DEFAULT 0,
    -- 认证版本号：每次登录 +1。JWT 里带上签发时的版本，
    -- 服务端比对不上就说明"该账号在别处重新登录过"，旧 Token 立即失效。
    -- 这是防止同一账号在多台设备上同时在线（导致多人共用一个座位）的关键。
    auth_version INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_users_username UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS rooms (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_code      VARCHAR(12)  NOT NULL,
    room_name      VARCHAR(60)  NOT NULL,
    password       VARCHAR(120),
    max_players    INT NOT NULL DEFAULT 8,
    werewolf_count INT NOT NULL DEFAULT 2,
    ai_count       INT NOT NULL DEFAULT 0,
    -- AI 默认关闭：应用层 CreateRoomRequest.allowAi 默认 false，
    -- 这里的 DDL 默认值同样取 FALSE，避免两套默认值语义不一致。
    allow_ai       BOOLEAN NOT NULL DEFAULT FALSE,
    public_room    BOOLEAN NOT NULL DEFAULT TRUE,
    game_status    VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    created_by     BIGINT,
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_rooms_code UNIQUE (room_code)
);

CREATE TABLE IF NOT EXISTS room_players (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id  BIGINT NOT NULL,
    user_id  BIGINT NOT NULL,
    nickname VARCHAR(50),
    seat_id  INT NOT NULL,
    role     VARCHAR(20),
    is_ai    BOOLEAN NOT NULL DEFAULT FALSE,
    is_alive BOOLEAN NOT NULL DEFAULT TRUE,
    ready    BOOLEAN NOT NULL DEFAULT FALSE,
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS messages (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id         BIGINT NOT NULL,
    sender_id       BIGINT,
    receiver_id     BIGINT,
    content         VARCHAR(1000) NOT NULL,
    message_type    VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    channel         VARCHAR(20) NOT NULL DEFAULT 'LOBBY',
    sender_nickname VARCHAR(50),
    is_ai           BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS game_records (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id     BIGINT,
    room_code   VARCHAR(12),
    winner      VARCHAR(20),
    result      VARCHAR(500),
    detail_json CLOB,
    rounds      INT,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_messages_room ON messages (room_id, id);
CREATE INDEX IF NOT EXISTS idx_room_players_room ON room_players (room_id);
CREATE INDEX IF NOT EXISTS idx_game_records_room ON game_records (room_id);

-- =====================================================================
-- 基于 AI 增强的多人在线狼人杀系统 —— MySQL 8 建库脚本
--
-- 用途：生产 / 答辩演示环境（SPRING_PROFILES_ACTIVE=mysql）的数据库初始化。
-- 结构来源：backend/src/main/resources/db/h2/schema.sql（H2 演示库），
--           两边的表名 / 列名 / 语义必须保持一致，差异只在数据类型与语法：
--             H2 TIMESTAMP -> MySQL DATETIME
--             H2 CLOB      -> MySQL TEXT
--             H2 BOOLEAN   -> MySQL TINYINT(1)
--             H2 独立 CREATE INDEX -> MySQL 表内 KEY（MySQL 8 不支持 CREATE INDEX IF NOT EXISTS）
--
-- 执行方式：
--   mysql -h 127.0.0.1 -P 3306 -u root -p < database/schema.sql
--   或进入客户端后：source database/schema.sql
--
-- 规格对照：docs/DATABASE.md 第九节《database/schema.sql 的规格》与本脚本一致，
--           并采纳了其中两处改进建议（allow_ai 默认 FALSE、detail_json 用 LONGTEXT）。
--
-- 排序规则说明：这里用 MySQL 8 默认的 utf8mb4_0900_ai_ci（大小写/重音不敏感，
--               中文排序更合理）。若将来需要兼容 MySQL 5.7，把全部
--               utf8mb4_0900_ai_ci 替换成 utf8mb4_unicode_ci 即可。
--
-- 注意：脚本是幂等的（CREATE ... IF NOT EXISTS），不会删除已有数据。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `werewolf`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `werewolf`;

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. users —— 用户表
--    密码列存 BCrypt 哈希（长度 60，留 120 以兼容算法升级），
--    任何接口都不会把该列返回给前端。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `users` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`    VARCHAR(50)  NOT NULL                COMMENT '登录名，唯一',
    `password`    VARCHAR(120) NOT NULL                COMMENT 'BCrypt 密码哈希',
    `nickname`    VARCHAR(50)  NOT NULL                COMMENT '展示昵称，全局唯一（应用层校验）',
    `avatar`      VARCHAR(255) DEFAULT NULL            COMMENT '头像地址（本地素材 /assets/avatars/*.svg）',
    `signature`   VARCHAR(240) NOT NULL DEFAULT '' COMMENT '个性签名',
    `updated_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '资料或密码更新时间',
    `total_games` INT          NOT NULL DEFAULT 0      COMMENT '累计对局数（排行榜用）',
    `win_games`   INT          NOT NULL DEFAULT 0      COMMENT '累计胜场（排行榜用）',
    -- 认证版本号：每次登录 +1。JWT 里带签发时的版本，服务端比对不上即判定"该账号已在别处登录"。
    -- 这是防止同一账号在多台设备同时在线（导致多人共用一个座位）的关键字段。
    `auth_version` INT         NOT NULL DEFAULT 0      COMMENT '认证版本号，每次登录自增，用于单会话强制',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_users_username` (`username`),
    KEY `idx_users_nickname` (`nickname`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户表';

-- ---------------------------------------------------------------------
-- 升级脚本：如果 users 表是早期版本创建的（缺少 auth_version 列），
-- 执行下面这一句即可补齐。MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，
-- 若提示 "Duplicate column name" 说明已经加过，可忽略。
-- ---------------------------------------------------------------------
-- ALTER TABLE `users`
--     ADD COLUMN `auth_version` INT NOT NULL DEFAULT 0
--     COMMENT '认证版本号，每次登录自增，用于单会话强制';

-- ---------------------------------------------------------------------
-- 2. rooms —— 房间表
--    只保存房间的配置与生命周期标记（WAITING / PLAYING / ENDED）；
--    对局中的实时状态（座位、准备、身份、投票）只存在于内存 RoomRuntime。
--
--    与 H2 脚本的差异：H2 里 allow_ai 默认 TRUE，本脚本按“基础功能优先、
--    AI 默认关闭”的策略改为默认 0；应用侧（RoomService.createRoom）
--    始终显式写入该列，默认值仅作兜底。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `rooms` (
    role_counts JSON NULL,
    voice_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    `id`             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `room_code`      VARCHAR(12) NOT NULL                COMMENT '6 位房间码，玩家输入它加入',
    `room_name`      VARCHAR(60) NOT NULL                COMMENT '房间名称',
    `password`       VARCHAR(120) DEFAULT NULL           COMMENT 'BCrypt 房间密码，NULL 表示无密码',
    `max_players`    INT         NOT NULL DEFAULT 8      COMMENT '房间最大人数 4~12',
    `werewolf_count` INT         NOT NULL DEFAULT 2      COMMENT '本局狼人数量 1~((人数-1)/2)',
    `ai_count`       INT         NOT NULL DEFAULT 0      COMMENT 'AI NPC 数量，默认 0（可 0~人数-1）',
    `allow_ai`       TINYINT(1)  NOT NULL DEFAULT 0      COMMENT '是否允许 AI 入座：1 允许 / 0 关闭（默认关闭）',
    `public_room`    TINYINT(1)  NOT NULL DEFAULT 1      COMMENT '是否公开房间：1 公开 / 0 仅凭房间码加入',
    `game_status`    VARCHAR(20) NOT NULL DEFAULT 'WAITING' COMMENT '房间状态：WAITING / PLAYING / ENDED',
    `created_by`     BIGINT      DEFAULT NULL            COMMENT '房主 users.id',
    `created_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rooms_code` (`room_code`),
    KEY `idx_rooms_status` (`game_status`, `public_room`),
    CONSTRAINT `fk_rooms_created_by` FOREIGN KEY (`created_by`)
        REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '房间配置表（实时状态在内存 RoomRuntime）';

-- ---------------------------------------------------------------------
-- 3. room_players —— 房间座位表
--    role 只在一局结束后回写（复盘用），对局中的真实身份只存在于 GameEngine 内存。
--
--    注意：AI 座位使用负数 user_id（-1, -2, ...）占位，因此这里**不能**给
--    user_id 建外键，否则 AI 入座会插入失败。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `room_players` (
    `id`        BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `room_id`   BIGINT      NOT NULL                COMMENT 'rooms.id',
    `user_id`   BIGINT      NOT NULL                COMMENT 'users.id；AI 座位为负数（-seatId）',
    `nickname`  VARCHAR(50) DEFAULT NULL            COMMENT '座位昵称快照',
    `seat_id`   INT         NOT NULL                COMMENT '座位号，1-based，整局不变',
    `role`      VARCHAR(20) DEFAULT NULL            COMMENT '身份，仅对局结束后回写：WEREWOLF/SEER/WITCH/VILLAGER',
    `is_ai`     TINYINT(1)  NOT NULL DEFAULT 0      COMMENT '是否 AI NPC',
    `is_alive`  TINYINT(1)  NOT NULL DEFAULT 1      COMMENT '是否存活',
    `ready`     TINYINT(1)  NOT NULL DEFAULT 0      COMMENT '是否已准备',
    `joined_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入座时间',
    PRIMARY KEY (`id`),
    KEY `idx_room_players_room` (`room_id`),
    KEY `idx_room_players_user` (`user_id`),
    CONSTRAINT `fk_room_players_room` FOREIGN KEY (`room_id`)
        REFERENCES `rooms` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '房间座位表';

-- ---------------------------------------------------------------------
-- 4. messages —— 聊天消息表
--    receiver_id 为空 = 公共频道；message_type=WOLF = 狼人频道；
--    其余为点对点私聊。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `messages` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `room_id`         BIGINT        NOT NULL                COMMENT 'rooms.id',
    `sender_id`       BIGINT        DEFAULT NULL            COMMENT '发送者 users.id（系统消息可为空）',
    `receiver_id`     BIGINT        DEFAULT NULL            COMMENT '接收者 users.id，NULL 表示公共频道',
    `content`         VARCHAR(1000) NOT NULL                COMMENT '消息内容',
    `message_type`    VARCHAR(20)   NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC / PRIVATE / WOLF / SYSTEM',
    `channel`         VARCHAR(20)   NOT NULL DEFAULT 'LOBBY'  COMMENT 'LOBBY（等待大厅）/ GAME（对局内）',
    `sender_nickname` VARCHAR(50)   DEFAULT NULL            COMMENT '发送者昵称快照',
    `is_ai`           TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '是否来自 AI NPC',
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    PRIMARY KEY (`id`),
    KEY `idx_messages_room` (`room_id`, `id`),
    KEY `idx_messages_receiver` (`receiver_id`, `id`),
    CONSTRAINT `fk_messages_room` FOREIGN KEY (`room_id`)
        REFERENCES `rooms` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '局内聊天消息表';

-- ---------------------------------------------------------------------
-- 5. game_records —— 对局记录表
--    一局结束后由 RoomService.saveGameRecord 落库，detail_json 存复盘数据
--    （每个座位的身份 / 存活 / 是否 AI + 全部发言）。
--    注意：detail_json 用 LONGTEXT 而不是 TEXT —— H2 的 CLOB 在 MySQL 8 的
--    对应类型是 LONGTEXT（见 docs/DATABASE.md 第八、九节），TEXT 只有 64KB。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `game_records` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `room_id`     BIGINT       DEFAULT NULL            COMMENT 'rooms.id（房间被清理后保留记录，故可为空）',
    `room_code`   VARCHAR(12)  DEFAULT NULL            COMMENT '房间码快照',
    `winner`      VARCHAR(20)  DEFAULT NULL            COMMENT '获胜阵营：VILLAGE / WEREWOLF',
    `result`      VARCHAR(500) DEFAULT NULL            COMMENT '结算说明文本',
    `detail_json` LONGTEXT     DEFAULT NULL            COMMENT '复盘 JSON：players / speeches',
    `rounds`      INT          DEFAULT NULL            COMMENT '本局总轮次（夜晚数）',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '结束时间',
    PRIMARY KEY (`id`),
    KEY `idx_game_records_room` (`room_id`),
    KEY `idx_game_records_created` (`created_at`),
    CONSTRAINT `fk_game_records_room` FOREIGN KEY (`room_id`)
        REFERENCES `rooms` (`id`) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '对局记录表（复盘 / 战绩）';

-- =====================================================================
-- 建库完成。演示账号不在这里插入（BCrypt 哈希必须由 PasswordEncoder 现场生成），
-- 详见 database/seed.sql 与 database/README.md。
-- =====================================================================

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

-- JiuJiu conversations
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

-- JiuJiu wall
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

-- Game platform
ALTER TABLE rooms ADD COLUMN game_type VARCHAR(32) NOT NULL DEFAULT 'WEREWOLF';
ALTER TABLE rooms ADD COLUMN allow_invites BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE rooms ADD COLUMN private_chat_enabled BOOLEAN NOT NULL DEFAULT TRUE;

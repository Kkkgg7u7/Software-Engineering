-- =====================================================================
-- H2 增量升级脚本（幂等，每次启动都会执行）
--
-- 背景：db/h2/schema.sql 用的是 CREATE TABLE IF NOT EXISTS，
--       对于"表已存在"的情况不会补新列。
--       本地用文件库（WEREWOLF_DB_URL=jdbc:h2:file:...）时，
--       升级后启动会因为缺列而报 "Column AUTH_VERSION not found"。
--
-- 因此把新增列/索引的语句集中到这里，用 IF NOT EXISTS 保证可反复执行。
-- 新增字段时请同时更新：schema.sql、本文件、database/schema.sql。
-- =====================================================================

-- 认证版本号：每次登录 +1，用于让旧 Token 失效（单会话强制）
ALTER TABLE users ADD COLUMN IF NOT EXISTS auth_version INT NOT NULL DEFAULT 0;

-- 补充索引（老库可能缺）
CREATE INDEX IF NOT EXISTS idx_users_nickname ON users (nickname);

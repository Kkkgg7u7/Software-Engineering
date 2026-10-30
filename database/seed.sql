-- =====================================================================
-- 基于 AI 增强的多人在线狼人杀系统 —— 可选种子数据（MySQL 8）
--
-- 执行前提：先执行 database/schema.sql。
-- 执行方式：mysql -h 127.0.0.1 -P 3306 -u root -p werewolf < database/seed.sql
--
-- 本脚本刻意保持“最小可空跑”：
--   1) 不插入任何用户 —— 演示账号由后端代码创建，见下面的说明；
--   2) 不插入任何房间 / 座位 / AI 数据 —— 这些都有强外键与运行时语义，
--      必须由应用（RoomService）写入，手工插入容易产生脏数据；
--   3) 只插入 2 条示例对局记录，供“战绩 / 复盘”页面在空库时有内容可展示。
-- =====================================================================

USE `werewolf`;

-- ---------------------------------------------------------------------
-- 关于演示账号（重要）
-- ---------------------------------------------------------------------
-- 为什么要用代码而不是 SQL：
--   users.password 存的是 BCrypt 哈希，必须由 Spring Security 的
--   PasswordEncoder 现场生成（每次加盐结果都不同）。把哈希写死在 SQL 里
--   既不可读，也容易与编码器版本 / 强度不一致而登录失败。
--
-- 因此演示账号由后端的 com.werewolf.config.DemoDataInitializer 在启动时创建：
--   demo    / demo1234   演示玩家
--   player1 / demo1234   1号玩家
--   player2 / demo1234   2号玩家
--   player3 / demo1234   3号玩家
--   player4 / demo1234   4号玩家
--
-- 注意：DemoDataInitializer 标注了 @Profile("h2")，只在本地零依赖演示
-- （SPRING_PROFILES_ACTIVE=h2）时生效。使用 mysql profile 时库里没有
-- 预置账号，请在登录页点击“注册”自行创建账号（注册接口对所有人开放），
-- 或在使用 Docker Compose 时按 deploy/README.md 的“首次使用”小节操作。
--
-- 如果你确实想在 MySQL 里预置一个账号，推荐做法是：先用应用注册一个账号，
-- 再执行 UPDATE users SET username='demo' WHERE username='你注册的名字';
-- （密码哈希保持不变，仍是你注册时设置的密码）。
-- ---------------------------------------------------------------------

-- ---------------------------------------------------------------------
-- 示例对局记录（2 条，可直接被 RecordsView / 复盘页读取）
-- 使用 INSERT ... WHERE NOT EXISTS 保证重复执行不会插入重复数据。
-- room_id 允许为 NULL：这里不带房间外键，避免依赖一个真实存在的 rooms.id。
-- ---------------------------------------------------------------------
INSERT INTO `game_records` (`room_id`, `room_code`, `winner`, `result`, `detail_json`, `rounds`, `created_at`)
SELECT NULL,
       'DEMO01',
       'VILLAGE',
       '好人阵营胜利',
       '{"players":[{"seatId":1,"nickname":"演示玩家","role":"SEER","alive":true,"ai":false},{"seatId":2,"nickname":"1号玩家","role":"WEREWOLF","alive":false,"ai":false},{"seatId":3,"nickname":"2号玩家","role":"WITCH","alive":true,"ai":false},{"seatId":4,"nickname":"3号玩家","role":"VILLAGER","alive":true,"ai":false}],"speeches":[]}',
       3,
       NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `game_records` WHERE `room_code` = 'DEMO01');

INSERT INTO `game_records` (`room_id`, `room_code`, `winner`, `result`, `detail_json`, `rounds`, `created_at`)
SELECT NULL,
       'DEMO02',
       'WEREWOLF',
       '狼人阵营胜利',
       '{"players":[{"seatId":1,"nickname":"演示玩家","role":"VILLAGER","alive":false,"ai":false},{"seatId":2,"nickname":"1号玩家","role":"WEREWOLF","alive":true,"ai":false},{"seatId":3,"nickname":"2号玩家","role":"SEER","alive":false,"ai":false},{"seatId":4,"nickname":"3号玩家","role":"WITCH","alive":false,"ai":false}],"speeches":[]}',
       2,
       NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `game_records` WHERE `room_code` = 'DEMO02');

-- =====================================================================
-- 执行结果自检（可选）：
--   SELECT COUNT(*) AS users FROM users;                -- 期望 0（mysql profile）
--   SELECT id, room_code, winner, rounds FROM game_records ORDER BY id;  -- 期望 2 条
-- =====================================================================

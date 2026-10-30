# 数据库增量迁移

本项目暂未引入自动生产迁移框架。新库执行 `database/schema.sql`；已有 MySQL 库先备份，再按版本顺序手动执行 mysql 目录的脚本并记录时间、版本和校验结果。脚本不可在已发布后改写，应新增下一个版本。

当前旧库前提是已有 users.auth_version（旧版升级语句见 database/schema.sql）；V002 增加 signature、updated_at。使用目标数据库连接运行脚本，不能对未确认的数据卷执行删除或重新初始化。

示例：登录 MySQL 客户端并选择实际数据库，执行 `SOURCE /absolute/path/database/migrations/mysql/V002__user_profile.sql;`。此脚本使用 MySQL 客户端 DELIMITER 命令与 information_schema 检查，支持重复执行。不要将它交给不认识 DELIMITER 的简单 JDBC 脚本执行器。

H2 开发模式在 application-h2.yml 中按顺序执行 schema.sql、旧 migration.sql、migrations/V002__user_profile.sql。V002 为加列与回填，不清除旧用户、密码或战绩。`UserProfileMigrationTest` 覆盖旧表保留数据并重复升级、新库重复执行。

验证范围：H2 已自动测试。MySQL SQL 已按 MySQL 8 语法编写，但当前机器无可用 MySQL/Docker，生产执行前仍需在备份副本上演练。现有 backend/data 文件库未在开发测试中打开。

V003：增加friendships、media_assets。H2应用启动时自动执行；已有MySQL库备份后执行mysql/V003__friendship_media.sql，新库schema.sql已包含。两个表CREATE IF NOT EXISTS可重复执行；未来列变化必须新增版本，不修改已交付V003。

V004：新增独立会话、成员及消息表。H2 自动重复安全执行；MySQL 手动在 V003 后执行一次。

V005：墙、图片关联、点赞及评论表。H2自动重复安全执行；MySQL在V004后手动执行一次。


V008__room_role_counts.sql 为本次身份配置迁移。现有 MySQL 库备份后执行一次，新增 rooms.role_counts JSON NULL；H2 在 application-h2.yml 自动重复安全执行，旧记录 NULL 沿用原默认组合。

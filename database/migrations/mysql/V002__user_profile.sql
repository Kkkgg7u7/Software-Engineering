-- MySQL 8. Run after schema.sql or against an existing users table. Back up first.
-- No DROP TABLE, DELETE, password rewrite or user-data replacement.
DROP PROCEDURE IF EXISTS migrate_jiujiu_v002;
DELIMITER $$
CREATE PROCEDURE migrate_jiujiu_v002()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='users' AND column_name='signature') THEN
    ALTER TABLE users ADD COLUMN signature VARCHAR(240) NOT NULL DEFAULT '';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='users' AND column_name='updated_at') THEN
    ALTER TABLE users ADD COLUMN updated_at DATETIME NULL;
  END IF;
  UPDATE users SET updated_at=COALESCE(created_at,CURRENT_TIMESTAMP) WHERE updated_at IS NULL;
END$$
DELIMITER ;
CALL migrate_jiujiu_v002();
DROP PROCEDURE migrate_jiujiu_v002;

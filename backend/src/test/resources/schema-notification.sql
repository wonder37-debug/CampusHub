-- H2 内存库建表脚本：sys_notification
-- 严格对照 init_schema.sql 中 sys_notification 的真实列，移除 MySQL 专属语法。

DROP TABLE IF EXISTS sys_notification;
CREATE TABLE sys_notification (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  type VARCHAR(32) NOT NULL,
  title VARCHAR(128) NOT NULL,
  content VARCHAR(500) NOT NULL,
  is_read BOOLEAN NOT NULL DEFAULT FALSE,
  related_id BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
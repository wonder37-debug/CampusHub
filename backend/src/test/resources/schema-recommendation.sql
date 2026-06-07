-- H2 内存库建表脚本：rec_user_action_log
-- 严格对照 init_schema.sql 中 rec_user_action_log 的真实列，移除 MySQL 专属语法。

DROP TABLE IF EXISTS rec_user_action_log;
CREATE TABLE rec_user_action_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  action_type VARCHAR(16) NOT NULL,
  demand_id BIGINT NOT NULL,
  category VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

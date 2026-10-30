-- 通知类型扩展：补齐 PR #26 新增的 5 种需求互动通知
-- （DEMAND_RESPONSE_SELECTED / DEMAND_RESPONSE_REJECTED / DEMAND_ANSWER_ACCEPTED / TEAM_UP_COMPLETED / DEMAND_CLOSED）。
--
-- init_schema.sql 使用 CREATE TABLE IF NOT EXISTS，已存在的库不会更新 CHECK 约束，因此旧库需要这条迁移。
-- 该文件不被应用自动执行（spring.sql.init.schema-locations 只加载 init_schema.sql，生产 profile 为 spring.sql.init.mode=never），
-- 由 DBA 手动执行。
--
-- 最低 MySQL 版本：8.0.16（CHECK 约束强制执行 + DROP CHECK 语法支持）。
-- MySQL 8.0.16+ 删除 CHECK 约束可用 DROP CHECK symbol 或 DROP CONSTRAINT symbol（两者均实测可删除 CHECK），
-- 但都不支持 IF EXISTS：DROP CHECK IF EXISTS 与 DROP CONSTRAINT IF EXISTS 均报 ERROR 1064 语法错误
-- （带 IF EXISTS 的写法是 MariaDB 的语法），因此按 information_schema 判定存在性后再条件执行。
-- 幂等：可安全重复执行（先检查 constraint_type='CHECK' 的约束是否存在，再决定 DROP 或跳过）。
-- 安全：仅匹配 constraint_type='CHECK' 的约束，避免误删 FOREIGN KEY / UNIQUE 等其他约束。
SET @constraint_exists = (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'sys_notification'
    AND constraint_name = 'chk_notify_type' AND constraint_type = 'CHECK');
SET @sql = IF(@constraint_exists > 0,
    'ALTER TABLE `sys_notification` DROP CHECK `chk_notify_type`',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE `sys_notification`
  ADD CONSTRAINT `chk_notify_type` CHECK (`type` IN (
    'ORDER_ACCEPTED','STATUS_CHANGED','REVIEW_RECEIVED','REVIEW_REQUEST',
    'DEMAND_REJECTED','DEMAND_APPROVED','PENDING_REVIEW',
    'ORDER_ARBITRATION_REQUESTED','ORDER_ARBITRATION_RESOLVED',
    'RESPONSE_REVIEW_RECEIVED','DEMAND_RESPONSE_RECEIVED','ORDER_DELETED',
    'DEMAND_RESPONSE_SELECTED','DEMAND_RESPONSE_REJECTED',
    'DEMAND_ANSWER_ACCEPTED','TEAM_UP_COMPLETED','DEMAND_CLOSED'));

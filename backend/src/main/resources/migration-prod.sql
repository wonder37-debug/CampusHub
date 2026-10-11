-- ============================================================
-- 生产环境升级脚本（手动执行，非自动初始化）
-- 适用：已有生产数据库需升级到支持 proof_image_urls + uploaded_asset 的版本
-- 执行方式：mysql -u <user> -p <database> < migration-prod.sql
-- 幂等：可重复执行（IF NOT EXISTS / IF NOT EXISTS 列检测）
-- ============================================================

-- 1. 为 ord_order 表新增 proof_image_urls 列（完成凭证图片URL列表）
-- MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 检测
SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ord_order' AND column_name = 'proof_image_urls');
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE `ord_order` ADD COLUMN `proof_image_urls` json DEFAULT NULL COMMENT ''完成凭证图片URL列表(1-3)'' AFTER `proof_image_count`',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 创建 uploaded_asset 表（上传资源所有权记录）
CREATE TABLE IF NOT EXISTS `uploaded_asset` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `filename` varchar(255) NOT NULL COMMENT '服务端生成的文件名（UUID+扩展名）',
  `url_path` varchar(512) NOT NULL COMMENT '完整相对路径 /api/v1/uploads/YYYY/MM/filename',
  `uploader_id` bigint NOT NULL COMMENT '上传者用户ID',
  `is_private` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否为私密图片（凭证等），0公开 1私密',
  `bound_order_id` bigint DEFAULT NULL COMMENT '绑定的订单ID（凭证图片绑定后填充）',
  `uploaded_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_filename` (`filename`),
  UNIQUE KEY `uk_asset_url_path` (`url_path`),
  KEY `idx_asset_uploader` (`uploader_id`),
  KEY `idx_asset_bound_order` (`bound_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传资源所有权记录';

-- 3. 为已有 uploaded_asset 表补充 is_private 和 bound_order_id 列（幂等）
SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'uploaded_asset' AND column_name = 'is_private');
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE `uploaded_asset` ADD COLUMN `is_private` tinyint(1) NOT NULL DEFAULT 0 COMMENT ''是否为私密图片（凭证等），0公开 1私密'' AFTER `uploader_id`',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'uploaded_asset' AND column_name = 'bound_order_id');
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE `uploaded_asset` ADD COLUMN `bound_order_id` bigint DEFAULT NULL COMMENT ''绑定的订单ID（凭证图片绑定后填充）'' AFTER `is_private`',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'uploaded_asset' AND index_name = 'idx_asset_bound_order');
SET @sql = IF(@idx_exists = 0,
    'CREATE INDEX idx_asset_bound_order ON uploaded_asset(bound_order_id)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. 更新 sys_notification 表的 chk_notify_type 约束，新增 DEMAND_RESPONSE_RECEIVED 通知类型
-- 最低 MySQL 版本：8.0.16（CHECK 约束强制执行 + DROP CHECK 语法支持）。
-- 幂等：可安全重复执行（先检查 constraint_type='CHECK' 的约束是否存在再决定 DROP+ADD 或仅 ADD）。
-- 安全：仅操作 constraint_type='CHECK' 的约束，避免误删 FOREIGN KEY / UNIQUE 等其他约束。
SET @constraint_exists = (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'sys_notification'
    AND constraint_name = 'chk_notify_type' AND constraint_type = 'CHECK');
SET @sql = IF(@constraint_exists > 0,
    'ALTER TABLE `sys_notification` DROP CHECK `chk_notify_type`, ADD CONSTRAINT `chk_notify_type` CHECK (`type` IN (''ORDER_ACCEPTED'',''STATUS_CHANGED'',''REVIEW_RECEIVED'',''REVIEW_REQUEST'',''DEMAND_REJECTED'',''DEMAND_APPROVED'',''PENDING_REVIEW'',''ORDER_ARBITRATION_REQUESTED'',''ORDER_ARBITRATION_RESOLVED'',''RESPONSE_REVIEW_RECEIVED'',''DEMAND_RESPONSE_RECEIVED''))',
    'ALTER TABLE `sys_notification` ADD CONSTRAINT `chk_notify_type` CHECK (`type` IN (''ORDER_ACCEPTED'',''STATUS_CHANGED'',''REVIEW_RECEIVED'',''REVIEW_REQUEST'',''DEMAND_REJECTED'',''DEMAND_APPROVED'',''PENDING_REVIEW'',''ORDER_ARBITRATION_REQUESTED'',''ORDER_ARBITRATION_RESOLVED'',''RESPONSE_REVIEW_RECEIVED'',''DEMAND_RESPONSE_RECEIVED''))');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4.1 验证迁移结果：确认 DEMAND_RESPONSE_RECEIVED 类型可被约束接受（不实际插入，仅检查约束定义）
-- 如果迁移成功，以下查询应返回包含 DEMAND_RESPONSE_RECEIVED 的约束定义
SELECT CONSTRAINT_NAME, CHECK_CLAUSE
FROM information_schema.check_constraints
WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_notify_type';

-- 5. 创建仲裁举证表 ord_arbitration_submission（双方举证流程）
CREATE TABLE IF NOT EXISTS `ord_arbitration_submission` (
  `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `order_id` bigint NOT NULL COMMENT '关联订单ID',
  `submitter_id` bigint NOT NULL COMMENT '提交者用户ID（发起方或另一方）',
  `role` varchar(16) NOT NULL COMMENT '提交者角色: INITIATOR / RESPONDER',
  `reason` varchar(2000) NOT NULL COMMENT '仲裁理由/补充说明',
  `image_urls` json DEFAULT NULL COMMENT '证据图片URL列表(私密存储)',
  `image_count` int NOT NULL DEFAULT 0 COMMENT '证据图片数量',
  `submitted_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
  KEY `idx_arbitration_order` (`order_id`),
  KEY `idx_arbitration_submitter` (`submitter_id`),
  CONSTRAINT `chk_arb_role` CHECK (`role` IN ('INITIATOR','RESPONDER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仲裁双方举证记录表';

-- 6. 幂等移除 ord_demand_response 表的唯一约束（HELP 模式允许多条 active Response）
SET @has_unique_resp = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ord_demand_response'
    AND index_name = 'uk_response_demand_author_active');
SET @sql = IF(@has_unique_resp > 0,
    'ALTER TABLE `ord_demand_response` DROP INDEX `uk_response_demand_author_active`', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 7. 幂等补充 ord_demand_response 表的 active_flag 生成列
SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ord_demand_response' AND column_name = 'active_flag');
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE `ord_demand_response` ADD COLUMN `active_flag` int GENERATED ALWAYS AS (CASE WHEN `status` IN (''PENDING'',''SELECTED'') THEN 1 ELSE NULL END) VIRTUAL', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 8. 幂等补充 ord_demand_response 的 idx_response_demand_author_active 普通索引
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ord_demand_response'
    AND index_name = 'idx_response_demand_author_active');
SET @sql = IF(@idx_exists = 0,
    'CREATE INDEX idx_response_demand_author_active ON ord_demand_response(demand_id, author_id, active_flag)', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 9. 幂等补充 ord_demand_response 的 chk_response_status CHECK 约束
SET @constraint_exists = (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'ord_demand_response'
    AND constraint_name = 'chk_response_status' AND constraint_type = 'CHECK');
SET @sql = IF(@constraint_exists = 0,
    'ALTER TABLE `ord_demand_response` ADD CONSTRAINT `chk_response_status` CHECK (`status` IN (''PENDING'',''SELECTED'',''REJECTED'',''WITHDRAWN''))', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

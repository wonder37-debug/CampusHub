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
-- 兼容 MySQL 8.0+：8.0.15 及以下 CHECK 约束不存储在 information_schema 中，走 ADD 分支；
-- 8.0.16+ 走 DROP CONSTRAINT + ADD 分支（DROP CONSTRAINT 语法仅 8.0.16+ 支持，但约束存在性检查确保旧版本不会执行此分支）。
-- 幂等：可安全重复执行（先检查 CHECK 类型约束是否存在再决定 DROP+ADD 或仅 ADD）。
-- 安全：仅操作 constraint_type='CHECK' 的约束，避免误删 FOREIGN KEY / UNIQUE 等其他约束。
SET @constraint_exists = (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'sys_notification'
    AND constraint_name = 'chk_notify_type' AND constraint_type = 'CHECK');
SET @sql = IF(@constraint_exists > 0,
    'ALTER TABLE `sys_notification` DROP CONSTRAINT `chk_notify_type`, ADD CONSTRAINT `chk_notify_type` CHECK (`type` IN (''ORDER_ACCEPTED'',''STATUS_CHANGED'',''REVIEW_RECEIVED'',''REVIEW_REQUEST'',''DEMAND_REJECTED'',''DEMAND_APPROVED'',''PENDING_REVIEW'',''ORDER_ARBITRATION_REQUESTED'',''ORDER_ARBITRATION_RESOLVED'',''RESPONSE_REVIEW_RECEIVED'',''DEMAND_RESPONSE_RECEIVED''))',
    'ALTER TABLE `sys_notification` ADD CONSTRAINT `chk_notify_type` CHECK (`type` IN (''ORDER_ACCEPTED'',''STATUS_CHANGED'',''REVIEW_RECEIVED'',''REVIEW_REQUEST'',''DEMAND_REJECTED'',''DEMAND_APPROVED'',''PENDING_REVIEW'',''ORDER_ARBITRATION_REQUESTED'',''ORDER_ARBITRATION_RESOLVED'',''RESPONSE_REVIEW_RECEIVED'',''DEMAND_RESPONSE_RECEIVED''))');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4.1 验证迁移结果：确认 DEMAND_RESPONSE_RECEIVED 类型可被约束接受（不实际插入，仅检查约束定义）
-- 如果迁移成功，以下查询应返回包含 DEMAND_RESPONSE_RECEIVED 的约束定义
SELECT CONSTRAINT_NAME, CHECK_CLAUSE
FROM information_schema.check_constraints
WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_notify_type';

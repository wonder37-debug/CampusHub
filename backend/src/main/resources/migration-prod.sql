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
  `uploaded_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_filename` (`filename`),
  UNIQUE KEY `uk_asset_url_path` (`url_path`),
  KEY `idx_asset_uploader` (`uploader_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传资源所有权记录';

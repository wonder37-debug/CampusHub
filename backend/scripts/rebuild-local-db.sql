-- ============================================================
-- CampusHub 本地开发数据库重建脚本
-- ============================================================
-- 用途：删除并重建本地开发数据库 campushub，仅限 localhost 使用。
--
-- 安全校验：
--   1. 检查当前 MySQL 连接的 hostname 是否为 localhost 或 127.0.0.1
--   2. 仅在本地连接时才执行 DROP DATABASE，否则中止
--
-- 使用方式（在项目根目录执行）：
--   mysql -u root -p < backend/scripts/rebuild-local-db.sql
--
-- 或在 application.properties 中配置好密码后直接用 Spring Boot 初始化：
--   1. 先手动 DROP DATABASE campushub;
--   2. 重启应用，Spring Boot 的 sql.init.mode=always 会用 init_schema.sql 重建
--
-- 注意：此脚本会删除所有本地数据，仅用于开发环境。
-- ============================================================

-- 安全校验：仅允许本地连接执行
-- 如果连接的不是 localhost，以下语句会报错中止
SET @host = (SELECT SUBSTRING_INDEX(USER(), '@', -1));
SET @is_local = (SELECT CASE
    WHEN @host = 'localhost' THEN 1
    WHEN @host = '127.0.0.1' THEN 1
    WHEN @host LIKE '127.0.0.%' THEN 1
    ELSE 0
END);

-- 如果不是本地连接，报错中止
SET @abort_msg = IF(@is_local = 0,
    CONCAT('ABORT: connection host is "', @host, '", rebuild-local-db.sql only runs on localhost'),
    'SELECT 1');
SET @sql = IF(@is_local = 0,
    CONCAT('SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''', @abort_msg, ''''),
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 安全校验通过，开始重建
DROP DATABASE IF EXISTS campushub;
CREATE DATABASE campushub DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE campushub;

-- 以下由 init_schema.sql 的完整 schema 接管
-- 应用启动时 spring.sql.init.mode=always 会自动执行 init_schema.sql 完成建表
-- 如果需要立即初始化，可在此追加 SOURCE init_schema.sql（MySQL CLI 方式）

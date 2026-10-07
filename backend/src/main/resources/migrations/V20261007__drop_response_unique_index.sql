-- ============================================================
-- Migration: 删除 ord_demand_response 唯一约束
-- ============================================================
-- 目的：支持 HELP 模式同一用户提交多条 active Response（PENDING/SELECTED）。
-- SELECT_ONE/SELECT_MANY 的"一人一个 active Response"规则由 Service 层在
-- Demand 行锁（findByIdForUpdate）保护下校验，不再依赖 DB 唯一约束。
--
-- 适用：已有生产数据库（spring.sql.init.mode=never，不会自动执行 init_schema.sql）
-- 新安装环境由 init_schema.sql 自动创建为普通索引，无需手动执行此脚本。
--
-- 执行方式：连接生产 MySQL 后执行
--   mysql -u<user> -p campushub < migrations/V20261007__drop_response_unique_index.sql
--
-- 幂等：不存在该索引时执行 SELECT 1，不报错。
-- ============================================================

SET @has_unique = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'ord_demand_response'
      AND index_name = 'uk_response_demand_author_active'
);

SET @sql = IF(@has_unique > 0,
    'ALTER TABLE `ord_demand_response` DROP INDEX `uk_response_demand_author_active`',
    'SELECT 1');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 验证：HELP 同一用户可以有多条 active Response
-- 执行后可运行以下查询确认约束已移除（应返回 0）：
-- SELECT COUNT(*) FROM information_schema.statistics
--   WHERE table_schema = DATABASE() AND table_name = 'ord_demand_response'
--     AND index_name = 'uk_response_demand_author_active' AND non_unique = 0;

-- 允许系统自动完成流水写入 operator_id = NULL
--
-- 背景：OrderAutoCompletionScheduler 每 5 分钟扫描"接单方已确认完成、发布者超过 48 小时未确认"
-- 的订单并自动完成，调用 autoCompleteOverdueOrder(orderId, null)，把系统触发记为
-- operator_id = NULL + note = 'SYSTEM_AUTO_COMPLETED'
-- （见 OrderApplicationServiceImpl#autoCompleteOverdueOrder 与调度器注释）。
--
-- 但 init_schema.sql 早期版本把 operator_id 声明为 NOT NULL。MyBatis-Plus 默认跳过 null 字段，
-- 生成的 INSERT 不含 operator_id 列，MySQL 报 "Field 'operator_id' doesn't have a default value"，
-- 导致整笔订单回滚：状态停在 IN_PROGRESS、冻结悬赏永不结算，且每轮扫描重复报错。
--
-- 新库由 init_schema.sql 直接建表（已同步为 NULL）；已存在的库需手动执行本迁移。
-- 该文件不被应用自动执行（spring.sql.init 只加载 init_schema.sql，生产 profile 为 never）。
--
-- 幂等：MODIFY COLUMN 可重复执行，不会报错，也不改动已有数据。
ALTER TABLE `ord_order_status_log`
  MODIFY COLUMN `operator_id` bigint NULL COMMENT '操作人ID，NULL 表示系统定时任务自动触发';

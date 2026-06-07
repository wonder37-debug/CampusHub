-- 订单测试切片专用建表脚本
-- 严格对照 init_schema.sql 中 ord_order / ord_order_status_log 的真实列设计；
-- 移除了 H2 不支持的字符集与 MySQL 保留风格，保留 NOT NULL / 默认值 / 唯一索引等关键约束。

DROP TABLE IF EXISTS ord_order_status_log;
DROP TABLE IF EXISTS ord_order;

CREATE TABLE ord_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  demand_id BIGINT NOT NULL,
  publisher_id BIGINT NOT NULL,
  accepter_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  accept_note VARCHAR(500),
  proof_submitted BOOLEAN NOT NULL DEFAULT FALSE,
  proof_image_count INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME,
  completed_at DATETIME,
  CONSTRAINT uk_order_demand UNIQUE (demand_id)
);

CREATE TABLE ord_order_status_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  from_status VARCHAR(32),
  to_status VARCHAR(32) NOT NULL,
  operator_id BIGINT NOT NULL,
  note VARCHAR(500),
  changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 评价测试切片专用建表脚本
-- 严格对照 init_schema.sql 中 ord_review 的真实列设计；
-- 移除了 H2 不支持的字符集与 MySQL 保留风格，保留 NOT NULL / 默认值 / 唯一索引等关键约束。

DROP TABLE IF EXISTS ord_review;

CREATE TABLE ord_review (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  author_id BIGINT NOT NULL,
  target_id BIGINT NOT NULL,
  rating SMALLINT NOT NULL,
  comment VARCHAR(1000),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_review_order_author UNIQUE (order_id, author_id)
);
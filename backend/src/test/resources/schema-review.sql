-- 评价测试切片专用建表脚本
-- 严格对照 init_schema.sql 中 ord_review 的真实列设计；
-- 移除了 H2 不支持的字符集与 MySQL 保留风格，保留 NOT NULL / 默认值 / 唯一索引等关键约束。
-- 业务模型 2.0：order_id 改为可空，新增 response_id 支持 Response 评价。

DROP TABLE IF EXISTS ord_review;

CREATE TABLE ord_review (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT,
  response_id BIGINT,
  demand_id BIGINT,
  author_id BIGINT NOT NULL,
  target_id BIGINT NOT NULL,
  rating SMALLINT NOT NULL,
  comment VARCHAR(1000),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_review_order_author UNIQUE (order_id, author_id),
  CONSTRAINT uk_review_response_author UNIQUE (response_id, author_id),
  CONSTRAINT chk_review_rating CHECK (rating BETWEEN 1 AND 5),
  CONSTRAINT chk_review_target CHECK (order_id IS NOT NULL OR response_id IS NOT NULL)
);

CREATE INDEX idx_review_target ON ord_review(target_id);
CREATE INDEX idx_review_author ON ord_review(author_id);
CREATE INDEX idx_review_created_at ON ord_review(created_at);

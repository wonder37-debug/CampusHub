-- 需求响应测试切片专用建表脚本
-- 严格对照 init_schema.sql 中 ord_demand_response 的真实列设计。

DROP TABLE IF EXISTS ord_demand_response;

CREATE TABLE ord_demand_response (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  demand_id BIGINT NOT NULL,
  author_id BIGINT NOT NULL,
  content VARCHAR(1000) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME,
  CONSTRAINT uk_response_demand_author_active UNIQUE (demand_id, author_id, status),
  CONSTRAINT chk_response_status CHECK (status IN ('PENDING','SELECTED','REJECTED','WITHDRAWN'))
);

CREATE INDEX idx_response_demand ON ord_demand_response(demand_id);
CREATE INDEX idx_response_author ON ord_demand_response(author_id);
CREATE INDEX idx_response_status ON ord_demand_response(status);

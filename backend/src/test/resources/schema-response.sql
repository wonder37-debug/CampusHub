-- 需求响应测试切片专用建表脚本
-- 严格对照 init_schema.sql 中 ord_demand_response 的真实列设计。
-- 业务模型 2.0：active_flag 生成列仅用于索引加速，不再作为唯一约束；
-- HELP 模式允许同一用户提交多条 active Response，SELECT_ONE/SELECT_MANY 由 Service 层在 Demand 行锁保护下校验"一人一个 active Response"。

DROP TABLE IF EXISTS ord_demand_response;

CREATE TABLE ord_demand_response (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  demand_id BIGINT NOT NULL,
  author_id BIGINT NOT NULL,
  content VARCHAR(1000) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  active_flag INT GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING','SELECTED') THEN 1 ELSE NULL END),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME,
  CONSTRAINT chk_response_status CHECK (status IN ('PENDING','SELECTED','REJECTED','WITHDRAWN'))
);

CREATE INDEX idx_response_demand_author_active ON ord_demand_response(demand_id, author_id, active_flag);
CREATE INDEX idx_response_demand ON ord_demand_response(demand_id);
CREATE INDEX idx_response_author ON ord_demand_response(author_id);
CREATE INDEX idx_response_status ON ord_demand_response(status);

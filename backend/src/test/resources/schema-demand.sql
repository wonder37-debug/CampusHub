DROP TABLE IF EXISTS ord_demand;

CREATE TABLE ord_demand (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  publisher_id BIGINT,
  publisher_display_name VARCHAR(64),
  title VARCHAR(200) NOT NULL,
  description TEXT,
  category VARCHAR(32) NOT NULL,
  campus_zone VARCHAR(32) NOT NULL,
  location VARCHAR(256),
  start_time DATETIME,
  end_time DATETIME,
  reward DECIMAL(10,2) NOT NULL DEFAULT '0.00',
  interaction_mode VARCHAR(32) NOT NULL DEFAULT 'DIRECT_ACCEPT',
  target_participant_count INT,
  tags VARCHAR(500),
  images TEXT DEFAULT NULL,
  contact_info VARCHAR(200) DEFAULT NULL,
  anonymous BOOLEAN NOT NULL DEFAULT FALSE,
  anonymous_code VARCHAR(64),
  status VARCHAR(32) NOT NULL DEFAULT 'REVIEWING',
  is_approved BOOLEAN NOT NULL DEFAULT FALSE,
  note VARCHAR(500),
  reviewed_by BIGINT,
  reviewed_at DATETIME,
  review_reason VARCHAR(500),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME,
  CONSTRAINT chk_demand_interaction CHECK (interaction_mode IN ('DIRECT_ACCEPT','SELECT_ONE','SELECT_MANY','HELP'))
);

CREATE INDEX idx_demand_publisher ON ord_demand(publisher_id);
CREATE INDEX idx_demand_status ON ord_demand(status);
CREATE INDEX idx_demand_category ON ord_demand(category);
CREATE INDEX idx_demand_campus_zone ON ord_demand(campus_zone);
CREATE INDEX idx_demand_created_at ON ord_demand(created_at);

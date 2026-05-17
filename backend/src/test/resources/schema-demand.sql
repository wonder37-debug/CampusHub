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
  tags VARCHAR(500),
  anonymous BOOLEAN NOT NULL DEFAULT FALSE,
  anonymous_code VARCHAR(64),
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  note VARCHAR(500),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME
);
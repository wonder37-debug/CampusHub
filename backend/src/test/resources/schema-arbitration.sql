DROP TABLE IF EXISTS ord_arbitration_submission;
CREATE TABLE ord_arbitration_submission (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    order_id      BIGINT       NOT NULL,
    submitter_id  BIGINT       NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    reason        VARCHAR(2000) NOT NULL,
    image_urls    CLOB         DEFAULT NULL,
    image_count   INT          NOT NULL DEFAULT 0,
    submitted_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT chk_arb_role CHECK (role IN ('INITIATOR','RESPONDER'))
);
CREATE INDEX idx_arbitration_order ON ord_arbitration_submission(order_id);
CREATE INDEX idx_arbitration_submitter ON ord_arbitration_submission(submitter_id);

DROP TABLE IF EXISTS uploaded_asset;
CREATE TABLE uploaded_asset (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    filename    VARCHAR(255) NOT NULL,
    uploader_id BIGINT       NOT NULL,
    uploaded_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_asset_filename UNIQUE (filename)
);

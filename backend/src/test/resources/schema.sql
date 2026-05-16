-- H2 兼容的 sys_user 建表脚本，供 @MybatisPlusTest 测试使用。
-- 字段集合与 docs/P3/建表SQL.txt 对齐；唯一索引落地以保障测试覆盖唯一约束冲突场景。
DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email           VARCHAR(128) NOT NULL,
    student_id      VARCHAR(32)  NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    nickname        VARCHAR(64)  DEFAULT '匿名校友',
    avatar_url      VARCHAR(255) DEFAULT NULL,
    role            VARCHAR(16)  NOT NULL DEFAULT 'USER',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    credit_score    INT          NOT NULL DEFAULT 100,
    balance         DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    frozen_balance  DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    email_verified_at TIMESTAMP  DEFAULT NULL,
    CONSTRAINT uk_user_email      UNIQUE (email),
    CONSTRAINT uk_user_student_id UNIQUE (student_id),
    CONSTRAINT chk_user_role   CHECK (role IN ('USER','ADMIN')),
    CONSTRAINT chk_user_status CHECK (status IN ('ACTIVE','BANNED'))
);

-- ==========================================================

-- CampusHub 校园互助平台建表脚本

-- ==========================================================



-- 1. 创建并使用数据库 (支持 Emoji 表情)

CREATE DATABASE IF NOT EXISTS campushub DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE campushub;



-- ==========================================================

-- 1. 用户基础表 (sys_user)

-- ==========================================================

CREATE TABLE IF NOT EXISTS`sys_user` (

  `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,

  `email` varchar(128) NOT NULL UNIQUE COMMENT '学校邮箱(注册/登录标识)',

  `student_id` varchar(32) NOT NULL UNIQUE COMMENT '学号(业务标识)',

  `password_hash` varchar(255) NOT NULL COMMENT '哈希密码(Bcrypt)',

  `nickname` varchar(64) DEFAULT '匿名校友' COMMENT '昵称',

  `avatar_url` varchar(255) DEFAULT NULL COMMENT '头像链接',

  `role` varchar(16) NOT NULL DEFAULT 'USER' COMMENT 'USER/ADMIN',

  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/BANNED',

  `credit_score` int NOT NULL DEFAULT 100 COMMENT '信用分',

  `balance` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '可用余额',

  `frozen_balance` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '冻结金额',

  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',

  `email_verified_at` datetime DEFAULT NULL COMMENT '邮箱验证完成时间',

  `updated_at` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

  CONSTRAINT `chk_user_role` CHECK (`role` IN ('USER','ADMIN')),

  CONSTRAINT `chk_user_status` CHECK (`status` IN ('ACTIVE','BANNED'))

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户主表';



-- 插入默认的超级管理员账号 (防重复插入机制)

-- 账号: admin@edu.cn / ADMIN001 | 密码: Admin123!

INSERT INTO `sys_user` (`email`, `student_id`, `password_hash`, `nickname`, `role`) 

VALUES ('admin@edu.cn', 'ADMIN001', '$2a$10$ZZ9LIwTu25X6iXkfc1SASe4YEghRHDiD1jTMuvWiqCuDEvAunM68O', '超级管理员', 'ADMIN')

ON DUPLICATE KEY UPDATE id=id;

INSERT INTO `sys_user` (`email`, `student_id`, `password_hash`, `nickname`, `role`) 

VALUES ('test1@edu.cn', 'TEST001', '$2a$10$ZZ9LIwTu25X6iXkfc1SASe4YEghRHDiD1jTMuvWiqCuDEvAunM68O', '测试用户1', 'USER')

ON DUPLICATE KEY UPDATE id=id;

INSERT INTO `sys_user` (`email`, `student_id`, `password_hash`, `nickname`, `role`) 

VALUES ('test2@edu.cn', 'TEST002', '$2a$10$ZZ9LIwTu25X6iXkfc1SASe4YEghRHDiD1jTMuvWiqCuDEvAunM68O', '测试用户2', 'USER')

ON DUPLICATE KEY UPDATE id=id;



-- ==========================================================

-- 2. 需求主表 (ord_demand)

-- ==========================================================

CREATE TABLE IF NOT EXISTS`ord_demand` (

  `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,

  `publisher_id` bigint COMMENT '发单人(匿名时允许逻辑为空)',

  `publisher_display_name` varchar(64) COMMENT '发布者展示名',

  `title` varchar(200) NOT NULL COMMENT '需求标题',

  `description` text COMMENT '需求描述',

  `category` varchar(32) NOT NULL COMMENT '分类(英文字典)',

  `campus_zone` varchar(32) NOT NULL COMMENT '校区(如XIANLIN)',

  `location` varchar(256) DEFAULT NULL COMMENT '详细地点',

  `start_time` datetime DEFAULT NULL COMMENT '期望开始时间',

  `end_time` datetime DEFAULT NULL COMMENT '期望结束时间',

  `reward` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '悬赏金额',

  `interaction_mode` varchar(32) NOT NULL DEFAULT 'DIRECT_ACCEPT' COMMENT '互动模式: DIRECT_ACCEPT/SELECT_ONE/SELECT_MANY/HELP',

  `target_participant_count` int DEFAULT NULL COMMENT '目标参与人数(TEAM_UP必填)',

  `tags` varchar(500) DEFAULT NULL COMMENT '标签(逗号分隔)',
  `images` json DEFAULT NULL COMMENT '图片URL列表(JSON数组)',
  `contact_info` varchar(200) DEFAULT NULL COMMENT '联系方式(电话/微信/QQ/邮箱)',

  `anonymous` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否匿名(0否 1是)',

  `anonymous_code` varchar(64) DEFAULT NULL COMMENT '匿名识别码',

  `status` varchar(32) NOT NULL DEFAULT 'REVIEWING' COMMENT '状态机: REVIEWING→PENDING→IN_PROGRESS→COMPLETED/CANCELLED',

  `is_approved` tinyint(1) NOT NULL DEFAULT 0 COMMENT '0待审核 1已通过',

  `note` varchar(500) DEFAULT NULL COMMENT '需求补充说明',

  `reviewed_by` bigint DEFAULT NULL COMMENT '审核人ID',

  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',

  `review_reason` varchar(500) DEFAULT NULL COMMENT '审核理由/拒绝原因',

  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',

  `updated_at` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

  CONSTRAINT `chk_demand_category` CHECK (`category` IN ('EXPRESS','ERRAND','STUDY_TUTORING','SECOND_HAND','TEAM_UP','OTHER','HELP')),

  CONSTRAINT `chk_demand_status` CHECK (`status` IN ('PENDING','REVIEWING','IN_PROGRESS','COMPLETED','CANCELLED','EXPIRED')),

  CONSTRAINT `chk_demand_interaction` CHECK (`interaction_mode` IN ('DIRECT_ACCEPT','SELECT_ONE','SELECT_MANY','HELP'))

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需求主表';

-- 幂等补充 interaction_mode / target_participant_count 列（老版本 ord_demand 表已存在但缺少这两列时添加；Spring sql.init always mode 重复执行安全）
SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ord_demand' AND column_name = 'interaction_mode');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ord_demand` ADD COLUMN `interaction_mode` varchar(32) NOT NULL DEFAULT ''DIRECT_ACCEPT'' COMMENT ''互动模式: DIRECT_ACCEPT/SELECT_ONE/SELECT_MANY/HELP''', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ord_demand' AND column_name = 'target_participant_count');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ord_demand` ADD COLUMN `target_participant_count` int DEFAULT NULL COMMENT ''目标参与人数(TEAM_UP必填)''', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;



-- 幂等创建索引（Spring sql.init always mode 重复执行安全）
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ord_demand' AND index_name = 'idx_demand_publisher');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_demand_publisher ON ord_demand(publisher_id)', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ord_demand' AND index_name = 'idx_demand_status');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_demand_status ON ord_demand(status)', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ord_demand' AND index_name = 'idx_demand_category');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_demand_category ON ord_demand(category)', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ord_demand' AND index_name = 'idx_demand_campus_zone');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_demand_campus_zone ON ord_demand(campus_zone)', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ord_demand' AND index_name = 'idx_demand_created_at');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_demand_created_at ON ord_demand(created_at)', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;



-- ==========================================================

-- 3. 订单主表 (ord_order)

-- ==========================================================

CREATE TABLE IF NOT EXISTS`ord_order` (

  `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,

  `demand_id` bigint NOT NULL COMMENT '关联的需求ID',

  `publisher_id` bigint NOT NULL COMMENT '发单人ID',

  `accepter_id` bigint NOT NULL COMMENT '接单人ID',

  `status` varchar(32) NOT NULL COMMENT '订单状态',

  `accept_note` varchar(500) DEFAULT NULL COMMENT '接单时的留言',

  `proof_submitted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否已提交凭证',

  `proof_image_count` int NOT NULL DEFAULT 0 COMMENT '凭证图片数量',

  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '接单/订单生成时间',

  `updated_at` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

  `completed_at` datetime DEFAULT NULL COMMENT '最终完成时间',

  UNIQUE KEY `uk_order_demand` (`demand_id`) COMMENT '防重接单的物理底线',

  KEY `idx_order_publisher` (`publisher_id`),

  KEY `idx_order_accepter` (`accepter_id`),

  KEY `idx_order_created_at` (`created_at`),

  CONSTRAINT `chk_order_status` CHECK (`status` IN ('ACCEPTED','IN_PROGRESS','IN_ARBITRATION','COMPLETED','CANCELLED'))

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单主表';

-- 幂等补充 idx_order_created_at（老版本 ord_order 表已存在但缺少该索引时添加；Spring sql.init always mode 重复执行安全）
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ord_order' AND index_name = 'idx_order_created_at');
SET @sql = IF(@idx_exists = 0, 'ALTER TABLE `ord_order` ADD INDEX `idx_order_created_at` (`created_at`)', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;



-- ==========================================================

-- 4. 订单状态变更日志表 (ord_order_status_log)

-- ==========================================================

CREATE TABLE IF NOT EXISTS`ord_order_status_log` (

  `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,

  `order_id` bigint NOT NULL COMMENT '关联订单ID',

  `from_status` varchar(32) COMMENT '原状态',

  `to_status` varchar(32) NOT NULL COMMENT '新状态',

  `operator_id` bigint NOT NULL COMMENT '操作人ID',

  `note` varchar(500) COMMENT '状态变更备注',

  `changed_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '变更时间',

  KEY `idx_order_status_log_order` (`order_id`)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单状态历史轨迹表';



-- ==========================================================

-- 5. 需求响应表 (ord_demand_response)

-- ==========================================================

CREATE TABLE IF NOT EXISTS`ord_demand_response` (

  `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,

  `demand_id` bigint NOT NULL COMMENT '关联需求ID',

  `author_id` bigint NOT NULL COMMENT '响应人ID',

  `content` varchar(1000) NOT NULL COMMENT '留言/报名/回答内容',

  `status` varchar(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/SELECTED/REJECTED/WITHDRAWN',

  `active_flag` int GENERATED ALWAYS AS (CASE WHEN `status` IN ('PENDING','SELECTED') THEN 1 ELSE NULL END) VIRTUAL COMMENT '活跃标志: PENDING/SELECTED=1, 其余=NULL, 仅用于唯一约束',

  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

  `updated_at` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

  UNIQUE KEY `uk_response_demand_author_active` (`demand_id`, `author_id`, `active_flag`),

  KEY `idx_response_demand` (`demand_id`),

  KEY `idx_response_author` (`author_id`),

  KEY `idx_response_status` (`status`),

  CONSTRAINT `chk_response_status` CHECK (`status` IN ('PENDING','SELECTED','REJECTED','WITHDRAWN'))

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需求响应表(留言/报名/回答)';

-- 幂等补充 active_flag 生成列（老版本 ord_demand_response 已存在但缺少该列时添加）
SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ord_demand_response' AND column_name = 'active_flag');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ord_demand_response` ADD COLUMN `active_flag` int GENERATED ALWAYS AS (CASE WHEN `status` IN (''PENDING'',''SELECTED'') THEN 1 ELSE NULL END) VIRTUAL COMMENT ''活跃标志: PENDING/SELECTED=1, 其余=NULL, 仅用于唯一约束''', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 幂等替换唯一索引：从 (demand_id, author_id, status) 改为 (demand_id, author_id, active_flag)
-- 旧索引以 status 为列、新索引以 active_flag 为列，通过 information_schema 区分
SET @old_idx = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ord_demand_response' AND index_name = 'uk_response_demand_author_active' AND column_name = 'status');
SET @new_idx = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ord_demand_response' AND index_name = 'uk_response_demand_author_active' AND column_name = 'active_flag');
SET @sql = IF(@old_idx > 0 AND @new_idx = 0, 'ALTER TABLE `ord_demand_response` DROP INDEX `uk_response_demand_author_active`, ADD UNIQUE KEY `uk_response_demand_author_active` (`demand_id`, `author_id`, `active_flag`)', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;



-- ==========================================================

-- 6. 评价表 (ord_review)

-- ==========================================================

CREATE TABLE IF NOT EXISTS`ord_review` (

  `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,

  `order_id` bigint DEFAULT NULL COMMENT '关联订单ID(Response评价时为空)',

  `response_id` bigint DEFAULT NULL COMMENT '关联响应ID(Order评价时为空)',

  `demand_id` bigint DEFAULT NULL COMMENT '关联需求ID(Response评价跳转用,冗余辅助字段)',

  `author_id` bigint NOT NULL COMMENT '评价人ID',

  `target_id` bigint NOT NULL COMMENT '被评价人ID',

  `rating` tinyint NOT NULL COMMENT '1-5星打分',

  `comment` varchar(1000) DEFAULT NULL COMMENT '评价内容',

  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',

  UNIQUE KEY `uk_review_order_author` (`order_id`, `author_id`) COMMENT '同订单同作者单向只能评价一次',

  UNIQUE KEY `uk_review_response_author` (`response_id`, `author_id`) COMMENT '同响应同作者单向只能评价一次',

  KEY `idx_review_target` (`target_id`) COMMENT '用于加速查询某人的所有评价算分',

  KEY `idx_review_author` (`author_id`),

  KEY `idx_review_created_at` (`created_at`),

  CONSTRAINT `chk_review_rating` CHECK (`rating` BETWEEN 1 AND 5),

  CONSTRAINT `chk_review_target` CHECK (`order_id` IS NOT NULL OR `response_id` IS NOT NULL)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单/响应评价表';



-- 幂等补充 response_id 列与 order_id nullable（老版本 ord_review 表已存在但缺少 response_id 列时添加）
SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ord_review' AND column_name = 'response_id');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ord_review` ADD COLUMN `response_id` bigint DEFAULT NULL COMMENT ''关联响应ID(Order评价时为空)''', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 幂等补充 demand_id 列（Response 评价跳转用，冗余辅助字段）
SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ord_review' AND column_name = 'demand_id');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ord_review` ADD COLUMN `demand_id` bigint DEFAULT NULL COMMENT ''关联需求ID(Response评价跳转用,冗余辅助字段)''', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 幂等放宽 order_id 为可空（老版本为 NOT NULL，Response 评价需要 order_id 可空）
SET @col_is_nullable = (SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ord_review' AND column_name = 'order_id');
SET @sql = IF(@col_is_nullable = 'NO', 'ALTER TABLE `ord_review` MODIFY COLUMN `order_id` bigint DEFAULT NULL COMMENT ''关联订单ID(Response评价时为空)''', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 幂等创建 uk_review_response_author 唯一索引
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ord_review' AND index_name = 'uk_review_response_author');
SET @sql = IF(@idx_exists = 0, 'CREATE UNIQUE INDEX `uk_review_response_author` ON `ord_review`(`response_id`, `author_id`)', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;



-- 幂等创建索引（Spring sql.init always mode 重复执行安全）
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ord_review' AND index_name = 'idx_review_author');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_review_author ON ord_review(author_id)', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 幂等补充 idx_review_created_at（Spring sql.init always mode 重复执行安全）
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ord_review' AND index_name = 'idx_review_created_at');
SET @sql = IF(@idx_exists = 0, 'ALTER TABLE `ord_review` ADD INDEX `idx_review_created_at` (`created_at`)', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;



-- ==========================================================

-- 6. 通知表 (sys_notification)

-- ==========================================================

CREATE TABLE IF NOT EXISTS`sys_notification` (

  `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,

  `user_id` bigint NOT NULL COMMENT '接收人ID',

  `type` varchar(32) NOT NULL COMMENT '通知类型',

  `title` varchar(128) NOT NULL COMMENT '通知标题',

  `content` varchar(500) NOT NULL COMMENT '通知内容',

  `is_read` tinyint(1) NOT NULL DEFAULT 0 COMMENT '0未读 1已读',

  `related_id` bigint DEFAULT NULL COMMENT '相关业务实体ID',

  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '通知生成时间',

  KEY `idx_notify_user_read` (`user_id`, `is_read`) COMMENT '加速未读消息列表查询',

  CONSTRAINT `chk_notify_type` CHECK (`type` IN ('ORDER_ACCEPTED','STATUS_CHANGED','REVIEW_RECEIVED','REVIEW_REQUEST','DEMAND_REJECTED','DEMAND_APPROVED','PENDING_REVIEW','ORDER_ARBITRATION_REQUESTED','ORDER_ARBITRATION_RESOLVED','RESPONSE_REVIEW_RECEIVED'))

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内信通知表';



-- ==========================================================

-- 7. 推荐/用户行为日志表 (rec_user_action_log)

-- ==========================================================

CREATE  TABLE IF NOT EXISTS `rec_user_action_log` (

  `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,

  `user_id` bigint NOT NULL COMMENT '行为人ID',

  `action_type` varchar(16) NOT NULL COMMENT '动作类型: VIEW / ACCEPT',

  `demand_id` bigint NOT NULL COMMENT '被操作的需求ID',

  `category` varchar(32) NOT NULL COMMENT '需求的分类',

  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '行为发生时间',

  KEY `idx_action_user_cat` (`user_id`, `category`) COMMENT '用于按分类统计用户偏好',

  KEY `idx_user_action_demand_time` (`user_id`, `action_type`, `demand_id`, `created_at`) COMMENT '用于 VIEW 去重 existsRecentView'

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐系统用户行为日志表';

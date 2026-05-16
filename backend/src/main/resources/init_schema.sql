-- ==========================================================

-- CampusHub 校园互助平台建表脚本

-- ==========================================================



-- 1. 创建并使用数据库 (支持 Emoji 表情)

CREATE DATABASE IF NOT EXISTS campushub DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE campushub;



-- ==========================================================

-- 1. 用户基础表 (sys_user)

-- ==========================================================

CREATE TABLE `sys_user` (

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

  CONSTRAINT `chk_user_role` CHECK (`role` IN ('USER','ADMIN')),

  CONSTRAINT `chk_user_status` CHECK (`status` IN ('ACTIVE','BANNED'))

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户主表';



-- 插入默认的超级管理员账号 (防重复插入机制)

-- 账号: admin@edu.cn / ADMIN001 | 密码: Admin123!

INSERT INTO `sys_user` (`email`, `student_id`, `password_hash`, `nickname`, `role`) 

VALUES ('admin@edu.cn', 'ADMIN001', '$2a$10$X8H43oFItP2q7vWwJz29/e5j02M7q.K/Q0H8.3/m2K9G6k7Q5Q9rW', '超级管理员', 'ADMIN')

ON DUPLICATE KEY UPDATE id=id;



-- ==========================================================

-- 2. 需求主表 (ord_demand)

-- ==========================================================

CREATE TABLE `ord_demand` (

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

  `tags` varchar(500) DEFAULT NULL COMMENT '标签(逗号分隔)',

  `anonymous` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否匿名(0否 1是)',

  `anonymous_code` varchar(64) DEFAULT NULL COMMENT '匿名识别码',

  `status` varchar(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态机',

  `note` varchar(500) DEFAULT NULL COMMENT '需求补充说明',

  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',

  `updated_at` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

  CONSTRAINT `chk_demand_category` CHECK (`category` IN ('EXPRESS','STUDY_TUTORING','SECOND_HAND','TEAM_UP','OTHER')),

  CONSTRAINT `chk_demand_status` CHECK (`status` IN ('PENDING','REVIEWING','IN_PROGRESS','COMPLETED','CANCELLED'))

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需求主表';



-- ==========================================================

-- 3. 订单主表 (ord_order)

-- ==========================================================

CREATE TABLE `ord_order` (

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

  CONSTRAINT `chk_order_status` CHECK (`status` IN ('ACCEPTED','IN_PROGRESS','COMPLETED','CANCELLED'))

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单主表';



-- ==========================================================

-- 4. 订单状态变更日志表 (ord_order_status_log)

-- ==========================================================

CREATE TABLE `ord_order_status_log` (

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

-- 5. 评价表 (ord_review)

-- ==========================================================

CREATE TABLE `ord_review` (

  `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,

  `order_id` bigint NOT NULL COMMENT '关联订单ID',

  `author_id` bigint NOT NULL COMMENT '评价人ID',

  `target_id` bigint NOT NULL COMMENT '被评价人ID',

  `rating` tinyint NOT NULL COMMENT '1-5星打分',

  `comment` varchar(1000) DEFAULT NULL COMMENT '评价内容',

  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',

  UNIQUE KEY `uk_review_order_author` (`order_id`, `author_id`) COMMENT '单向只能评价一次',

  KEY `idx_review_target` (`target_id`) COMMENT '用于加速查询某人的所有评价算分',

  CONSTRAINT `chk_review_rating` CHECK (`rating` BETWEEN 1 AND 5)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单评价表';



-- ==========================================================

-- 6. 资产流水表 (ast_ledger)

-- ==========================================================

CREATE TABLE `ast_ledger` (

  `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,

  `user_id` bigint NOT NULL COMMENT '资产归属人ID',

  `amount` decimal(10,2) NOT NULL COMMENT '变动金额',

  `direction` varchar(10) NOT NULL COMMENT '方向(IN/OUT/FREEZE/UNFREEZE)',

  `biz_order_id` bigint DEFAULT NULL COMMENT '关联业务订单ID',

  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '流水发生时间',

  CONSTRAINT `chk_ledger_direction` CHECK (`direction` IN ('IN','OUT','FREEZE','UNFREEZE'))

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变资产流水表';



-- ==========================================================

-- 7. 通知表 (sys_notification)

-- ==========================================================

CREATE TABLE `sys_notification` (

  `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,

  `user_id` bigint NOT NULL COMMENT '接收人ID',

  `type` varchar(32) NOT NULL COMMENT '通知类型',

  `title` varchar(128) NOT NULL COMMENT '通知标题',

  `content` varchar(500) NOT NULL COMMENT '通知内容',

  `is_read` tinyint(1) NOT NULL DEFAULT 0 COMMENT '0未读 1已读',

  `related_id` bigint DEFAULT NULL COMMENT '相关业务实体ID',

  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '通知生成时间',

  KEY `idx_notify_user_read` (`user_id`, `is_read`) COMMENT '加速未读消息列表查询',

  CONSTRAINT `chk_notify_type` CHECK (`type` IN ('ORDER_ACCEPTED','STATUS_CHANGED','REVIEW_RECEIVED'))

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内信通知表';



-- ==========================================================

-- 8. 推荐/用户行为日志表 (rec_user_action_log)

-- ==========================================================

CREATE TABLE `rec_user_action_log` (

  `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,

  `user_id` bigint NOT NULL COMMENT '行为人ID',

  `action_type` varchar(16) NOT NULL COMMENT '动作类型: VIEW / ACCEPT',

  `demand_id` bigint NOT NULL COMMENT '被操作的需求ID',

  `category` varchar(32) NOT NULL COMMENT '需求的分类',

  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '行为发生时间',

  KEY `idx_action_user_cat` (`user_id`, `category`) COMMENT '用于按分类统计用户偏好'

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐系统用户行为日志表';
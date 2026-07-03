# CampusHub — 南京大学校园互助平台

> 一个面向校园场景的需求发布、接单执行、评价反馈全链路互助平台。学生可以发布跑腿代办、学习辅导、二手交易等各类需求，其他学生接单完成后获得悬赏，平台提供信用体系、站内通知、个性化推荐和管理后台。

## 目录

- [项目简介](#项目简介)
- [功能特性](#功能特性)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [依赖环境](#依赖环境)
- [安装步骤](#安装步骤)
- [配置方式](#配置方式)
- [使用方法](#使用方法)
- [测试账号](#测试账号)
- [API 概览](#api-概览)
- [数据库设计](#数据库设计)
- [CI/CD](#cicd)
- [开源协议](#开源协议)

---

## 项目简介

CampusHub 是南京大学软件工程课程大作业项目，采用前后端分离的 Monorepo 架构。平台围绕"校园需求匹配"核心场景，构建了从需求发布、审核、接单、执行、完成到评价的完整业务闭环，并辅以信用积分、余额冻结/转移、站内通知、个性化推荐和管理后台等功能模块。

**核心业务流程：**

```
发布需求 → 管理员审核 → 公开浏览 → 接单 → 开始执行 → 双方确认完成 → 互相评价
                                                                ↓
                                                          发起仲裁（如有争议）
```

---

## 功能特性

### 用户与认证

- 校园邮箱注册（域名白名单校验，默认支持 `nju.edu.cn`、`smail.nju.edu.cn` 等）
- 邮箱验证码注册与密码重置（未配置 SMTP 时自动退化为控制台日志输出）
- 基于 Token 的轻量级认证（Base64 编码，有效期 1 小时）
- BCrypt 密码加密

### 需求管理

- 六大分类：跑腿代取、委托代办、学习辅导、二手交易、活动组队、其他
- 三大校区：鼓楼、仙林、苏州
- 多维筛选：关键词、分类、校区、地点、时间范围
- 四种排序：时间、距离、报酬、推荐
- 匿名发布（生成匿名识别码，保护发布者身份）
- 敏感词过滤（屏蔽代课、代考等违规内容）
- 草稿自动保存（前端 localStorage）

### 订单系统

- 完整状态流转：已接单 → 进行中 → 已完成 / 仲裁中 / 已取消
- 双方确认完成机制（接单方提交凭证图片，发布者确认）
- 48 小时超时自动完成
- 仲裁申请与管理员裁决
- 悬赏金冻结与转移（发布时冻结，完成时转移，取消时解冻）

### 评价与信用

- 1-5 星评分 + 文字评论
- 信用分动态计算（历史权重 0.9 + 新评价权重 0.1）
- 信用等级：金牌助教（95+）、银牌助教（85+）、成长中

### 通知系统

- 9 种通知类型：接单、状态变更、收到评价、待审核、审核通过/驳回、待评价提醒、仲裁申请/仲裁处理
- 标记已读 / 全部已读
- 点击通知智能跳转关联页面

### 个性化推荐

- 冷启动算法（新用户）：40% 报酬 + 30% 紧急度 + 30% 新鲜度
- 热启动算法（有历史接单）：50% 分类偏好 + 20% 报酬 + 15% 紧急度 + 15% 新鲜度
- 推荐理由标签（同分类、高报酬、即将截止、最新需求）

### 管理后台

- 用户管理：搜索、筛选、封禁/解封、角色修改
- 需求审核：通过/驳回（需填写理由）
- 仲裁处理：裁决完成或取消
- 仪表盘统计：DAU、总用户数、需求数、待审核数、订单数、分类分布

### 文件上传

- 支持格式：jpg、jpeg、png、webp
- 单文件上限 5MB，单次最多 6 张
- 按年月目录存储，浏览器 24 小时缓存

---

## 技术栈

### 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 运行时 |
| Spring Boot | 3.5.0 | 应用框架 |
| MyBatis-Plus | 3.5.7 | ORM 框架 |
| MySQL Connector/J | (继承) | 生产数据库驱动 |
| H2 Database | (继承) | 测试数据库 |
| Spring Security Crypto | (继承) | BCrypt 密码加密 |
| Spring Boot Mail | (继承) | SMTP 邮件发送 |
| Lombok | (继承) | 简化 Java POJO |

### 前端

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue | ^3.5.32 | 核心框架 |
| TypeScript | ~6.0.2 | 类型系统 |
| Vite | ^8.0.4 | 构建工具 |
| Pinia | ^3.0.4 | 状态管理 |
| Vue Router | ^4.6.4 | 路由管理 |
| vue-tsc | ^3.2.6 | Vue TS 类型检查 |

> 前端未使用任何第三方 UI 组件库，全部为纯手写自定义 CSS（暖色调 + 毛玻璃风格设计系统）。

---

## 项目结构

```
sec-ii-2026/
├── .github/workflows/          # GitHub Actions CI 配置
│   ├── backend-ci.yml
│   └── frontend-ci.yml
├── .gitlab-ci.yml              # GitLab 父流水线
├── .gitlab/                    # GitLab 子流水线
│   ├── backend.yml
│   └── frontend.yml
├── backend/                    # Spring Boot 后端
│   ├── mvnw / mvnw.cmd         # Maven Wrapper
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/campushub/backend/
│       │   │   ├── BackendApplication.java   # 入口类
│       │   │   ├── api/                      # 控制器层 + 视图对象
│       │   │   ├── auth/                     # 认证模块
│       │   │   ├── demand/                   # 需求模块
│       │   │   ├── order/                    # 订单模块
│       │   │   ├── review/                   # 评价模块
│       │   │   ├── notification/             # 通知模块
│       │   │   ├── recommendation/           # 推荐模块
│       │   │   ├── admin/                    # 管理后台模块
│       │   │   └── common/                   # 公共模块（异常/安全/响应）
│       │   └── resources/
│       │       ├── application.properties    # 主配置
│       │       ├── application-local.properties  # 本地配置（gitignore）
│       │       └── init_schema.sql           # 建表脚本
│       └── test/
│           └── resources/                    # H2 测试 schema
├── frontend/                   # Vue 3 前端
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│       ├── main.ts             # 入口文件
│       ├── App.vue             # 根组件（布局外壳）
│       ├── router/             # 路由配置
│       ├── stores/             # Pinia 状态管理
│       ├── views/              # 页面组件（12 个视图）
│       ├── components/         # 可复用组件
│       ├── composables/        # 组合式函数
│       ├── types/              # TypeScript 类型定义
│       └── utils/              # 工具函数
├── docs/                       # 各阶段交付文档
├── start.sh                    # 一键启动脚本
└── README.md
```

### 后端架构

后端采用 DDD（领域驱动设计）分层架构，每个业务模块内部结构一致：

```
module/
├── domain/        # 领域模型与枚举（纯 POJO）
├── dto/           # 数据传输对象
├── repository/    # 仓储接口 + MyBatis 实现 + 内存实现
│   ├── entity/    # 持久化实体
│   └── mapper/    # MyBatis Mapper 接口
└── service/       # 应用服务接口与实现
```

> 仓储层采用双实现架构：生产环境使用 `MyBatis*Repository`（MySQL），测试环境使用 `InMemory*Repository`（内存）。

---

## 依赖环境

| 工具 | 版本要求 | 说明 |
|------|---------|------|
| JDK | 21 | 后端编译运行 |
| MySQL | 8.0+ | 生产数据库（默认 `localhost:3306`） |
| Node.js | 18+ | 前端构建运行 |
| npm | 随 Node.js | 前端包管理 |
| Maven | 无需安装 | 项目内置 `mvnw` Wrapper |

---

## 安装步骤

### 1. 克隆仓库

```bash
git clone https://github.com/wonder37-debug/CampusHub.git
cd CampusHub
```

### 2. 配置后端数据库

后端启动时 Spring Boot 会自动执行 `init_schema.sql` 完成建表和默认数据初始化，**无需手动导入 SQL**。只需确保 MySQL 在运行并配置好密码。

在 `backend/src/main/resources/` 下创建 `application-local.properties`（已被 `.gitignore` 忽略）：

```properties
spring.datasource.password=你的MySQL密码
```

### 3. 安装前端依赖

```bash
cd frontend
npm ci
```

### 4. 启动服务

**方式一：一键启动（推荐）**

```bash
./start.sh
```

脚本会自动检查环境、清理端口、启动前后端。

**方式二：分别启动**

```bash
# 终端 1 — 启动后端
cd backend
./mvnw spring-boot:run

# 终端 2 — 启动前端
cd frontend
npm run dev
```

### 5. 访问应用

| 服务 | 地址 |
|------|------|
| 前端开发服务器 | http://localhost:5173 |
| 后端 API | http://localhost:8080 |

> 前端通过 Vite 代理将 `/api` 请求转发到后端 `8080` 端口，开发时无需手动配置接口地址。

---

## 配置方式

### 数据库配置

主配置文件 `backend/src/main/resources/application.properties` 中密码使用占位符 `${DB_PASSWORD:root}`，**不要直接修改主配置文件**。通过以下任一方式配置：

| 方式 | 操作 |
|------|------|
| 本地配置文件（推荐） | 在 `application-local.properties` 中写入 `spring.datasource.password=你的密码` |
| 环境变量 | 设置 `DB_PASSWORD` 环境变量 |

连接 URL 中已包含 `createDatabaseIfNotExist=true`，数据库会自动创建。

### 邮箱验证码配置

注册和密码重置流程需要邮箱验证码。未配置 SMTP 时系统自动退化为控制台日志输出，不会阻塞启动。

在 `application-local.properties` 中添加（以 QQ 邮箱为例）：

```properties
APP_MAIL_FROM=your-account@qq.com
SPRING_MAIL_HOST=smtp.qq.com
SPRING_MAIL_PORT=465
SPRING_MAIL_USERNAME=your-account@qq.com
SPRING_MAIL_PASSWORD=your-smtp-auth-code
SPRING_MAIL_PROTOCOL=smtp
SPRING_MAIL_SMTP_AUTH=true
SPRING_MAIL_SMTP_STARTTLS_ENABLE=false
SPRING_MAIL_SMTP_SSL_ENABLE=true
```

完整配置示例（QQ / 163 / Gmail）参见 `backend/邮箱验证码发信配置说明.md`。

### 邮箱域名白名单

```properties
app.auth.allowed-email-domains=nju.edu.cn,smail.nju.edu.cn,edu.cn
```

只有白名单内域名的邮箱可以注册。多个域名用英文逗号分隔。

### 文件上传配置

```properties
spring.servlet.multipart.max-file-size=5MB
spring.servlet.multipart.max-request-size=32MB
app.upload.dir=uploads
```

### 演示数据

```properties
app.demo-data.enabled=true
```

开启后启动时自动创建管理员和测试用户账号。

---

## 使用方法

### 基本操作流程

1. **注册/登录**：使用校园邮箱注册（需验证码），或直接使用测试账号登录
2. **浏览需求**：在首页查看需求列表，支持按分类/校区/关键词筛选
3. **发布需求**：点击发布，填写需求信息（标题、描述、分类、校区、报酬等），支持匿名发布和图片上传
4. **接单**：在需求详情页点击接单
5. **执行订单**：接单方开始执行，完成后提交凭证图片
6. **确认完成**：发布者确认完成后订单完结，悬赏金转入接单方余额
7. **评价**：双方互相评价（1-5 星 + 评论）
8. **仲裁**（如有争议）：任意方可发起仲裁，由管理员裁决

### 管理员操作

登录管理员账号后访问管理后台：

- **用户管理**：搜索、筛选、封禁/解封、修改角色
- **需求审核**：审核待通过的需求
- **仲裁处理**：处理仲裁中的订单
- **仪表盘**：查看平台运营数据

### 前端构建

```bash
cd frontend
npm run build      # 类型检查 + 生产构建，输出到 dist/
npm run preview    # 预览构建结果
npm run type-check # 仅类型检查
```

### 后端构建

```bash
cd backend
./mvnw clean package -DskipTests    # 构建（跳过测试）
./mvnw clean package                 # 构建 + 运行测试
./mvnw test                          # 仅运行测试
```

---

## 测试账号

启动后自动初始化以下账号：

| 来源 | 学号 | 邮箱 | 密码 | 角色 |
|------|------|------|------|------|
| 演示数据 | admin | admin@campushub.local | Admin1234 | 管理员 |
| 建表脚本 | ADMIN001 | admin@edu.cn | Admin123! | 管理员 |
| 建表脚本 | TEST001 | test1@edu.cn | Admin123! | 普通用户 |
| 建表脚本 | TEST002 | test2@edu.cn | Admin123! | 普通用户 |

> 新用户注册后初始信用分为 100，初始余额为 100.00。

---

## API 概览

所有 API 统一前缀 `/api/v1`，响应格式 `{ code, message, data }`（成功时 `code=0`）。认证通过 `Authorization: Bearer <token>` 请求头。

| 模块 | 主要端点 |
|------|---------|
| 认证 | `POST /auth/register`、`POST /auth/login`、`POST /auth/email-code`、`POST /auth/change-password`、`POST /auth/password-reset` |
| 用户 | `GET /users/me`、`PUT /users/me`、`GET /users/{id}/reviews` |
| 需求 | `POST /demands`、`GET /demands`、`GET /demands/{id}`、`PUT /demands/{id}`、`POST /demands/{id}/withdraw`、`POST /demands/{id}/accept` |
| 订单 | `GET /orders`、`GET /orders/{id}`、`PUT /orders/{id}`、`POST /orders/{id}/arbitration`、`POST /orders/{id}/reviews` |
| 通知 | `GET /notifications`、`POST /notifications/{id}/read` |
| 推荐 | `GET /recommendations` |
| 文件 | `POST /upload/images`、`GET /uploads/{year}/{month}/{filename}` |
| 管理 | `GET /admin/dashboard`、`GET /admin/users`、`POST /admin/users/{id}/ban`、`GET /admin/demands/pending`、`POST /admin/demands/{id}/review`、`GET /admin/orders/arbitration`、`POST /admin/orders/{id}/arbitration/resolve` |

---

## 数据库设计

数据库名 `campushub`，字符集 `utf8mb4`，共 8 张表：

| 表名 | 说明 |
|------|------|
| `sys_user` | 用户主表（邮箱、学号、密码、角色、信用分、余额） |
| `ord_demand` | 需求主表（标题、描述、分类、校区、报酬、状态、匿名） |
| `ord_order` | 订单主表（需求关联、双方 ID、状态、凭证） |
| `ord_order_status_log` | 订单状态变更日志 |
| `ord_review` | 评价表（评分、评论，单向评价） |
| `ast_ledger` | 资产流水表（充值、冻结、解冻、转移） |
| `sys_notification` | 站内通知表（9 种类型） |
| `rec_user_action_log` | 推荐系统用户行为日志 |

> 测试环境使用 H2 内存数据库（MySQL 兼容模式），schema 文件独立于生产环境，互不影响。

---

## CI/CD

项目同时支持 GitLab CI 和 GitHub Actions，两者逻辑等价、路径过滤规则一致。

### GitLab CI

- `.gitlab-ci.yml`：父流水线，按路径变更路由触发子流水线
- `.gitlab/backend.yml`：后端子流水线（static-check → unit-test → integration-test → build）
- `.gitlab/frontend.yml`：前端子流水线（static-check → build）

### GitHub Actions

- `.github/workflows/backend-ci.yml`：后端 CI（4 个 job）
- `.github/workflows/frontend-ci.yml`：前端 CI（2 个 job）

**触发条件**：仅对应目录下的文件变更时触发（如 `backend/**` 变更触发后端 CI）。

---

## 开源协议

本项目为南京大学智能软件与工程学院软件工程与计算II课程大作业项目，基于MIT协议开源。

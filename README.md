# CampusHub — 校园互助服务平台

> 一个面向南京大学高校校园场景的需求发布、接单执行、评价反馈全链路互助平台。学生可以发布跑腿代办、学习辅导、活动组队、二手交易等各类校园需求，其他学生接单履约后获得悬赏，平台提供悬赏金担保、信用体系、匿名保护、站内通知、个性化推荐、AI 草稿生成和管理后台，构建安全可信的校园互助闭环。

## 目录

- [项目简介](#项目简介)
- [核心亮点](#核心亮点)
- [功能特性](#功能特性)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [依赖环境](#依赖环境)
- [安装步骤](#安装步骤)
- [配置方式](#配置方式)
- [测试与质量保障](#测试与质量保障)
- [使用方法](#使用方法)
- [API 概览](#api-概览)
- [数据库设计](#数据库设计)
- [CI/CD](#cicd)
- [开源协议](#开源协议)

---

## 项目简介

CampusHub 采用前后端分离的 Monorepo 架构，围绕"校园需求匹配"核心场景，构建了从需求发布、审核、接单、执行、完成到评价的完整业务闭环，并辅以悬赏金冻结/转移、信用积分、站内通知、个性化推荐、AI 草稿生成和管理后台等功能模块。

**核心业务流程：**

```
发布需求 → 管理员审核 → 公开浏览 → 接单 → 开始执行 → 双方确认完成 → 互相评价
                                                                ↓
                                                          发起仲裁（如有争议）
```

> 悬赏金担保：发布需求时冻结悬赏金，订单完成时自动转入接单方余额，订单取消或撤回复原，全程无需第三方担保，保障交易安全。

---

## 核心亮点

1. **完整业务闭环与资金保障**：需求发布→审核→接单→履约→双向确认→评价全流程闭环；悬赏金冻结/转移/解冻状态机 + 48 小时超时自动完成 + 人工仲裁，解决小额互助"不敢交易、不可追责"的信任问题。
2. **校园身份与信用治理**：邮箱域名白名单注册、BCrypt 密码存储、信用分动态演化、敏感词过滤、匿名发布、私密凭证图片"服务端授权访问 + 前端 Blob 拉取 + 所有权校验"，构建可信校园生态。
3. **AI 自然语言需求草稿生成**：基于 Spring AI 接入 OpenAI 兼容接口，一句话描述需求即可生成结构化发布草稿，服务端二次校验确保字段合法，降低发布门槛。

---

## 功能特性

### 用户与认证

- 校园邮箱注册（域名白名单校验），邮箱验证码注册与密码重置（未配置 SMTP 时自动退化为控制台日志输出）
- 基于 JWT 的无状态认证（HS256/384 签名，有效期 1 小时），角色与状态以数据库当前值为准
- BCrypt 密码加密

### 需求管理

- 七大分类：跑腿代取（EXPRESS）、委托代办（ERRAND）、学习辅导（STUDY_TUTORING）、二手交易（SECOND_HAND）、活动组队（TEAM_UP）、互助问答（HELP）、其他（OTHER）
- 三大校区：鼓楼、仙林、苏州
- 多维筛选：关键词、分类、校区、地点、时间范围
- 四种排序：时间、距离、报酬、推荐
- 匿名发布（生成匿名识别码，保护发布者身份）、敏感词过滤、本地草稿自动保存
- AI 草稿生成：自然语言转结构化发布草稿

### 需求-响应-订单三层互动模型

- 一个需求承载三种互动方式：直接接单（DIRECT_ACCEPT）、择优选择（SELECT_ONE）、满员组队（SELECT_MANY）
- 留言/报名/回答等多种响应形式，按互动模式自动分流

### 订单系统

- 完整状态流转：已接单 → 进行中 → 已完成 / 仲裁中 / 已取消
- 双向确认完成机制（接单方提交私密凭证图片，发布者确认）
- 48 小时超时自动完成
- 仲裁申请与管理员裁决
- 悬赏金冻结与转移（数据库原子更新，消除并发竞态）

### 评价与信用

- 1-5 星评分 + 文字评论，双向评价
- 信用分动态计算（历史权重 0.9 + 新评价权重 0.1）
- 信用等级：金牌助教（95+）、银牌助教（85+）、成长中

### 通知系统

- 10 种通知类型：接单、状态变更、收到评价、待审核、审核通过/驳回、待评价提醒、仲裁申请/处理、响应评价
- 标记已读 / 全部已读，点击通知智能跳转关联页面

### 个性化推荐

- 冷启动算法（新用户）：40% 报酬 + 30% 紧急度 + 30% 新鲜度
- 热启动算法（有历史接单）：50% 分类偏好 + 20% 报酬 + 15% 紧急度 + 15% 新鲜度
- 基于用户行为日志的偏好建模、浏览抑制与多样性重排

### 管理后台

- 用户管理：搜索、筛选、封禁/解封、角色修改
- 需求审核：通过/驳回（需填写理由）
- 仲裁处理：裁决完成或取消
- 仪表盘统计：DAU、总用户数、需求数、待审核数、订单数、分类分布

### 文件上传

- 仅已登录用户可上传（USER/ADMIN），游客返回 401
- 格式校验三层：扩展名 + content-type + 文件头魔数签名（jpg/png/webp）
- 单文件上限 10MB，单次最多 6 张
- 服务端生成随机文件名，防路径穿越，按年月目录存储
- 私密凭证图片：授权访问 + 前端 Blob 拉取，不暴露公网 URL

### AI 需求草稿生成

- 自然语言转结构化草稿：自动提取标题、分类、校区、时间、报酬、联系方式等字段
- 服务端二次校验：枚举合法性、时间格式、字段长度、missingFields 白名单规范化
- 空值安全：未配置 `AI_BASE_URL` / `AI_MODEL` / `AI_API_KEY` 环境变量时应用正常启动，AI 接口返回明确业务错误
- 限流保护：每用户 1 分钟 5 次 + 全局 3 并发，异常不泄露密钥与内部堆栈

---

## 技术栈

### 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 运行时 |
| Spring Boot | 3.5.0 | 应用框架 |
| MyBatis-Plus | 3.5.7 | ORM 框架 / 持久化 |
| MySQL | 8.0.16+ | 生产数据库 |
| H2 Database | (继承) | 测试数据库 |
| Spring AI | 1.0.0 | AI 草稿生成（OpenAI-compatible） |
| Spring Security Crypto | (继承) | BCrypt 密码加密 |
| Spring Boot Mail | (继承) | SMTP 邮件发送 |
| JJWT | 0.12.6 | JWT 生成与校验 |
| JUnit 5 / Mockito | (继承) | 单元与集成测试 |

### 前端

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue | ^3.5.32 | 核心框架 |
| TypeScript | ~6.0.2 | 类型系统 |
| Vite | ^8.0.4 | 构建工具 |
| Pinia | ^3.0.4 | 状态管理 |
| Vue Router | ^4.6.4 | 路由管理 |
| Vitest | (随 Vite) | 组件/视图测试 |

---

## 项目结构

```
CampusHub/
├── .github/workflows/          # GitHub Actions CI 配置
├── .gitlab-ci.yml / .gitlab/   # GitLab CI 配置
├── backend/                    # Spring Boot 后端
│   ├── mvnw / mvnw.cmd         # Maven Wrapper
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/campushub/backend/
│       │   │   ├── BackendApplication.java   # 入口类
│       │   │   ├── ai/                       # AI 草稿生成模块
│       │   │   ├── api/                      # 控制器层 + 视图对象
│       │   │   ├── auth/                     # 认证模块
│       │   │   ├── demand/                   # 需求模块
│       │   │   ├── order/                    # 订单模块（含仲裁、资金）
│       │   │   ├── review/                   # 评价模块
│       │   │   ├── notification/             # 通知模块
│       │   │   ├── recommendation/           # 推荐模块
│       │   │   ├── admin/                    # 管理后台模块
│       │   │   ├── upload/                   # 文件上传模块
│       │   │   └── common/                   # 公共模块（异常/安全/响应）
│       │   └── resources/
│       │       ├── application.properties    # 主配置（环境变量占位）
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
│       ├── views/              # 页面组件
│       ├── components/         # 可复用组件
│       ├── composables/        # 组合式函数
│       ├── types/              # TypeScript 类型定义
│       └── utils/              # 工具函数
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

> 仓储层采用双实现架构：生产环境使用 `MyBatis*Repository`（MySQL），测试环境使用 `InMemory*Repository`（内存），在保持领域模型纯净的同时兼顾生产性能与测试成本。

---

## 依赖环境

| 工具 | 版本要求 | 说明 |
|------|---------|------|
| JDK | 21 | 后端编译运行 |
| MySQL | 8.0.16+ | 生产数据库（CHECK 约束强制执行需要 8.0.16+） |
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

> 前端通过 Vite 代理将 `/api` 请求转发到后端 `8080` 端口。

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
SPRING_MAIL_SMTP_AUTH=true
SPRING_MAIL_SMTP_STARTTLS_ENABLE=false
SPRING_MAIL_SMTP_SSL_ENABLE=true
```

### AI 草稿生成配置

CampusHub 集成了基于 Spring AI 的需求草稿生成（`POST /api/v1/ai/demand-draft`），支持 OpenAI-compatible Chat Completions 接口。**所有 AI 配置项必须通过环境变量注入，配置文件中不提供任何真实默认值或硬编码密钥。**

#### 必需环境变量

| 环境变量 | 对应配置项 | 说明 |
|---------|-----------|------|
| `AI_BASE_URL` | `spring.ai.openai.base-url` | OpenAI-compatible API 的 Base URL |
| `AI_MODEL` | `spring.ai.openai.chat.options.model` | 调用的模型名称 |
| `AI_API_KEY` | `spring.ai.openai.api-key` | API 密钥 |

> 未设置时应用仍可正常启动，AI 接口调用将返回明确的业务错误提示。配置文件中仅保留占位符（`https://placeholder.invalid` / `not-configured` / 空）以维持 Spring Boot 自动配置的 Bean 初始化结构，由 `AiConfig` 检测空值/占位符后跳过 `ChatClient` 创建。

#### 本地开发

**PowerShell：**

```powershell
$env:AI_BASE_URL = "Your AI_BASE_URL"
$env:AI_MODEL = "Your AI_MODEL"
$env:AI_API_KEY = "sk-your-api-key"
cd backend
./mvnw spring-boot:run
```

**bash / zsh：**

```bash
export AI_BASE_URL="Your AI_BASE_URL"
export AI_MODEL="Your AI_MODEL"
export AI_API_KEY="sk-your-api-key"
cd backend
./mvnw spring-boot:run
```

#### 服务商配置示例

##### DeepSeek（OpenAI-compatible）

```bash
export AI_BASE_URL="https://api.deepseek.com"
export AI_MODEL="deepseek-flash"
export AI_API_KEY="sk-your-deepseek-api-key"
```

##### Qwen（通义千问 DashScope OpenAI-compatible 接口）

```bash
export AI_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
export AI_MODEL="qwen-plus"
export AI_API_KEY="sk-your-dashscope-api-key"
```

##### OpenAI

```bash
export AI_BASE_URL="https://api.openai.com/v1"
export AI_MODEL="gpt-4o-mini"
export AI_API_KEY="sk-your-openai-api-key"
```

#### 可选调优参数

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| `AI_CONNECT_TIMEOUT_MS` | `10s` | HTTP 连接超时 |
| `AI_READ_TIMEOUT_MS` | `60s` | HTTP 读取超时 |
| `AI_RETRY_MAX_ATTEMPTS` | `2` | Spring AI 重试次数上限 |
| `AI_DEMAND_DRAFT_PROMPT_MAX_LENGTH` | `1000` | 用户输入自然语言最大字符数 |
| `AI_DEMAND_DRAFT_RATE_LIMIT_WINDOW_MS` | `60000` | 限流窗口时长（毫秒） |
| `AI_DEMAND_DRAFT_RATE_LIMIT_MAX_PER_WINDOW` | `5` | 每用户每窗口最大请求数 |
| `AI_DEMAND_DRAFT_RATE_LIMIT_MAX_CONCURRENT` | `3` | 全局最大并发请求数 |

#### 安全注意事项

- **API Key 安全**：密钥仅通过环境变量传递，不记录到日志、不传给 LLM（用户身份信息不传给 AI provider）
- **空值安全**：`AiConfig` 检测到 `AI_BASE_URL` / `AI_MODEL` / `AI_API_KEY` 任一为空或占位符后跳过 `ChatClient` 创建；`AiDemandApplicationServiceImpl` 在 `ChatClient` 为 null 时返回明确业务错误，不泄露内部堆栈或密钥
- **异常处理**：所有 provider 原始异常（401/403/429/5xx/网络故障/JSON 解析失败）被转换为业务错误

### 文件上传配置

```properties
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=64MB
app.upload.dir=uploads
```

### 演示数据

```properties
app.demo-data.enabled=true
```

开启后启动时自动创建管理员和测试用户账号。


## 测试与质量保障

### 后端（536 个测试）

- AI 草稿生成服务与限流器单元测试（64）
- 需求/订单/评价/通知/推荐等业务服务单元测试与集成测试
- 安全边界集成测试：游客 401、越权 403、超时 409、伪造上传、路径穿越
- 事务隔离、并发竞态（余额原子更新、凭证绑定、订单状态机）回归测试
- 使用 H2 内存数据库（MySQL 兼容模式），构建与测试命令：

```bash
cd backend
./mvnw clean package -DskipTests   # 构建（跳过测试）
./mvnw test                        # 运行测试
```

### 前端（Vitest）

- 需求发布、详情、订单、认证、错误处理、竞态保护等组件/视图测试
- 构建与测试命令：

```bash
cd frontend
npm run build           # 类型检查 + 生产构建
npm run type-check      # 仅类型检查
npm test                # 运行 Vitest
```

---

## 使用方法

### 基本操作流程

1. **注册/登录**：使用校园邮箱注册（需验证码），或直接使用内置测试账号登录
2. **浏览需求**：在首页查看需求列表，支持按分类/校区/关键词筛选与排序
3. **发布需求**：点击发布，填写需求信息（标题、描述、分类、校区、报酬等），支持 AI 草稿生成、匿名发布和图片上传
4. **接单**：在需求详情页点击接单，按互动模式（直接接单/择优/组队）自动分流
5. **执行订单**：接单方开始执行，完成后提交私密凭证图片
6. **确认完成**：发布者确认完成后订单完结，悬赏金转入接单方余额
7. **评价**：双方互相评价（1-5 星 + 评论）
8. **仲裁**（如有争议）：任意方可发起仲裁，由管理员裁决

### 管理员操作

- **用户管理**：搜索、筛选、封禁/解封、角色修改
- **需求审核**：审核待通过的需求
- **仲裁处理**：处理仲裁中的订单
- **仪表盘**：查看平台运营数据

---

## 测试账号

以下账号在数据库初始化时由 `init_schema.sql` 预置，启动后即可登录：

| 学号 | 邮箱 | 密码 | 角色 |
|------|------|------|------|
| ADMIN001 | admin@edu.cn | Admin123! | 管理员 |
| TEST001 | test1@edu.cn | Admin123! | 普通用户 |
| TEST002 | test2@edu.cn | Admin123! | 普通用户 |

> 新用户注册后初始信用分为 100，初始余额为 100.00。

---

## API 概览

所有 API 统一前缀 `/api/v1`，响应格式 `{ code, message, data }`（成功时 `code=0`）。认证通过 `Authorization: Bearer <token>` 请求头。

| 模块 | 主要端点 |
|------|---------|
| 认证 | `POST /auth/register`、`POST /auth/login`、`POST /auth/email-code`、`POST /auth/change-password`、`POST /auth/password-reset/code`、`POST /auth/password-reset` |
| 用户 | `GET /users/me`、`PUT /users/me`、`GET /users/{id}/reviews` |
| 需求 | `POST /demands`、`GET /demands`、`GET /demands/{id}`、`PUT /demands/{id}`、`POST /demands/{id}/withdraw`、`POST /demands/{id}/accept`、`POST /demands/{id}/responses` |
| 订单 | `GET /orders`、`GET /orders/{id}`、`PUT /orders/{id}`、`POST /orders/{id}/arbitration`、`POST /orders/{id}/reviews` |
| 通知 | `GET /notifications`、`POST /notifications/{id}/read` |
| 推荐 | `GET /recommendations` |
| AI | `POST /ai/demand-draft`（登录后可用，自然语言转需求草稿 JSON） |
| 文件 | `POST /upload/images`、`GET /uploads/{year}/{month}/{filename}` |
| 管理 | `GET /admin/dashboard`、`GET /admin/users`、`POST /admin/users/{id}/ban`、`GET /admin/demands/pending`、`POST /admin/demands/{id}/review`、`GET /admin/orders/arbitration`、`POST /admin/orders/{id}/arbitration/resolve` |

---

## 数据库设计

数据库名 `campushub`，字符集 `utf8mb4`，共 8 张表：

| 表名 | 说明 |
|------|------|
| `sys_user` | 用户主表（邮箱、学号、密码、角色、信用分、余额、冻结余额） |
| `ord_demand` | 需求主表（标题、描述、分类、校区、报酬、状态、匿名、互动模式、联系方式） |
| `ord_demand_response` | 需求响应表（留言/报名/回答，状态 PENDING/SELECTED/REJECTED/WITHDRAWN） |
| `ord_order` | 订单主表（需求关联、双方 ID、状态、凭证） |
| `ord_order_status_log` | 订单状态变更日志 |
| `ord_review` | 评价表（评分、评论，单向评价） |
| `sys_notification` | 站内通知表（10 种类型） |
| `rec_user_action_log` | 推荐系统用户行为日志表（VIEW/ACCEPT） |

> 测试环境使用 H2 内存数据库（MySQL 兼容模式）；本地开发环境使用 MySQL，由 `init_schema.sql` 自动建表与初始化。

---

## CI/CD

项目同时支持 GitLab CI 和 GitHub Actions，两者逻辑等价、路径过滤规则一致。

### GitLab CI

- `.gitlab-ci.yml`：父流水线，按路径变更路由触发子流水线
- `.gitlab/backend.yml`：static-check → unit-test → integration-test → build
- `.gitlab/frontend.yml`：static-check → build

### GitHub Actions

- `.github/workflows/backend-ci.yml`：后端 CI（4 个 job）
- `.github/workflows/frontend-ci.yml`：前端 CI（2 个 job）

**触发条件**：仅对应目录下的文件变更时触发（如 `backend/**` 变更触发后端 CI）。

---

## 开源协议

本项目基于 MIT 协议开源。

- 作品名称：南京大学 CampusHub 校园服务平台

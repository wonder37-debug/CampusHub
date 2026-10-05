# 仓储层统一阶段 2D 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为生产 `init_schema.sql` 与 H2 `schema-*.sql` 补齐索引并完全对齐，为 2B SQL 下推提供性能支撑。

**Architecture:** 单 task：改 1 个生产 schema + 5 个 H2 schema 文件，追加 CREATE INDEX（+ review rating CHECK），mvn test 验证 H2 语法正确、不回归。

**Tech Stack:** MySQL 8（生产）/ H2 MySQL 兼容模式（测试）。

**Spec:** `docs/superpowers/specs/2026-09-28-repository-unify-2d-design.md`

## Global Constraints
- Java 21，Spring Boot 3.5.0，MyBatis-Plus 3.5.7
- 允许加索引与必要微调（不加/删列、不加外键）
- 前端兼容契约：仅加索引，不改表结构，API 行为不变
- 现有测试全绿（基线 125/125，2A 后）
- 工作分支 `refactor/optimization`（HEAD `4fece7b`）
- 本机 `JAVA_HOME` 需 JDK 21；bash 禁管道/分号/&&/重定向，Maven 用 `.\mvnw.cmd` + workdir=backend

---

## Task 1: 补齐生产 + H2 schema 索引

**Files:**
- Modify: `backend/src/main/resources/init_schema.sql`
- Modify: `backend/src/test/resources/schema-demand.sql`
- Modify: `backend/src/test/resources/schema-order.sql`
- Modify: `backend/src/test/resources/schema-review.sql`
- Modify: `backend/src/test/resources/schema-notification.sql`
- Modify: `backend/src/test/resources/schema-recommendation.sql`

**Interfaces:**
- Consumes: 无
- Produces: 生产与 H2 schema 索引完全对齐

- [ ] **Step 1: 读 6 个 schema 文件确认现有索引/约束位置**

Read 每个文件，确认现有 CREATE TABLE/INDEX/CHECK 结构与插入点（避免重复索引、找准追加位置）。

- [ ] **Step 2: 生产 `init_schema.sql` 追加索引**

在 `ord_demand` 表 CREATE 之后追加：
```sql
CREATE INDEX idx_demand_publisher ON ord_demand(publisher_id);
CREATE INDEX idx_demand_status ON ord_demand(status);
CREATE INDEX idx_demand_category ON ord_demand(category);
CREATE INDEX idx_demand_campus_zone ON ord_demand(campus_zone);
CREATE INDEX idx_demand_created_at ON ord_demand(created_at);
```
在 `ast_ledger` 表 CREATE 之后追加：
```sql
CREATE INDEX idx_ledger_user ON ast_ledger(user_id);
```
在 `ord_review` 表 CREATE 之后（`idx_review_target` 之后）追加：
```sql
CREATE INDEX idx_review_author ON ord_review(author_id);
```
用 Edit 精确插入（Read 后定位锚点）。

- [ ] **Step 3: H2 `schema-demand.sql` 追加 ord_demand 5 索引**（与生产 4.1 同名同列）

- [ ] **Step 4: H2 `schema-order.sql` 补丢失索引**
```sql
CREATE INDEX idx_order_publisher ON ord_order(publisher_id);
CREATE INDEX idx_order_accepter ON ord_order(accepter_id);
CREATE INDEX idx_order_status_log_order ON ord_order_status_log(order_id);
```

- [ ] **Step 5: H2 `schema-review.sql` 补丢失索引 + CHECK**
```sql
CREATE INDEX idx_review_target ON ord_review(target_id);
CREATE INDEX idx_review_author ON ord_review(author_id);
ALTER TABLE ord_review ADD CONSTRAINT chk_review_rating CHECK (rating BETWEEN 1 AND 5);
```
（若 H2 不支持 ALTER TABLE ADD CONSTRAINT AFTER CREATE，改为在 CREATE TABLE 内联 CHECK；Read 文件确认现有 rating 列定义后决定）

- [ ] **Step 6: H2 `schema-notification.sql` 补索引**
```sql
CREATE INDEX idx_notify_user_read ON sys_notification(user_id, is_read);
```

- [ ] **Step 7: H2 `schema-recommendation.sql` 补索引**
```sql
CREATE INDEX idx_action_user_cat ON rec_user_action_log(user_id, category);
```

- [ ] **Step 8: 跑全量测试验证**

Run（bash workdir=backend，JAVA_HOME 设 JDK 21，`.\mvnw.cmd test -q`）
Expected: BUILD SUCCESS，125/125 全绿。若 H2 报索引/约束语法错：Read 错误 + 调整 SQL 语法（H2 MySQL 模式兼容），不改 Java。

- [ ] **Step 9: 提交（两条独立单命令，workdir 仓库根）**
- `git add backend/src/main/resources/init_schema.sql backend/src/test/resources/schema-demand.sql backend/src/test/resources/schema-order.sql backend/src/test/resources/schema-review.sql backend/src/test/resources/schema-notification.sql backend/src/test/resources/schema-recommendation.sql`
- `git commit -m "perf(schema): 补齐生产与 H2 测试 schema 索引并对齐"`

---

## 验收清单
- [ ] `cd backend && ./mvnw clean package` 构建成功
- [ ] `cd backend && ./mvnw test` 全绿，125/125
- [ ] 生产 `init_schema.sql` 含 idx_demand_*（5）+ idx_ledger_user + idx_review_author
- [ ] H2 各 schema-*.sql 索引与生产同名同列对齐
- [ ] 无 Java 代码改动

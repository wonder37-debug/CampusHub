# 仓储层统一阶段 2D 设计（索引补齐）

- 日期：2026-09-28
- 状态：待评审
- 分支：`refactor/optimization`（2A 已完成，HEAD `4fece7b`）
- 范围：生产 `init_schema.sql` + H2 测试 `schema-*.sql` 索引同步补齐
- 上游：子项目2 仓储统一的阶段四（2D），为 2B SQL 下推提供性能支撑

## 1. 背景与现状（探索已核实）

### 1.1 生产 `init_schema.sql` 索引缺失
| 表 | 现状 | 缺失 |
|---|---|---|
| `ord_demand` | **零索引** | `publisher_id`/`status`/`category`/`campus_zone`/`created_at` 全裸 → findAll/filter 全表扫 |
| `ast_ledger` | 无 `user_id` 索引 | `user_id`（虽无对应 Repository，但余额流水查询会用） |
| `ord_review` | 有 `idx_review_target(target_id)`、`uk_review_order_author(order_id,author_id)` | `author_id` 无独立索引（`findByAuthorId` 走联合唯一键前缀，可用但不最优） |
| `sys_user`/`ord_order`/`ord_order_status_log`/`sys_notification`/`rec_user_action_log` | 索引较完善 | 无需补 |

### 1.2 H2 测试 `schema-*.sql` 与生产索引不同步
| 测试 schema 文件 | 丢失的索引（生产有） |
|---|---|
| `schema-demand.sql` | 无任何索引（生产也无，2D 一并补） |
| `schema-order.sql` | `idx_order_publisher`、`idx_order_accepter`、`idx_order_status_log_order` |
| `schema-review.sql` | `idx_review_target`（+ rating CHECK） |
| `schema-notification.sql` | `idx_notify_user_read(user_id,is_read)` |
| `schema-recommendation.sql` | `idx_action_user_cat(user_id,category)` |

> 生产与测试 schema 索引不一致是潜在隐患：测试可能掩盖生产查询性能问题，且 2B SQL 下推后索引差异会让测试性能特征失真。

## 2. 目标 / 非目标

### 目标
1. 生产 `init_schema.sql` 为 `ord_demand`、`ast_ledger`、`ord_review` 补索引。
2. H2 `schema-*.sql` 与生产索引完全对齐（补丢失索引 + 同步新增）。
3. `mvn test` 全绿（H2 schema 加索引后语法正确、测试不回归）。

### 非目标
- SQL 下推（分页/排序/LIKE）→ 2B
- N+1 修复 → 2C
- `mybatis.type-aliases-package` 配置修正 → 2E
- 不改表结构/列/外键，仅加索引（与 CHECK 约束对齐时一并补丢失的 CHECK）

## 3. 约束（继承自主 spec）
- 允许加索引与必要微调（用户确认）
- 前端兼容契约：schema 改动不影响 API（仅加索引，不改表结构）
- 现有测试全绿（基线 125/125）
- 工作分支 `refactor/optimization`
- 生产 `init_schema.sql` 是 `spring.sql.init` 启动时执行；H2 `schema-*.sql` 由测试 `@Sql`/`spring.sql.init.schema-locations` 加载

## 4. 详细设计

### 4.1 生产 `init_schema.sql` 新增索引
在对应表 CREATE 之后、下一表之前追加：
```sql
-- ord_demand（原零索引）
CREATE INDEX idx_demand_publisher ON ord_demand(publisher_id);
CREATE INDEX idx_demand_status ON ord_demand(status);
CREATE INDEX idx_demand_category ON ord_demand(category);
CREATE INDEX idx_demand_campus_zone ON ord_demand(campus_zone);
CREATE INDEX idx_demand_created_at ON ord_demand(created_at);

-- ast_ledger
CREATE INDEX idx_ledger_user ON ast_ledger(user_id);

-- ord_review（author_id 独立索引，补充 idx_review_target）
CREATE INDEX idx_review_author ON ord_review(author_id);
```

### 4.2 H2 `schema-*.sql` 与生产对齐
- `schema-demand.sql`：追加 4.1 的 5 条 ord_demand 索引（H2 语法同 MySQL）
- `schema-order.sql`：补 `idx_order_publisher`、`idx_order_accepter`（ord_order）+ `idx_order_status_log_order`（ord_order_status_log）
- `schema-review.sql`：补 `idx_review_target`（ord_review）+ 4.1 的 `idx_review_author` + rating CHECK（与生产 `chk_review_rating` 对齐）
- `schema-notification.sql`：补 `idx_notify_user_read(user_id,is_read)`
- `schema-recommendation.sql`：补 `idx_action_user_cat(user_id,category)`

> 索引名与生产完全一致，避免"同名不同表"混淆。H2 支持 `CREATE INDEX ... ON table(col)` 标准 SQL，与 MySQL 语法一致。

## 5. 影响面
| 文件 | 操作 |
|---|---|
| `backend/src/main/resources/init_schema.sql` | 追加 7 条 CREATE INDEX |
| `backend/src/test/resources/schema-demand.sql` | 追加 5 条 ord_demand 索引 |
| `backend/src/test/resources/schema-order.sql` | 追加 3 条索引 |
| `backend/src/test/resources/schema-review.sql` | 追加 2 条索引 + rating CHECK |
| `backend/src/test/resources/schema-notification.sql` | 追加 1 条索引 |
| `backend/src/test/resources/schema-recommendation.sql` | 追加 1 条索引 |

不改任何 Java 代码、Repository、Service、Controller。

## 6. 验收标准
1. `cd backend && ./mvnw clean package` 构建成功
2. `cd backend && ./mvnw test` 全绿，125/125 不回归
3. 生产 `init_schema.sql` 含 ord_demand 5 索引 + ast_ledger 1 + ord_review author_id 1
4. H2 各 `schema-*.sql` 索引与生产同名同列对齐
5. 前端兼容（API 行为不变）

## 7. 风险
| 风险 | 对策 |
|---|---|
| H2 不支持某索引语法 | H2 兼容 MySQL 模式，标准 CREATE INDEX 均支持；若失败，调整语法 |
| 索引名与既有约束冲突 | 新索引名均以 `idx_` 前缀，避开 `uk_`/`chk_` |
| 重复加索引（生产已有） | 仅补缺失项，已存在的 idx_order_publisher 等在 H2 schema 补、生产不动 |

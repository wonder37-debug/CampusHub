# 仓储层统一阶段 2B 设计（SQL 下推）

- 日期：2026-09-28
- 状态：待评审
- 分支：`refactor/optimization`（2A/2D/2E 已完成，HEAD `a7f166b`）
- 范围：Repository 接口扩展 + Service 查询下沉 SQL（分页/排序/过滤/LIKE/聚合）

## 1. 背景与现状（探索已核实）

全仓 10 处 `findAll()`/`findByXxx()` 后在 Java 内存做 filter/sort/paginate，**无任何 SQL 级 orderBy/limit/like/count**（仅 Order loadHistory 用了 orderByAsc）。数据量增长时全表扫描+内存排序会成瓶颈。

| # | 位置 | 现状 |
|---|---|---|
| 1 | `DemandApplicationServiceImpl.list:128-147` | `findAll`+6 filter+sort+subList 分页 |
| 2 | `NotificationApplicationServiceImpl.list:107-119` | `findByUserId`+filter unread+sort+分页 |
| 3 | `RecommendationApplicationServiceImpl.filterCandidateDemands:140-151` | `findAll`+filter（含跨仓储 N+1，2C 修） |
| 4 | `AdminApplicationServiceImpl.listUsers:84-98` | `findAll`+filter+sort+分页 |
| 5 | `AdminApplicationServiceImpl.listPendingDemands:160-174` | `findByStatus`+keyword/category/zone/排序/分页 内存 |
| 6 | `AdminApplicationServiceImpl.listArbitrationOrders:183-193` | `findAll`+filter+sort+分页 |
| 7 | `AdminApplicationServiceImpl.getDashboard:300-324` | 3 `findAll`+内存聚合统计 |
| 8 | `ReviewApplicationServiceImpl.listUserReviews:94-106` | 2 查询合并+sort+分页 |
| 9 | `OrderApplicationServiceImpl.listHistory:188-199` | `findByParticipant`+sort+分页 内存 |
| 10 | `DemandApplicationServiceImpl` 注释（接口自述） | 当前由 Service 层完成筛选/排序 |

## 2. 目标 / 非目标

### 目标
1. 为各 Repository 扩展分页/过滤/排序查询方法，把 filter/sort/paginate/count 下沉 SQL（LambdaQueryWrapper + `selectPage`/`selectCount` + `orderBy` + `like`/`eq` + `last("LIMIT ...")` 或 Page）。
2. Service 层改用新 Repository 方法，删除 `findAll`+内存处理。
3. 保持 API 响应契约不变（`PageResponse` 结构、`DemandSummaryResponse` 等不变）。
4. 测试全绿（@SpringBootTest + H2 验证 SQL 下推正确性）。

### 非目标
- N+1 修复（Order loadHistory 批量、Service 跨仓储循环）→ 2C
- 索引（2D 已做）
- 不改 Domain/Entity/Mapper 结构，仅扩 Repository 接口 + MyBatis 实现 + Service 调用

## 3. 约束（继承）
- 前端兼容契约：`PageResponse`/`*SummaryResponse` 字段不变；API 行为不变
- 现有测试全绿（基线 125/125）
- 工作分支 `refactor/optimization`
- Repository 接口扩方法，MyBatisDemandRepository 等实现；InMemory 已删（2A）

## 4. 设计

### 4.1 Repository 接口扩展模式
每个需下推的 Repository 新增方法（两种风格，按模块择一）：

**风格 A（推荐，分页+计数分离）**：
```java
List<Demand> findPage(DemandQuery query);   // SQL: filter+orderBy+LIMIT offset,size
long count(DemandQuery query);             // SQL: filter+count
```
Service 组装 `PageResponse(items.map(toSummary), page, size, total)`。Repository 内部提取 `buildWrapper(query)` 私有方法供 findPage/count 共用。

**风格 B（MyBatis-Plus Page）**：
```java
Page<DemandEntity> selectPage(Page<DemandEntity> page, LambdaQueryWrapper<DemandEntity> wrapper);
```
但暴露 Entity/Page 到 Repository 接口不够干净，风格 A 更贴合 DDD（Repository 返回 domain）。

### 4.2 过滤/排序下沉映射（demand 为例）
| 内存逻辑 | SQL 下推 |
|---|---|
| `isPubliclyVisible` + 自己的 | `status IN (公开状态) OR publisher_id = :uid` |
| `matchesKeyword(q)` | `title LIKE %q% OR description LIKE %q%` |
| `matchesCategory` | `category = :cat` |
| `matchesCampusZone` | `campus_zone = :zone` |
| `matchesLocation` | `location LIKE %loc%`（确认 matchesLocation 是 LIKE 还是 eq） |
| `matchesStartTimeRange` | `start_time BETWEEN :from AND :to` |
| `resolveComparator(TIME)` | `ORDER BY created_at DESC` |
| subList 分页 | `LIMIT :size OFFSET :offset`（或 Page） |

### 4.3 子阶段分解（逐个 spec/plan/SDD）
| 子阶段 | 范围 | 涉及 Repository |
|---|---|---|
| **2B.1** | demand list 下推（#1） | DemandRepository |
| **2B.2** | notification list 下推（#2） | NotificationRepository |
| **2B.3** | admin 4 处下推（#4/#5/#6/#7，含 getDashboard 聚合） | UserRepository/DemandRepository/OrderRepository |
| **2B.4** | review listUserReviews 下推（#8） | ReviewRepository |
| **2B.5** | order listHistory 下推（#9） | OrderRepository |
| **2B.6** | recommendation filterCandidateDemands 下推（#3，配合 2C N+1） | DemandRepository（复用 2B.1） |

> 每子阶段独立 spec/plan/SDD，独立可验收（测试全绿 + API 不变）。2B.3 的 getDashboard 聚合（count/sum 分组）需 SQL `selectCount`+`groupBy`，最复杂。

## 5. 影响面（每子阶段）
- Repository 接口加方法 + MyBatis 实现（LambdaQueryWrapper + selectPage/selectCount/orderBy/like）
- Service 改用新方法（删 findAll+内存处理）
- 测试适配（@SpringBootTest 断言不变，验证下推后结果一致）

## 6. 验收（2B 全部子阶段完成后）
1. `mvn test` 全绿
2. main 无 `findAll()` 后内存 filter/sort/分页（除 getDashboard 聚合外的 10 处全下推）
3. `PageResponse`/`*SummaryResponse` 字段不变
4. API 行为不变（FrontendIntegrationFlowTest 全绿）

## 7. 风险
| 风险 | 对策 |
|---|---|
| 下推后结果与内存过滤不一致（边界：null 处理、LIKE 大小写、时间边界） | 每子阶段测试对照原内存行为；H2 MySQL 模式 LIKE 大小写与生产 MySQL 一致 |
| getDashboard 聚合 SQL 复杂 | 2B.3 单独设计，可用 `selectCount`+`groupBy` 或多查询 |
| DemandSort RECOMMEND 仍走 recommendation（非纯 SQL） | 2B.1 仅下推非 RECOMMEND 排序；RECOMMEND 分支保留调 RecommendationApplicationService（2C 配合） |

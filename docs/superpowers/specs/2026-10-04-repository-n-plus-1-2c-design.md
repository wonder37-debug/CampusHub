# 子项目2C 设计（仓储层 N+1 修复）

- 日期：2026-10-04
- 状态：待评审
- 分支：`refactor/2c`（基于 `github/main` `ace729c`，2B 已合并）
- 上游：子项目2 仓储统一（2A/2B 已完成，2C 是 N+1 修复阶段）
- 范围：修复 2B 留的 deferred N+1 + 新发现的 N+1，仓储层 + Service 层跨仓储循环

## 1. 背景与现状（探索已核实）

2B 把 10 处 `findAll`/`findByXxx` + 内存过滤/排序/分页下沉为 SQL `findPage`/`count`。但多处 N+1（逐条查询、跨仓储循环、冗余加载）留 2C。grep + 代码核实，全仓 **8 处 N+1/冗余**：

### 1.1 仓储层 loadHistory N+1（`MyBatisOrderRepository.assembleAll`）

`assembleAll`（:173-180）对每个 `OrderEntity` 逐条调 `loadHistory(orderId)`（查 `ord_order_status_log`），N+1。

| # | 位置 | 调用方 | N+1 |
|---|---|---|---|
| 1 | `findByParticipant`（:82-90）→ `assembleAll` | `autoCompleteOverdueOrders`（OrderApplicationServiceImpl:197）/ `buildAcceptedCategoryStats`（RecommendationApplicationServiceImpl:145）/ `checkPendingReviewsAndAutoComplete`（DemandApplicationServiceImpl:409） | 逐 order `loadHistory` |
| 2 | `findAll`（:92-95）→ `assembleAll` | `getDashboard`（AdminApplicationServiceImpl:283，2B.3d 保留 dailyActiveUsers 用） | 逐 order `loadHistory` |

> `findById`（:56-65）/`findByDemandId`（:68-78）也调 `loadHistory`，但单条查询非 N+1。

### 1.2 Service 层跨仓储循环 N+1

| # | 位置 | 循环 | N+1 |
|---|---|---|---|
| 3 | `filterCandidateDemands`（RecommendationApplicationServiceImpl:139） | `findCandidatePage(userId, query).stream().filter(findByDemandId.isEmpty())` | 逐 demand 查 `ord_order` 是否存在（2B.6 deferred） |
| 4 | `buildAcceptedCategoryStats`（RecommendationApplicationServiceImpl:159） | `for (Order : findByParticipant) { demandRepository.findById(order.getDemandId()) }` | 逐 order 查 demand |
| 5 | `collectRecommendationActivity`（AdminApplicationServiceImpl:373） | `for (User : findAll) { userActionLogRepository.findByUserId(user.getId()) }` | 逐 user 查 userActionLog（2B.3d deferred） |
| 6 | `listAllReviews`（AdminApplicationServiceImpl:383） | `findAll.stream().flatMap(user -> reviewRepository.findByTargetId(user.getId()))` | 逐 user 查 review（2B.3d deferred） |
| 7 | `checkPendingReviewsAndAutoComplete`（DemandApplicationServiceImpl:414） | `for (Order : findByParticipant) { reviewRepository.findByOrderIdAndAuthorId(order.getId(), publisherId) }` | 逐 order 查 review |

### 1.3 getDashboard 冗余（2B.3d 妥协）

| # | 位置 | 冗余 |
|---|---|---|
| 8 | `getDashboard`（AdminApplicationServiceImpl:281-291） | 3 `findAll`（dailyActiveUsers + categoryDistribution 用）+ 5 `selectCount`（stats 用）并存——dailyActiveUsers 去 `findAll` 后 5 count 即唯一 stats 来源 |

## 2. 目标 / 非目标

### 目标
1. **Order loadHistory 批量化**（#1/#2）：`assembleAll` 逐条 → 批量（一次查所有 `order_id` 的 `status_log`，内存 `groupBy`）。
2. **filterCandidateDemands 跨仓储 N+1 消除**（#3）：`DemandRepository.findCandidatePage` 内部用 `NOT EXISTS` 子查询或 batch 查询排除已被接单的 demand。
3. **dailyActiveUsers 拆按日查询**（#5/#6/#8）：新建 `findActiveUserIdsByDate` 各 Repository 方法，去 `findAll` 循环；`getDashboard` 的 5 `selectCount` 即唯一 stats 来源（去冗余 `findAll`）。
4. **buildAcceptedCategoryStats batch**（#4）：一次查 user 接单的 order + 这些 order 的 demand。
5. **checkPendingReviewsAndAutoComplete batch**（#7）：一次查 user 的 COMPLETED order + 这些 order 的 review 状态。
6. 测试全绿（基线 207/207），行为零回归。

### 非目标
- 不改 API 契约（`PageResponse`/`*Response` 字段不变）。
- 不改 Domain/Entity/schema（仅 Repository 接口 + MyBatis 实现 + Service 调用）。
- 不修子项目3 视图组装层 N+1（`toNotificationResponse` 跨仓储查 title / `ApiViewMapper` 的 `findByDemandId`）。
- 不动 `findById`/`findByDemandId` 的单条 `loadHistory`（非 N+1）。
- 不动 `accept`/`updateStatus`/`submit` 等写路径的 N+1（写路径非高频）。

## 3. 修复方案

### 3.1 Order loadHistory 批量化（#1/#2）

`MyBatisOrderRepository` 新增私有 `assembleWithBatchHistory(List<OrderEntity>)`：
- 一次 `selectList(eq(order_id in ids))` 查所有 `OrderStatusLogEntity`（`orderByAsc(changedAt).orderByAsc(id)`）
- 内存 `groupBy(orderId)` → `Map<Long, List<OrderStatusHistoryEntry>>`
- 逐 entity `toDomain(historyMap.get(entity.getId()))`

`findByParticipant`/`findAll` 改调 `assembleWithBatchHistory`（替 `assembleAll`）。`findById`/`findByDemandId` 仍用单条 `loadHistory`（非 N+1，无需改）。

> 批量化把 N+1 次查询降为 2 次（selectList orders + selectList logs）。

### 3.2 filterCandidateDemands 跨仓储 N+1（#3）

**方案 A（NOT EXISTS 子查询）**：`DemandRepository.findCandidatePage` 内部 `buildCandidateWrapper` 加 `notExists` 子查询查 `ord_order`。但 demand 层查 ord_order 跨层（`DemandEntity` 不应感知 `OrderEntity`）。

**方案 B（batch 查询，推荐）**：Service `filterCandidateDemands` 先 `findCandidatePage` 取候选 `List<Demand>`，再 `orderRepository.findDemandIdsWithOrder(Collection<Long> demandIds)` 一次查有 order 的 demand_id set，内存过滤 `!demandIdsWithOrder.contains(demand.getId())`。2 次 SQL（findCandidatePage + findDemandIdsWithOrder），无 N+1。

需 `OrderRepository` 新增 `Set<Long> findDemandIdsWithOrder(Collection<Long> demandIds)`（`selectList(in(demand_id, ids))` → map getDemandId → set）。

### 3.3 dailyActiveUsers 拆按日查询（#5/#6/#8）

新建 4 个 `findActiveUserIdsByDate(LocalDate)` 方法：
- `DemandRepository.findActivePublisherIdsByDate(LocalDate)`：`selectList(eq(status, PENDING/IN_PROGRESS/COMPLETED) and (date(created_at)=today or date(updated_at)=today))` → map getPublisherId → set。或 SQL `DATE(created_at)=:today`。
- `OrderRepository.findActiveParticipantIdsByDate(LocalDate)`：`selectList(date(created_at)=today or date(updated_at)=today or date(completed_at)=today)` → map publisherId+accepterId → set。
- `ReviewRepository.findActiveAuthorIdsByDate(LocalDate)`：`selectList(date(created_at)=today)` → map getAuthorId → set。
- `UserActionLogRepository.findActiveUserIdsByDate(LocalDate)`：`selectList(date(created_at)=today)` → map getUserId → set。

`countDailyActiveUsers` 改调 4 个方法，合并 set。去 `collectDemandActivity`/`collectOrderActivity`/`collectReviewActivity`/`collectRecommendationActivity`/`listAllReviews` 的 `findAll` 循环。

`getDashboard` 去 3 `findAll`（dailyActiveUsers 用按日查询 + categoryDistribution 用 `countByCategory` SQL groupBy 或保留 demands 查询）。

> `categoryDistribution`：若去 `demands` findAll，需 `DemandRepository.countByCategory()` SQL groupBy（`selectMaps(groupBy category)`）。或保留 `demands` findAll（categoryDistribution 用）。考虑 dailyActiveUsers 去 findAll 后，categoryDistribution 仍需 demands——可选保留 demands findAll（仅 categoryDistribution）或下推 countByCategory。2C.3 评估。

### 3.4 buildAcceptedCategoryStats batch（#4）

`RecommendationApplicationServiceImpl.buildAcceptedCategoryStats`：
- `orderRepository.findByParticipant(userId)`（已 3.1 批量化 loadHistory）
- 逐 order `demandRepository.findById(order.getDemandId())` → batch：`demandRepository.findAllById(Collection<Long> demandIds)`（`selectBatchIds(ids)`）一次查所有 demand。

需 `DemandRepository` 新增 `List<Demand> findAllById(Collection<Long> ids)`（MyBatis-Plus `selectBatchIds`）。

### 3.5 checkPendingReviewsAndAutoComplete batch（#7）

`DemandApplicationServiceImpl.checkPendingReviewsAndAutoComplete`：
- `orderRepository.findByParticipant(publisherId)`（已 3.1 批量化）
- 逐 order `reviewRepository.findByOrderIdAndAuthorId(order.getId(), publisherId)` → batch：`reviewRepository.findReviewedOrderIdsByAuthor(Long authorId, Collection<Long> orderIds)` 一次查这些 order 中已 review 的 order_id set。

需 `ReviewRepository` 新增 `Set<Long> findReviewedOrderIdsByAuthor(Long authorId, Collection<Long> orderIds)`。

## 4. 子阶段分解

| 子阶段 | 范围 | 修复 # | 涉及 Repository |
|---|---|---|---|
| **2C.1** | Order loadHistory 批量化 | #1/#2 | MyBatisOrderRepository |
| **2C.2** | filterCandidateDemands 跨仓储 N+1 | #3 | OrderRepository + RecommendationApplicationServiceImpl |
| **2C.3** | dailyActiveUsers 拆按日查询 + getDashboard 去冗余 findAll | #5/#6/#8 | Demand/Order/Review/UserActionLog Repository + AdminApplicationServiceImpl |
| **2C.4** | buildAcceptedCategoryStats batch | #4 | DemandRepository + RecommendationApplicationServiceImpl |
| **2C.5** | checkPendingReviewsAndAutoComplete batch | #7 | ReviewRepository + DemandApplicationServiceImpl |

> 每子阶段独立 spec/plan/SDD/push。2C.1 是基础（loadHistory 批量化受益多个调用方）。2C.3 最复杂（4 个 findActiveUserIdsByDate + getDashboard 重构）。

## 5. 影响面（每子阶段）
- Repository 接口加方法 + MyBatis 实现（`selectBatchIds`/`selectList(in)`/`selectList(date)`）
- Service 改用新方法（删循环）
- 测试新增（batch/按日查询用例）+ 回归护栏

## 6. 验收（2C 全部完成后）
1. `mvn test` 全绿，测试数 ≥ 207 + 新增。
2. main 无 N+1（grep `assembleAll` 逐条 `loadHistory` 消除；`filterCandidateDemands` 无逐条 `findByDemandId`；`collectRecommendationActivity`/`listAllReviews` 无逐 user 循环；`getDashboard` 无冗余 `findAll`）。
3. API 契约不变。
4. `getDashboard` 的 dailyActiveUsers 走按日查询（去 `findAll`）。

## 7. 风险
| 风险 | 对策 |
|---|---|
| `NOT EXISTS` / batch 查询语义与原逐条不一致 | 每子阶段测试对照原 N+1 行为 |
| `DATE(created_at)=:today` 在 H2 与 MySQL 语法/索引差异 | H2 MySQL 模式支持 `DATE()`；索引留 2D 评估（函数索引 vs 范围查询） |
| batch `selectBatchIds`/`in` 大列表性能 | 候选集/page size 限制；`in` 列表大时分批 |
| dailyActiveUsers 拆按日查询改变行为 | 对照原内存聚合（同日活跃）测试 |

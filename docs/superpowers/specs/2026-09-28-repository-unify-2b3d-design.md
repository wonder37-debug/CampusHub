# 仓储层统一阶段 2B.3d 设计（admin getDashboard 聚合下推）

- 日期：2026-09-28
- 状态：待评审（用户已授权直接执行）
- 分支：`refactor/optimization`（HEAD `58f3fa8`，2B.3c 已完成，测试 181/181）
- 上游 spec：`docs/superpowers/specs/2026-09-28-repository-unify-2b-design.md`（2B 总体，本文件为其第 3 子阶段 d，2B.3 最后一个）
- 范围：`UserRepository`/`DemandRepository`/`OrderRepository` 各加 `count` 类方法 + `AdminApplicationServiceImpl.getDashboard` 下沉 5 `selectCount`

## 1. 背景与现状（探索已核实）

### 1.1 现状定位（2B 总体 spec #7）

`AdminApplicationServiceImpl.getDashboard`（`backend/src/main/java/com/campushub/backend/admin/service/AdminApplicationServiceImpl.java:277-306`）当前：

```java
List<User> users = userRepository.findAll();
List<Demand> demands = demandRepository.findAll();
List<Order> orders = orderRepository.findAll();
LocalDate today = LocalDate.now();

long dailyActiveUsers = countDailyActiveUsers(demands, orders, today);
long pendingReviewDemands = demandRepository.findByStatus(DemandStatus.REVIEWING).size();
long completedOrders = orders.stream().filter(order -> order.getStatus() == OrderStatus.COMPLETED).count();
Map<String, Long> categoryDistribution = demands.stream()
    .collect(Collectors.groupingBy(demand -> demand.getCategory().name(), Collectors.counting()));

List<AdminCategoryStatResponse> categoryStats = categoryDistribution.entrySet().stream()
    .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
    .map(entry -> new AdminCategoryStatResponse(entry.getKey(), entry.getValue()))
    .toList();

return new AdminDashboardResponse(
    dailyActiveUsers, users.size(), demands.size(), pendingReviewDemands,
    orders.size(), completedOrders, categoryStats);
```

即 3 个 `findAll`（users/demands/orders，orders 含逐条 `loadHistory` N+1）+ `findByStatus(REVIEWING).size()`（额外全表 status 过滤）+ 内存聚合（`dailyActiveUsers` 跨表 + N+1 循环 `userActionLogRepository.findByUserId` / `listAllReviews` N+1 / `completedOrders` filter / `categoryDistribution` groupBy）。

### 1.2 下推范围与妥协（关键设计决策）

`dailyActiveUsers` 跨 4 表（demand/order/review/userActionLog）按日聚合，含 2 处 N+1（`collectRecommendationActivity` 逐条 `findByUserId`、`listAllReviews` 逐 `findByTargetId`）。彻底下推需拆按日查询（4 个 `findActiveUserIdsByDate` 新方法），属 2C 范围。

**裁决**：2B.3d **保留 `dailyActiveUsers` 内存聚合**（`countDailyActiveUsers`/`collect*` 逻辑不动，仍调 3 `findAll` 取 demands/orders/users），**仅下推 5 `selectCount`**（替 `size()`/`filter().count()`/`findByStatus.size()`）+ `categoryDistribution` 保留内存 groupBy（从已加载 demands）。

**冗余说明**：5 `selectCount`（totalUsers/totalDemands/totalOrders/pendingReview/completedOrders）与 `findAll` 的 `size()`/`filter().count()` 并存——此为过渡妥协。2C 优化 `dailyActiveUsers`（拆按日查询去 `findAll`）后，5 `selectCount` 即为唯一 stats 来源，冗余消除。2B.3d 提前下推 5 count 是为 2C 去 `findAll` 铺路。

### 1.3 `categoryDistribution` 保留内存（不从 demands 算→从 demands 算）

`demands` 仍为 `dailyActiveUsers` 的 `collectDemandActivity` 加载。`categoryDistribution` 从已加载 `demands` 内存 `groupBy(category)` **无额外查询**，比 SQL `groupBy` 更优（省一次查询）。故 2B.3d 不新增 `countByCategory()`，保留内存 groupBy。

### 1.4 各 Repository count 方法现状

- `UserRepository`：无 `count()`。`findAll` 用 `selectList(null)`，`selectCount(null)` 可作 `count()`。
- `DemandRepository`：2B.1 已加 `count(DemandQuery)`（带过滤）。无 `countAll()`（无过滤全量）也无 `countByStatus(DemandStatus)`。
- `OrderRepository`：无 `count()` 也无 `countByStatus(OrderStatus)`。2B.3c 加了 `countArbitration()`（`eq(status,IN_ARBITRATION)`）——本质是 `countByStatus(IN_ARBITRATION)` 的特化。

### 1.5 调用方契约

`getDashboard` 调用方：`AdminController.getDashboard`（拿 `AdminDashboardResponse` 返回前端）。2B.3d 仅改 Service 内部实现，`AdminDashboardResponse`（全 long 字段 + `List<AdminCategoryStatResponse>`）/`AdminCategoryStatResponse(String, long)` 结构不变，Controller 与前端契约不动。

### 1.6 `findByStatus`/`findAll` 保留

- `demandRepository.findByStatus`（:287 现状用）：2B.3d 下推 `pendingReview` 后，main 不再调 `findByStatus`（仅 `getDashboard:287` 用）。但 `findByStatus` 接口保留（test 仍测，2B 总体非目标删接口）。
- `userRepository.findAll`/`demandRepository.findAll`/`orderRepository.findAll`：仍被 `getDashboard` 的 `dailyActiveUsers`/`categoryDistribution` 用，**保留**。2C 优化 `dailyActiveUsers` 后评估删除。

## 2. 目标 / 非目标

### 目标
1. `UserRepository` 新增 `long count()`。
2. `DemandRepository` 新增 `long countAll()` + `long countByStatus(DemandStatus status)`（`count(DemandQuery)` 已有，保留）。
3. `OrderRepository` 新增 `long count()` + `long countByStatus(OrderStatus status)`（`countArbitration()` 已有，保留——本质是 `countByStatus(IN_ARBITRATION)` 特化，2B.3d 不合并以避免改动 2B.3c 已稳定代码）。
4. `AdminApplicationServiceImpl.getDashboard` 改用 5 `selectCount`（`totalUsers`/`totalDemands`/`totalOrders`/`pendingReview`/`completedOrders`），保留 `dailyActiveUsers` 内存聚合（3 `findAll` 不动）+ `categoryDistribution` 内存 groupBy（从已加载 demands）。
5. 行为零回归：`AdminDashboardResponse`/`AdminCategoryStatResponse` 字段不变；`shouldBuildDashboardStats` 用例断言不变且全绿。

### 非目标
- 不下推 `dailyActiveUsers`（1.2，2C 拆按日查询）。
- 不下推 `categoryDistribution` 到 SQL groupBy（1.3，从已加载 demands 内存 groupBy 更优）。
- 不删 `findAll`/`findByStatus`（1.6，2C 评估）。
- 不合并 `countArbitration()` 与 `countByStatus(IN_ARBITRATION)`（2B.3c 已稳定）。
- 不改 `AdminDashboardResponse`/`AdminCategoryStatResponse`/schema/Controller。
- 不动 `listUsers`/`listPendingDemands`/`listArbitrationOrders`（2B.3a/b/c 已完成）。
- 不修 N+1（2C）。

## 3. 约束（继承 2B 总体 + 主 spec）
- 前端兼容契约：`AdminDashboardResponse(全 long + List<AdminCategoryStatResponse>)`/`AdminCategoryStatResponse(String, long)` 字段结构不变；`AdminController` 转换不变；API 行为不变。
- 测试全绿：基线 181/181（2B.3c 后），每子阶段不回归。
- 工作分支 `refactor/optimization`。
- Repository 接口扩方法，各 MyBatisRepository 实现；无 XML mapper，全 `BaseMapper` + `LambdaQueryWrapper`。
- H2 MySQL 兼容模式（测试）与 MySQL（生产）行为一致。

## 4. 设计

### 4.1 Repository 接口扩展（5 count 方法）

`UserRepository.java` 追加：

```java
    /**
     * 统计用户总数（下推 SQL selectCount，用于 dashboard stats）。
     */
    long count();
```

`DemandRepository.java` 追加（`count(DemandQuery)` 已有，保留）：

```java
    /**
     * 统计需求总数（下推 SQL selectCount，无过滤，用于 dashboard stats）。
     */
    long countAll();

    /**
     * 按状态统计需求数（下推 SQL selectCount，用于 dashboard stats）。
     */
    long countByStatus(DemandStatus status);
```

`OrderRepository.java` 追加（`countArbitration()` 已有，保留）：

```java
    /**
     * 统计订单总数（下推 SQL selectCount，无过滤，用于 dashboard stats）。
     */
    long count();

    /**
     * 按状态统计订单数（下推 SQL selectCount，用于 dashboard stats）。
     */
    long countByStatus(OrderStatus status);
```

> `DemandRepository` import `DemandStatus` 已有（2B.1）。`OrderRepository` import `OrderStatus` 需新增（2B.3c 已 import 到 MyBatisOrderRepository，接口层需确认）。

### 4.2 MyBatisRepository 实现

`MyBatisUserRepository` 追加：

```java
    @Override
    public long count() {
        return userMapper.selectCount(null);
    }
```

`MyBatisDemandRepository` 追加（`count(DemandQuery)` 已有）：

```java
    @Override
    public long countAll() {
        return demandMapper.selectCount(null);
    }

    @Override
    public long countByStatus(DemandStatus status) {
        if (status == null) {
            return 0L;
        }
        return demandMapper.selectCount(new LambdaQueryWrapper<DemandEntity>()
            .eq(DemandEntity::getStatus, status.name()));
    }
```

`MyBatisOrderRepository` 追加（`countArbitration()` 已有，保留）：

```java
    @Override
    public long count() {
        return orderMapper.selectCount(null);
    }

    @Override
    public long countByStatus(OrderStatus status) {
        if (status == null) {
            return 0L;
        }
        return orderMapper.selectCount(new LambdaQueryWrapper<OrderEntity>()
            .eq(OrderEntity::getStatus, status.name()));
    }
```

> `DemandStatus`/`LambdaQueryWrapper`/`DemandEntity` 在 MyBatisDemandRepository 已 import（2B.1）。`OrderStatus`/`LambdaQueryWrapper`/`OrderEntity` 在 MyBatisOrderRepository 已 import（2B.3c）。`selectCount` 返回 `Long`，自动拆箱为 `long`（MyBatis-Plus 对 COUNT 查询返回非 null，至少 0）。

### 4.3 过滤下沉映射

| # | 内存逻辑（现状） | SQL 下推 | 边界对齐 |
|---|---|---|---|
| 1 | `users.size()` | `userRepository.count()` = `selectCount(null)` | 全表 COUNT(*) |
| 2 | `demands.size()` | `demandRepository.countAll()` = `selectCount(null)` | 全表 COUNT(*) |
| 3 | `orders.size()` | `orderRepository.count()` = `selectCount(null)` | 全表 COUNT(*) |
| 4 | `demandRepository.findByStatus(REVIEWING).size()` | `demandRepository.countByStatus(REVIEWING)` = `selectCount(eq(status,'REVIEWING'))` | 替 `findByStatus` 全量加载 + `.size()` |
| 5 | `orders.stream().filter(o -> o.getStatus()==COMPLETED).count()` | `orderRepository.countByStatus(COMPLETED)` = `selectCount(eq(status,'COMPLETED'))` | 替内存 filter（orders 仍 findAll for dailyActiveUsers，但 completedOrders 用独立 selectCount） |

### 4.4 `categoryDistribution` 保留内存

```java
Map<String, Long> categoryDistribution = demands.stream()
    .collect(Collectors.groupingBy(demand -> demand.getCategory().name(), Collectors.counting()));
```

`demands` 仍为 `dailyActiveUsers` 的 `collectDemandActivity` 加载（`findAll`）。从已加载 `demands` 内存 `groupBy(category)` 无额外查询，比 SQL `groupBy` 更优。保留不动。

### 4.5 `dailyActiveUsers` 保留内存

`countDailyActiveUsers(demands, orders, today)` / `collectDemandActivity` / `collectOrderActivity` / `collectReviewActivity` / `collectRecommendationActivity` / `listAllReviews` / `isSameDate` 全部保留不动（N+1 留 2C）。仍调 3 `findAll`（users/demands/orders）。

### 4.6 Service 改造

`AdminApplicationServiceImpl.getDashboard` 改为：

```java
@Override
public AdminDashboardResponse getDashboard(Long operatorId) {
    requireAdmin(operatorId);

    List<User> users = userRepository.findAll();
    List<Demand> demands = demandRepository.findAll();
    List<Order> orders = orderRepository.findAll();
    LocalDate today = LocalDate.now();

    long dailyActiveUsers = countDailyActiveUsers(demands, orders, today);
    long totalUsers = userRepository.count();
    long totalDemands = demandRepository.countAll();
    long totalOrders = orderRepository.count();
    long pendingReviewDemands = demandRepository.countByStatus(DemandStatus.REVIEWING);
    long completedOrders = orderRepository.countByStatus(OrderStatus.COMPLETED);
    Map<String, Long> categoryDistribution = demands.stream()
        .collect(Collectors.groupingBy(demand -> demand.getCategory().name(), Collectors.counting()));

    List<AdminCategoryStatResponse> categoryStats = categoryDistribution.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
        .map(entry -> new AdminCategoryStatResponse(entry.getKey(), entry.getValue()))
        .toList();

    return new AdminDashboardResponse(
        dailyActiveUsers, totalUsers, totalDemands, pendingReviewDemands,
        totalOrders, completedOrders, categoryStats);
}
```

> 3 `findAll` 保留（`dailyActiveUsers` + `categoryDistribution` 用）。5 `selectCount` 替 `size()`/`filter().count()`/`findByStatus.size()`。`pendingReviewDemands` 变量名保留（`AdminDashboardResponse` 字段名 `pendingReviewDemands`）。

### 4.7 import 清理

无新增/删除 import。`DemandStatus`/`OrderStatus`/`User`/`Demand`/`Order`/`Collectors`/`Map`/`List`/`LocalDate` 均已 import（现有 getDashboard 用）。

### 4.8 并发一致性

5 `selectCount` 与 3 `findAll` 各自独立 SQL，有并发窗口（如某 demand 在 `findAll` 后、`countAll` 前被删，`countAll` 少 1）。原 `findAll().size()` 是单次 SQL 内一致。2B.3d 接受此妥协（dashboard 是统计快照，非事务一致需求）；2C 若需严格一致再加 `@Transactional(readOnly=true)`。

## 5. 影响面

### 改动文件
| 文件 | 操作 |
|---|---|
| `auth/repository/UserRepository.java` | 加 `count()` |
| `auth/repository/MyBatisUserRepository.java` | 实现 `count()` |
| `demand/repository/DemandRepository.java` | 加 `countAll()` + `countByStatus(DemandStatus)` |
| `demand/repository/MyBatisDemandRepository.java` | 实现 `countAll()` + `countByStatus(DemandStatus)` |
| `order/repository/OrderRepository.java` | 加 `count()` + `countByStatus(OrderStatus)` + import（OrderStatus） |
| `order/repository/MyBatisOrderRepository.java` | 实现 `count()` + `countByStatus(OrderStatus)` |
| `admin/service/AdminApplicationServiceImpl.java` | `getDashboard` 改用 5 selectCount |

### 测试文件
| 文件 | 操作 |
|---|---|
| `auth/repository/MyBatisUserRepositoryTest.java` | **新增** `count()` 用例 |
| `demand/repository/MyBatisDemandRepositoryTest.java` | **新增** `countAll()`/`countByStatus()` 用例 |
| `order/repository/MyBatisOrderRepositoryTest.java` | **新增** `count()`/`countByStatus()` 用例 |
| `admin/service/AdminApplicationServiceImplTest.java` | **不改**（`shouldBuildDashboardStats` 作回归护栏） |

### 不受影响
- `AdminDashboardResponse`/`AdminCategoryStatResponse`/schema/Controller/前端契约
- `dailyActiveUsers` 的 `collect*`/`listAllReviews`/`isSameDate` 方法（保留）
- `listUsers`/`listPendingDemands`/`listArbitrationOrders`（2B.3a/b/c）
- `countArbitration()`（2B.3c，保留不合并）
- `findAll`/`findByStatus`/`findByParticipant`（保留）

## 6. 测试策略

### 6.1 回归护栏（不改，须全绿）
- `AdminApplicationServiceImplTest.shouldBuildDashboardStats`：setUp 3 user（admin/publisher/accepter）+ 插入 1 inactive user + `createDemand("待审核咨询", STUDY_TUTORING)` + `createDemand("已完成快递", EXPRESS)`→makePending + `accept`→`updateStatus(IN_PROGRESS)`→`updateStatus(COMPLETED)`×2 → `getDashboard(adminId)` 断言 `totalUsers=4`/`totalDemands=2`/`pendingReviewDemands=1`/`totalOrders=1`/`completedOrders=1`/`dailyActiveUsers=2`。下推后 5 `selectCount` + 内存 `dailyActiveUsers`/`categoryDistribution` 等价。

### 6.2 新增 Repository count 用例

`MyBatisUserRepositoryTest` 新增：
1. `count_returns_zero_when_empty`：空表 `count()==0`。
2. `count_returns_total_after_inserts`：插入 3 user → `count()==3`。

`MyBatisDemandRepositoryTest` 新增：
3. `countAll_returns_zero_when_empty` / `countAll_returns_total`。
4. `countByStatus_counts_matching_status`（插入 REVIEWING+PENDING+COMPLETED，`countByStatus(REVIEWING)==1`）。
5. `countByStatus_null_returns_zero`（防御）。

`MyBatisOrderRepositoryTest` 新增：
6. `count_returns_zero_when_empty` / `count_returns_total`。
7. `countByStatus_counts_matching_status`（插入 IN_ARBITRATION+COMPLETED+ACCEPTED，`countByStatus(COMPLETED)==1`）。
8. `countByStatus_null_returns_zero`（防御）。

## 7. 验收标准
1. `.\mvnw.cmd test`（`JAVA_HOME` 指向 JDK 21，workdir=backend）全绿，测试数 ≥ 181 + 新增（约 8 个 count 用例）。
2. `getDashboard` 不再出现 `findByStatus(DemandStatus.REVIEWING).size()`、`orders.stream().filter(...COMPLETED).count()`、`users.size()`、`demands.size()`、`orders.size()`（5 处替 selectCount）。
3. `UserRepository` 暴露 `count()`；`DemandRepository` 暴露 `countAll()`+`countByStatus(DemandStatus)`；`OrderRepository` 暴露 `count()`+`countByStatus(OrderStatus)`。
4. `AdminDashboardResponse`/`AdminCategoryStatResponse` 字段不变；`shouldBuildDashboardStats` 断言不变且全绿。
5. `dailyActiveUsers`/`categoryDistribution` 行为不变（保留内存）。
6. `countArbitration()` 保留（2B.3c，不合并）。
7. Controller 及前端契约未触动。

## 8. 风险与对策
| 风险 | 对策 |
|---|---|
| 5 `selectCount` 与 3 `findAll` 并发窗口致 stats 与 dailyActiveUsers 数据不一致 | dashboard 是统计快照，非事务一致需求（4.8）；2C 若需严格一致加 `@Transactional(readOnly=true)` |
| 5 count 与 findAll size() 冗余（过渡） | 2C 优化 dailyActiveUsers 去 findAll 后冗余消除（1.2）；2B.3d 提前下推为 2C 铺路 |
| `selectCount` 返回 `Long` 拆箱 NPE | MyBatis-Plus `selectCount` 对 COUNT 查询返回非 null（至少 0）；`countByStatus(null)` 入口守卫返回 0 |
| `dailyActiveUsers` 的 N+1（collectRecommendationActivity/listAllReviews）未修 | 非目标（2C）；2B.3d 保留内存聚合 |
| `countByStatus` 与 `countArbitration` 重复（后者是前者特化） | 2B.3c 已稳定，不合并避免改动风险（2.非目标） |

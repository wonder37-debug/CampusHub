# 仓储层统一 2B.3d（admin getDashboard 聚合下推）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `AdminApplicationServiceImpl.getDashboard` 的 `findByStatus(REVIEWING).size()` / `orders.filter(COMPLETED).count()` / `users.size()` / `demands.size()` / `orders.size()` 5 处内存计数下沉为 `UserRepository.count()` / `DemandRepository.countAll()+countByStatus()` / `OrderRepository.count()+countByStatus()` 的 SQL `selectCount`，`dailyActiveUsers` 保留内存聚合（N+1 留 2C），`categoryDistribution` 保留内存 groupBy（从已加载 demands），行为零回归。

**Architecture:** 3 个 Repository 各加 count 方法（`UserRepository.count()` / `DemandRepository.countAll()+countByStatus(DemandStatus)` / `OrderRepository.count()+countByStatus(OrderStatus)`），各 MyBatisRepository 用 `selectCount(null)`/`selectCount(eq status)` 实现；Service `getDashboard` 改用 5 selectCount，保留 3 findAll（dailyActiveUsers + categoryDistribution 用）+ dailyActiveUsers 内存聚合 + categoryDistribution 内存 groupBy。

**Tech Stack:** Java 21 / Spring Boot 3.5.0 / MyBatis-Plus 3.5.7（`BaseMapper` + `LambdaQueryWrapper`，无 XML mapper）/ H2 MySQL 兼容模式（测试）/ MySQL（生产）/ JUnit 5 + AssertJ。

**Spec:** `docs/superpowers/specs/2026-09-28-repository-unify-2b3d-design.md`（设计依据，与本 plan 一同阅读）

## Global Constraints

- **JDK 21**：本机默认 JDK 17，跑 Maven 前必须先设 `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"`（单条命令），再单独跑 `.\mvnw.cmd test`（workdir=`D:\workspace\sec-ii-2026\backend`）。
- **PowerShell 7+**：bash 工具禁止管道 `|`、分号 `;`、`&&`、`&`、重定向 `>`、反引号、`$()`；每条命令单独执行。
- **Maven Wrapper**：用 `.\mvnw.cmd`（非 `./mvnw`），workdir=`D:\workspace\sec-ii-2026\backend`。
- **前端契约不变**：`AdminDashboardResponse(全 long + List<AdminCategoryStatResponse>)` / `AdminCategoryStatResponse(String, long)` 字段结构不变；API 行为不变。
- **测试基线 181/181 不回归**（2B.3c 后）：每 task 结束跑全量验证。
- **分支 `refactor/optimization`**：仅 SDD 的 commit step 可执行；push 到 PR #10 延续授权模式；不 merge。
- **无 XML mapper**：全部 `BaseMapper` + `LambdaQueryWrapper`。
- **H2 MySQL 兼容模式**（测试）与 MySQL（生产）行为一致。

## File Structure

| 文件 | 责任 | 本 plan 操作 |
|---|---|---|
| `auth/repository/UserRepository.java` | 仓储接口 | 加 `count()` |
| `auth/repository/MyBatisUserRepository.java` | MyBatis 实现 | 实现 `count()` |
| `demand/repository/DemandRepository.java` | 仓储接口 | 加 `countAll()` + `countByStatus(DemandStatus)` |
| `demand/repository/MyBatisDemandRepository.java` | MyBatis 实现 | 实现 `countAll()` + `countByStatus(DemandStatus)` |
| `order/repository/OrderRepository.java` | 仓储接口 | 加 `count()` + `countByStatus(OrderStatus)` + import（OrderStatus） |
| `order/repository/MyBatisOrderRepository.java` | MyBatis 实现 | 实现 `count()` + `countByStatus(OrderStatus)` |
| `admin/service/AdminApplicationServiceImpl.java` | 应用服务 | `getDashboard` 改用 5 selectCount |
| `auth/repository/MyBatisUserRepositoryTest.java` | 切片测试 | 新增 2 个 `count()` 用例 |
| `demand/repository/MyBatisDemandRepositoryTest.java` | 切片测试 | 新增 3 个 `countAll`/`countByStatus` 用例 |
| `order/repository/MyBatisOrderRepositoryTest.java` | 切片测试 | 新增 3 个 `count`/`countByStatus` 用例 |

---

### Task 1: 3 Repository 加 count 方法 + MyBatis 实现 + 测试

**Files:**
- Modify: `backend/src/main/java/com/campushub/backend/auth/repository/UserRepository.java` + `MyBatisUserRepository.java`
- Modify: `backend/src/main/java/com/campushub/backend/demand/repository/DemandRepository.java` + `MyBatisDemandRepository.java`
- Modify: `backend/src/main/java/com/campushub/backend/order/repository/OrderRepository.java` + `MyBatisOrderRepository.java`
- Test: `MyBatisUserRepositoryTest.java` / `MyBatisDemandRepositoryTest.java` / `MyBatisOrderRepositoryTest.java`

**Interfaces:**
- Consumes: `DemandStatus`（`demand.domain` 枚举，`.name()` 全大写）、`OrderStatus`（`order.domain` 枚举，`.name()` 全大写）、`LambdaQueryWrapper`/各 Entity/Mapper（已 import）。
- Produces: `UserRepository.count() -> long`、`DemandRepository.countAll()/countByStatus(DemandStatus) -> long`、`OrderRepository.count()/countByStatus(OrderStatus) -> long`（供 Task 2 Service 调用）。

- [ ] **Step 1: 写失败测试（8 个 count 用例，跨 3 文件）**

**`MyBatisUserRepositoryTest.java`** 末尾（`newUser` 工厂之前）追加：

```java
    @Test
    void count_returns_zero_when_empty() {
        assertThat(repository.count()).isZero();
    }

    @Test
    void count_returns_total_after_inserts() {
        repository.save(newUser("a@campus.edu", "2026001"));
        repository.save(newUser("b@campus.edu", "2026002"));
        repository.save(newUser("c@campus.edu", "2026003"));

        assertThat(repository.count()).isEqualTo(3L);
    }
```

**`MyBatisDemandRepositoryTest.java`** 末尾（工厂之前）追加：

```java
    @Test
    void countAll_returns_zero_when_empty() {
        assertThat(repository.countAll()).isZero();
    }

    @Test
    void countAll_returns_total_after_inserts() {
        repository.save(newDemand("d1", DemandCategory.OTHER));
        repository.save(newDemand("d2", DemandCategory.EXPRESS));

        assertThat(repository.countAll()).isEqualTo(2L);
    }

    @Test
    void countByStatus_counts_matching_status_and_handles_null() {
        Demand reviewing = repository.save(newDemand("r", DemandCategory.OTHER));
        reviewing.setStatus(DemandStatus.REVIEWING);
        repository.save(reviewing);
        repository.save(newDemand("p", DemandCategory.OTHER)); // 默认 PENDING（见 2B.3b 工厂）

        assertThat(repository.countByStatus(DemandStatus.REVIEWING)).isEqualTo(1L);
        assertThat(repository.countByStatus(DemandStatus.PENDING)).isEqualTo(1L);
        assertThat(repository.countByStatus(null)).isZero();
    }
```

> `newDemand` 默认 `status=PENDING`（2B.3b 已确认）。`DemandStatus`/`DemandCategory` 已 import。

**`MyBatisOrderRepositoryTest.java`** 末尾（`newOrder` 工厂之前）追加：

```java
    @Test
    void count_returns_zero_when_empty() {
        assertThat(repository.count()).isZero();
    }

    @Test
    void count_returns_total_after_inserts() {
        repository.save(newOrder(6001L, 10L, 20L));
        repository.save(newOrder(6002L, 11L, 21L));

        assertThat(repository.count()).isEqualTo(2L);
    }

    @Test
    void countByStatus_counts_matching_status_and_handles_null() {
        Order arbitration = repository.save(newOrder(6101L, 10L, 20L));
        arbitration.setStatus(OrderStatus.IN_ARBITRATION);
        repository.save(arbitration);
        repository.save(newOrder(6102L, 11L, 21L)); // 默认 ACCEPTED

        assertThat(repository.countByStatus(OrderStatus.IN_ARBITRATION)).isEqualTo(1L);
        assertThat(repository.countByStatus(OrderStatus.ACCEPTED)).isEqualTo(1L);
        assertThat(repository.countByStatus(null)).isZero();
    }
```

> `OrderStatus` 已 import（:5）。`newOrder` 默认 `status=ACCEPTED`。

- [ ] **Step 2: 跑测试确认失败（编译错）**

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```

```
.\mvnw.cmd test -Dtest=MyBatisUserRepositoryTest,MyBatisDemandRepositoryTest,MyBatisOrderRepositoryTest
```

Expected: 编译失败，3 Repository 无 `count`/`countAll`/`countByStatus` 方法。

- [ ] **Step 3: 加 3 Repository 接口方法**

`UserRepository.java` 在 `save` 之后追加：

```java
    /**
     * 统计用户总数（下推 SQL selectCount，用于 dashboard stats）。
     */
    long count();
```

`DemandRepository.java` 在 `countReview` 之后追加（`count(DemandQuery)` 已有，保留）：

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

> `DemandStatus` 已 import（:4）。

`OrderRepository.java` 在 `countArbitration` 之后追加（`countArbitration()` 已有，保留）+ import：

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

在 `OrderRepository.java` import 区追加（保持字母序）：

```java
import com.campushub.backend.order.domain.OrderStatus;
```

- [ ] **Step 4: 实现 3 MyBatisRepository 的 count 方法**

`MyBatisUserRepository.java` 在 `save` 之后追加：

```java
    @Override
    public long count() {
        return userMapper.selectCount(null);
    }
```

`MyBatisDemandRepository.java` 在 `buildReviewWrapper` 之后追加（`count(DemandQuery)` 已有）：

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

> `LambdaQueryWrapper`/`DemandEntity`/`DemandStatus` 已 import（2B.1）。

`MyBatisOrderRepository.java` 在 `countArbitration` 之后追加（`countArbitration()` 已有，保留）：

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

> `LambdaQueryWrapper`/`OrderEntity`/`OrderStatus` 已 import（2B.3c Task 1 加 OrderStatus）。

- [ ] **Step 5: 跑 3 Repository 测试确认通过**

```
.\mvnw.cmd test -Dtest=MyBatisUserRepositoryTest,MyBatisDemandRepositoryTest,MyBatisOrderRepositoryTest
```

Expected: PASS，3 测试类全绿（含新增 8 用例）。

- [ ] **Step 6: 跑全量测试确认无回归**

```
.\mvnw.cmd test
```

Expected: 全绿，测试数 ≥ 181 + 8（新增）= 189。`AdminApplicationServiceImplTest` 此刻仍用旧 `getDashboard`（调 `findAll`/`size()`），全量绿。

> 若输出超 2000 行被截断写到 tool-output 文件，用 Grep 工具搜该文件确认无 `BUILD FAILURE`/`<<< FAILURE!`/`<<< ERROR!`，并搜 `Tests run:` 确认总数。

- [ ] **Step 7: Commit**

workdir=`D:\workspace\sec-ii-2026`：

```
git add backend/src/main/java/com/campushub/backend/auth/repository/UserRepository.java backend/src/main/java/com/campushub/backend/auth/repository/MyBatisUserRepository.java backend/src/main/java/com/campushub/backend/demand/repository/DemandRepository.java backend/src/main/java/com/campushub/backend/demand/repository/MyBatisDemandRepository.java backend/src/main/java/com/campushub/backend/order/repository/OrderRepository.java backend/src/main/java/com/campushub/backend/order/repository/MyBatisOrderRepository.java backend/src/test/java/com/campushub/backend/auth/repository/MyBatisUserRepositoryTest.java backend/src/test/java/com/campushub/backend/demand/repository/MyBatisDemandRepositoryTest.java backend/src/test/java/com/campushub/backend/order/repository/MyBatisOrderRepositoryTest.java
```

```
git commit -m "refactor(repository): add count/countAll/countByStatus to User/Demand/Order repositories for dashboard stats"
```

---

### Task 2: AdminApplicationServiceImpl.getDashboard 改用 5 selectCount

**Files:**
- Modify: `backend/src/main/java/com/campushub/backend/admin/service/AdminApplicationServiceImpl.java:277-306`（`getDashboard` 方法体）
- Test: `backend/src/test/java/com/campushub/backend/admin/service/AdminApplicationServiceImplTest.java`（不改，作回归护栏）

**Interfaces:**
- Consumes: Task 1 的 `UserRepository.count()` + `DemandRepository.countAll()`/`countByStatus(DemandStatus)` + `OrderRepository.count()`/`countByStatus(OrderStatus)`。
- Produces: `AdminApplicationService.getDashboard(Long operatorId) -> AdminDashboardResponse`（签名不变，行为等价但 5 count 走 SQL）。

- [ ] **Step 1: 改 getDashboard 方法体**

替换 `AdminApplicationServiceImpl.java:277-306` 的 `getDashboard` 方法为：

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

> 3 `findAll` 保留（`dailyActiveUsers` + `categoryDistribution` 用）。5 `selectCount` 替 `size()`/`filter().count()`/`findByStatus.size()`。`countDailyActiveUsers`/`collect*`/`listAllReviews`/`isSameDate` 全部保留不动。`DemandStatus`/`OrderStatus` 已 import。

- [ ] **Step 2: 跑 Service 测试确认 getDashboard 回归绿**

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```

```
.\mvnw.cmd test -Dtest=AdminApplicationServiceImplTest
```

Expected: PASS，含 `shouldBuildDashboardStats`（断言 `totalUsers=4`/`totalDemands=2`/`pendingReviewDemands=1`/`totalOrders=1`/`completedOrders=1`/`dailyActiveUsers=2`）全绿。

- [ ] **Step 3: 跑全量测试确认不回归**

```
.\mvnw.cmd test
```

Expected: 全绿，测试数 = Task 1 Step 6 的数量（本 task 不新增测试，仅重构）。

- [ ] **Step 4: Commit**

workdir=`D:\workspace\sec-ii-2026`：

```
git add backend/src/main/java/com/campushub/backend/admin/service/AdminApplicationServiceImpl.java
```

```
git commit -m "refactor(admin): switch AdminApplicationServiceImpl.getDashboard to SQL-pushed selectCount for 5 stats (dailyActiveUsers/categoryDistribution kept in-memory)"
```

---

## Self-Review

**1. Spec 覆盖**
- §4.1 接口扩展（UserRepository.count / DemandRepository.countAll+countByStatus / OrderRepository.count+countByStatus）→ Task 1 Step 3。✓
- §4.2 MyBatisRepository 实现（selectCount(null)/selectCount(eq status)）→ Task 1 Step 4。✓
- §4.3 过滤下沉映射（5 count）→ Task 1 Step 4 + Task 2 Step 1。✓
- §4.4 categoryDistribution 保留内存 → Task 2 Step 1 `demands.stream().groupBy`。✓
- §4.5 dailyActiveUsers 保留内存 → Task 2 Step 1 不动 collect*。✓
- §4.6 Service 改造 → Task 2 Step 1。✓
- §4.7 import 清理（无新增/删除）→ Task 2 Step 1 注释。✓
- §4.8 并发一致性 → spec 已记妥协。✓
- §5 影响面文件清单 → File Structure 表。✓
- §6 测试策略（回归护栏 1 + 新增 8）→ Task 1 Step 1（8 新增）+ Task 2 Step 2（1 护栏）。✓
- §7 验收（mvn 全绿 / getDashboard 不再 findByStatus.size/filter.count/size / 5 count 暴露 / 契约不变 / dailyActiveUsers+categoryDistribution 保留 / countArbitration 保留）→ Task 1 Step 5-6 + Task 2 Step 2-3。✓
- §8 风险（并发窗口 / 冗余过渡 / selectCount NPE / N+1 留2C / countByStatus vs countArbitration）→ spec 已记。✓

**2. 占位符扫描**：无 TBD/TODO；所有代码块为可直接落地实现。✓

**3. 类型一致性**：`UserRepository.count() -> long` / `DemandRepository.countAll()/countByStatus(DemandStatus) -> long` / `OrderRepository.count()/countByStatus(OrderStatus) -> long` 在 Task 1（接口+实现）与 Task 2（`userRepository.count()` + `demandRepository.countAll()/countByStatus(REVIEWING)` + `orderRepository.count()/countByStatus(COMPLETED)`）签名一致；`DemandStatus`/`OrderStatus` enum `.name()` 全大写存库，与 `eq(status, ...)` 一致；`AdminDashboardResponse(全 long + List<AdminCategoryStatResponse>)` 字段类型与 `new AdminDashboardResponse(dailyActiveUsers, totalUsers, totalDemands, pendingReviewDemands, totalOrders, completedOrders, categoryStats)` 一致。✓

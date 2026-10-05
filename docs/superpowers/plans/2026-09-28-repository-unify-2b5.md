# 仓储层统一 2B.5（order listHistory SQL 下推）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `OrderApplicationServiceImpl.listHistory` 的 `findByParticipant`（含逐条 `loadHistory` N+1）+ 内存 `createdAt DESC` 排序 + `subList` 分页下沉为 `OrderRepository.findHistoryPage` / `countHistory` 的单次 SQL（`LambdaQueryWrapper` `eq(publisher_id) OR eq(accepter_id)` + `orderByDesc` + `LIMIT/OFFSET` + `selectCount`），且 `findHistoryPage` 不调 `loadHistory`（用 `toDomain(Collections.emptyList())`，因 `OrderSummaryResponse.from` 不依赖 history），行为零回归。

**Architecture:** `OrderRepository` 新增 `findHistoryPage(Long userId, OrderHistoryQuery query)` + `countHistory(Long userId)`（风格 A，与 2B.4 一致）；`MyBatisOrderRepository` 提取私有 `buildHistoryWrapper(userId)`（`eq(publisher_id) OR eq(accepter_id)`，供共用）；`findHistoryPage` 固定 `orderByDesc(createdAt).orderByDesc(id)` + `LIMIT/OFFSET`，不调 `loadHistory`；Service `listHistory` 改调新方法，删连带失效的 `Comparator` import。

**Tech Stack:** Java 21 / Spring Boot 3.5.0 / MyBatis-Plus 3.5.7（`BaseMapper` + `LambdaQueryWrapper`，无 XML mapper）/ H2 MySQL 兼容模式（测试）/ MySQL（生产）/ JUnit 5 + AssertJ。

**Spec:** `docs/superpowers/specs/2026-09-28-repository-unify-2b5-design.md`（设计依据，与本 plan 一同阅读）

## Global Constraints

- **JDK 21**：本机默认 JDK 17，跑 Maven 前必须先设 `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"`（单条命令），再单独跑 `.\mvnw.cmd test`（workdir=`D:\workspace\sec-ii-2026\backend`）。
- **PowerShell 7+**：bash 工具禁止管道 `|`、分号 `;`、`&&`、`&`、重定向 `>`、反引号、`$()`；每条命令单独执行。
- **Maven Wrapper**：用 `.\mvnw.cmd`（非 `./mvnw`），workdir=`D:\workspace\sec-ii-2026\backend`。
- **前端契约不变**：`PageResponse(items, page, size, total)` / `OrderSummaryResponse` 字段结构不变；`listHistory` 签名不变；API 行为不变。
- **测试基线 194/194 不回归**（2B.4 后）：每 task 结束跑全量验证。
- **分支 `refactor/optimization`**：仅 SDD 的 commit step 可执行；push 到 PR #10 延续授权模式；不 merge。
- **无 XML mapper**：全部 `BaseMapper` + `LambdaQueryWrapper`。
- **H2 MySQL 兼容模式**（测试）与 MySQL（生产）行为一致。

## File Structure

| 文件 | 责任 | 本 plan 操作 |
|---|---|---|
| `order/repository/OrderRepository.java` | 仓储接口 | 加 `findHistoryPage(Long, OrderHistoryQuery)` + `countHistory(Long)` + import |
| `order/repository/MyBatisOrderRepository.java` | MyBatis 实现 | 实现 `findHistoryPage` + `countHistory` + 私有 `buildHistoryWrapper` + import |
| `order/service/OrderApplicationServiceImpl.java` | 应用服务 | `listHistory` 改用新方法；删 `Comparator` import |
| `order/repository/MyBatisOrderRepositoryTest.java` | 切片测试 | 新增 6 个 `findHistoryPage`/`countHistory` 用例 |

---

### Task 1: OrderRepository 扩展 findHistoryPage/countHistory + MyBatisOrderRepository 实现

**Files:**
- Modify: `backend/src/main/java/com/campushub/backend/order/repository/OrderRepository.java`
- Modify: `backend/src/main/java/com/campushub/backend/order/repository/MyBatisOrderRepository.java`
- Test: `backend/src/test/java/com/campushub/backend/order/repository/MyBatisOrderRepositoryTest.java`

**Interfaces:**
- Consumes: `OrderHistoryQuery`（`order.dto`，record `(PageQuery pageQuery)`，紧凑构造器 `pageQuery` null 兜底）、`PageQuery`、`OrderEntity`（字段 `publisherId`(Long, 列 `publisher_id`)/`accepterId`(Long, 列 `accepter_id`)/`createdAt`(LocalDateTime, 列 `created_at`)/`id`(Long)）、`OrderMapper`/`Collections`（已 import :11）。
- Produces: `OrderRepository.findHistoryPage(Long userId, OrderHistoryQuery) -> List<Order>`（不调 loadHistory）、`OrderRepository.countHistory(Long userId) -> long`（供 Task 2 Service 调用）。

- [ ] **Step 1: 写失败测试（6 个 findHistoryPage/countHistory 用例）**

在 `MyBatisOrderRepositoryTest.java` 类体内末尾（`newOrder` 工厂之前）追加测试方法。

> 沿用现有 `newOrder(demandId, publisherId, accepterId)` 工厂（默认 status=ACCEPTED/createdAt=now）。造不同 createdAt 需 save 后 `setCreatedAt` + 二次 `save`。

测试方法（追加到类体内）：

```java
    @Test
    void findHistoryPage_returns_orders_where_publisher_or_accepter_matches() {
        Order asPublisher = repository.save(newOrder(7001L, 10L, 20L)); // publisherId=10 命中
        Order asAccepter = repository.save(newOrder(7002L, 11L, 10L));  // accepterId=10 命中
        repository.save(newOrder(7003L, 12L, 22L)); // 都不命中

        List<Order> page = repository.findHistoryPage(10L, new OrderHistoryQuery(new PageQuery(1, 20)));

        assertThat(page).hasSize(2);
        assertThat(page).extracting(Order::getDemandId).containsExactlyInAnyOrder(7001L, 7002L);
        assertThat(repository.countHistory(10L)).isEqualTo(2L);
    }

    @Test
    void findHistoryPage_sorts_by_created_at_desc_then_id_desc() {
        Order older = repository.save(newOrder(7101L, 10L, 20L));
        older.setCreatedAt(LocalDateTime.of(2026, 9, 1, 10, 0));
        repository.save(older);
        Order newer = repository.save(newOrder(7102L, 10L, 21L));
        newer.setCreatedAt(LocalDateTime.of(2026, 9, 2, 10, 0));
        repository.save(newer);
        Order sameInstant = repository.save(newOrder(7103L, 10L, 22L));
        sameInstant.setCreatedAt(newer.getCreatedAt());
        repository.save(sameInstant);

        List<Order> page = repository.findHistoryPage(10L, new OrderHistoryQuery(new PageQuery(1, 20)));

        assertThat(page).hasSize(3);
        assertThat(page.get(0).getId()).isEqualTo(sameInstant.getId());
        assertThat(page.get(1).getId()).isEqualTo(newer.getId());
        assertThat(page.get(2).getId()).isEqualTo(older.getId());
    }

    @Test
    void findHistoryPage_applies_limit_and_offset_for_pagination() {
        for (int i = 0; i < 5; i++) {
            Order o = repository.save(newOrder(7200L + i, 10L, 20L));
            o.setCreatedAt(LocalDateTime.now().minusMinutes(5 - i));
            repository.save(o);
        }

        assertThat(repository.findHistoryPage(10L, new OrderHistoryQuery(new PageQuery(1, 2)))).hasSize(2);
        assertThat(repository.findHistoryPage(10L, new OrderHistoryQuery(new PageQuery(2, 2)))).hasSize(2);
        assertThat(repository.findHistoryPage(10L, new OrderHistoryQuery(new PageQuery(3, 2)))).hasSize(1);
        assertThat(repository.countHistory(10L)).isEqualTo(5L);
    }

    @Test
    void findHistoryPage_returns_orders_with_empty_status_history() {
        Order order = repository.save(newOrder(7301L, 10L, 20L));
        order.addHistory(null, OrderStatus.ACCEPTED, 20L, "接单", LocalDateTime.now());
        repository.save(order);

        List<Order> page = repository.findHistoryPage(10L, new OrderHistoryQuery(new PageQuery(1, 20)));

        assertThat(page).hasSize(1);
        assertThat(page.get(0).getStatusHistory()).isNotNull().isEmpty();
    }

    @Test
    void countHistory_matches_findHistoryPage_total() {
        repository.save(newOrder(7401L, 10L, 20L));
        repository.save(newOrder(7402L, 11L, 10L));
        repository.save(newOrder(7403L, 10L, 21L));

        assertThat(repository.countHistory(10L)).isEqualTo(3L);
        assertThat(repository.findHistoryPage(10L, new OrderHistoryQuery(new PageQuery(1, 20)))).hasSize(3);
    }

    @Test
    void findHistoryPage_and_countHistory_handle_null() {
        assertThat(repository.findHistoryPage(null, new OrderHistoryQuery(new PageQuery(1, 20)))).isEmpty();
        assertThat(repository.findHistoryPage(10L, null)).isEmpty();
        assertThat(repository.countHistory(null)).isZero();
    }
```

补充 import：

```java
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.order.dto.OrderHistoryQuery;
```

> `Order`/`OrderStatus`/`LocalDateTime`/`List` 已 import。

- [ ] **Step 2: 跑测试确认失败（编译错）**

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```

```
.\mvnw.cmd test -Dtest=MyBatisOrderRepositoryTest
```

Expected: 编译失败，`OrderRepository` 无 `findHistoryPage`/`countHistory` 方法。

- [ ] **Step 3: 加 OrderRepository 接口方法**

`OrderRepository.java` 在 `countByStatus` 之后追加（其余方法保留）：

```java
    /**
     * 按用户与查询条件分页查询历史订单（publisher_id 或 accepter_id 命中 + 排序 + LIMIT/OFFSET 下推 SQL）。
     *
     * <p>不加载 statusHistory（调用方用 OrderSummaryResponse 不依赖历史）；避免 findByParticipant 的逐条 loadHistory N+1。</p>
     *
     * @param userId 用户 ID，为 null 时返回空列表
     * @param query 查询条件，为 null 时返回空列表
     */
    List<Order> findHistoryPage(Long userId, OrderHistoryQuery query);

    /**
     * 按用户统计历史订单总数（publisher_id 或 accepter_id 命中，下推 SQL，用于分页 total）。
     *
     * @param userId 用户 ID，为 null 时返回 0
     */
    long countHistory(Long userId);
```

并在 import 区追加（保持字母序）：

```java
import com.campushub.backend.order.dto.OrderHistoryQuery;
```

- [ ] **Step 4: 实现 MyBatisOrderRepository 的 findHistoryPage/countHistory/buildHistoryWrapper**

在 `MyBatisOrderRepository.java` 的 `countByStatus` 之后追加实现：

```java
    @Override
    public List<Order> findHistoryPage(Long userId, OrderHistoryQuery query) {
        if (userId == null || query == null) {
            return List.of();
        }
        LambdaQueryWrapper<OrderEntity> wrapper = buildHistoryWrapper(userId);
        wrapper.orderByDesc(OrderEntity::getCreatedAt)
               .orderByDesc(OrderEntity::getId);
        int size = query.pageQuery().size();
        long offset = (long) (query.pageQuery().page() - 1) * size;
        wrapper.last("LIMIT " + size + " OFFSET " + offset);
        return orderMapper.selectList(wrapper).stream()
            .map(e -> e.toDomain(Collections.emptyList()))
            .toList();
    }

    @Override
    public long countHistory(Long userId) {
        if (userId == null) {
            return 0L;
        }
        return orderMapper.selectCount(buildHistoryWrapper(userId));
    }

    private LambdaQueryWrapper<OrderEntity> buildHistoryWrapper(Long userId) {
        LambdaQueryWrapper<OrderEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.eq(OrderEntity::getPublisherId, userId)
            .or().eq(OrderEntity::getAccepterId, userId));
        return wrapper;
    }
```

在 import 区追加：

```java
import com.campushub.backend.order.dto.OrderHistoryQuery;
```

> `LambdaQueryWrapper`/`OrderEntity`/`OrderMapper`/`OrderStatus`（2B.3c 加）/`Collections`（:11）已 import。

- [ ] **Step 5: 跑 Repository 测试确认通过**

```
.\mvnw.cmd test -Dtest=MyBatisOrderRepositoryTest
```

Expected: PASS，`MyBatisOrderRepositoryTest` 全部用例（含 2B.3c 的 16 + 2B.3d 的 3 + 新增 6 = 25 个）绿。

- [ ] **Step 6: 跑全量测试确认无回归**

```
.\mvnw.cmd test
```

Expected: 全绿，测试数 ≥ 194 + 6（新增）= 200。`OrderApplicationServiceImplTest` 此刻仍用旧 `listHistory`（调 `findByParticipant`），保留故仍绿。

> 若输出超 2000 行被截断写到 tool-output 文件，用 Grep 工具搜该文件确认无 `BUILD FAILURE`/`<<< FAILURE!`/`<<< ERROR!`，并搜 `Tests run:` 确认总数。

- [ ] **Step 7: Commit**

workdir=`D:\workspace\sec-ii-2026`：

```
git add backend/src/main/java/com/campushub/backend/order/repository/OrderRepository.java backend/src/main/java/com/campushub/backend/order/repository/MyBatisOrderRepository.java backend/src/test/java/com/campushub/backend/order/repository/MyBatisOrderRepositoryTest.java
```

```
git commit -m "refactor(order): push history list query down to SQL via findHistoryPage/countHistory in OrderRepository"
```

---

### Task 2: OrderApplicationServiceImpl.listHistory 改用 findHistoryPage/countHistory + 删 Comparator import

**Files:**
- Modify: `backend/src/main/java/com/campushub/backend/order/service/OrderApplicationServiceImpl.java:182-200`（`listHistory` 方法体）+ import 区
- Test: `backend/src/test/java/com/campushub/backend/order/service/OrderApplicationServiceImplTest.java`（不改，作回归护栏）

**Interfaces:**
- Consumes: Task 1 的 `OrderRepository.findHistoryPage(Long, OrderHistoryQuery) -> List<Order>` + `OrderRepository.countHistory(Long) -> long`。
- Produces: `OrderApplicationService.listHistory(Long operatorId, OrderHistoryQuery query) -> PageResponse<OrderSummaryResponse>`（签名不变，行为等价但走 SQL 下推）。

- [ ] **Step 1: 改 listHistory 方法体**

替换 `OrderApplicationServiceImpl.java:182-200` 的 `listHistory` 方法为：

```java
    @Override
    public PageResponse<OrderSummaryResponse> listHistory(Long operatorId, OrderHistoryQuery query) {
        findActiveUser(operatorId);
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "order history query must not be null");
        }
        List<Order> orders = orderRepository.findHistoryPage(operatorId, query);
        List<OrderSummaryResponse> items = orders.stream().map(OrderSummaryResponse::from).toList();
        long total = orderRepository.countHistory(operatorId);
        int page = query.pageQuery().page();
        int size = query.pageQuery().size();
        return new PageResponse<>(items, page, size, total);
    }
```

- [ ] **Step 2: 删除失效的 Comparator import**

从 `OrderApplicationServiceImpl.java` import 区删除（删前用 Grep 工具确认 `Comparator` 在该文件无其他引用）：

```java
import java.util.Comparator;
```

> grep 已确认：`Comparator` 仅 `listHistory`（:189 `Comparator.comparing(...)`）用 + import（:27）。Step 1 删方法体后无引用。`Locale` 保留（`parseStatus` :360 用）。`OrderHistoryQuery` 已 import（:20，现有 listHistory 签名用），无需新增。

- [ ] **Step 3: 跑 Service 测试确认 listHistory 回归绿**

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```

```
.\mvnw.cmd test -Dtest=OrderApplicationServiceImplTest
```

Expected: PASS，含 `listHistory` 用例及 `accept`/`updateStatus`/`requestArbitration`/`getDetail`/`autoCompleteOverdueOrders` 全绿。

- [ ] **Step 4: 跑全量测试确认不回归**

```
.\mvnw.cmd test
```

Expected: 全绿，测试数 = Task 1 Step 6 的数量（本 task 不新增测试，仅重构）。

- [ ] **Step 5: Commit**

workdir=`D:\workspace\sec-ii-2026`：

```
git add backend/src/main/java/com/campushub/backend/order/service/OrderApplicationServiceImpl.java
```

```
git commit -m "refactor(order): switch OrderApplicationServiceImpl.listHistory to SQL-pushed findHistoryPage/countHistory and drop Comparator import"
```

---

## Self-Review

**1. Spec 覆盖**
- §4.1 接口扩展（findHistoryPage/countHistory）→ Task 1 Step 3。✓
- §4.2 MyBatisOrderRepository 实现（buildHistoryWrapper OR / findHistoryPage 不调 loadHistory / countHistory selectCount）→ Task 1 Step 4。✓
- §4.3 过滤映射（eq(publisher_id) OR eq(accepter_id)）→ Task 1 Step 4 `buildHistoryWrapper`。✓
- §4.4 排序（created_at DESC + id DESC tie-breaker + NULL 无问题）→ Task 1 Step 4 `orderByDesc(createdAt).orderByDesc(id)`。✓
- §4.5 不调 loadHistory → Task 1 Step 4 `toDomain(Collections.emptyList())` + 测试 4 验证空 history。✓
- §4.6 Service 改造 → Task 2 Step 1。✓
- §4.7 import 清理（删 Comparator；Locale 保留；OrderHistoryQuery 已 import）→ Task 2 Step 2。✓
- §4.8 now 一致性 → 无需代码动作。✓
- §5 影响面 → File Structure 表。✓
- §6 测试策略（回归护栏 + 新增 6）→ Task 1 Step 1 + Task 2 Step 3。✓
- §7 验收（mvn 全绿 / listHistory 不再 findByParticipant(体内)/Comparator/subList / findHistoryPage+countHistory 暴露 / 不调 loadHistory / 契约不变 / autoCompleteOverdueOrders 不变 / findByParticipant 保留）→ Task 1 Step 5-6 + Task 2 Step 3-4。✓
- §8 风险（OR index merge / 不调 loadHistory / id DESC tie-breaker / Comparator 连带删 / LIMIT 拼接）→ spec 已记。✓

**2. 占位符扫描**：无 TBD/TODO；所有代码块为可直接落地实现。✓

**3. 类型一致性**：`findHistoryPage(Long userId, OrderHistoryQuery) -> List<Order>` / `countHistory(Long userId) -> long` 在 Task 1（接口+实现）与 Task 2（`orderRepository.findHistoryPage(operatorId, query)` + `countHistory(operatorId)`）签名一致；`OrderEntity` 字段（`publisherId`/`accepterId`/`createdAt`/`id`）与 spec 及源码 `@TableField` 一致；`toDomain(Collections.emptyList())` 与 `OrderEntity.toDomain(List)` 签名一致（2B.3c 已验证）。✓

**4. id DESC 断言方向复核**：测试 2 `findHistoryPage_sorts_by_created_at_desc_then_id_desc` 数据 `older`(先 save id=1) → `newer`(id=2) → `sameInstant`(id=3, 同 newer createdAt)。`orderByDesc(createdAt).orderByDesc(id)` 顺序 = `sameInstant`(id=3 最大) → `newer`(id=2) → `older`(id=1)。断言 `get(0)=sameInstant, get(1)=newer, get(2)=older` ✓ 与 id DESC 一致（修正了 2B.3b/3c/4 的同类断言方向 bug）。

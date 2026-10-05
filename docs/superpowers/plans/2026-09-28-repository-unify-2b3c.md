# 仓储层统一 2B.3c（admin listArbitrationOrders SQL 下推）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `AdminApplicationServiceImpl.listArbitrationOrders` 的 `findAll`（含逐条 `loadHistory` N+1）+ 内存过滤（`status==IN_ARBITRATION`）+ 内存 `updatedAt DESC nullsLast` 排序 + `subList` 分页下沉为 `OrderRepository.findArbitrationPage` / `countArbitration` 的 SQL（`LambdaQueryWrapper` `eq(status,IN_ARBITRATION)` + `orderByDesc` + `LIMIT/OFFSET` + `selectCount`），且 `findArbitrationPage` 不调 `loadHistory`（用 `toDomain(Collections.emptyList())`，因 `OrderSummaryResponse.from` 不依赖 history），行为零回归。

**Architecture:** `OrderRepository` 新增 `findArbitrationPage(int page, int size)` + `countArbitration()`（风格 A，`int page/size` 贴合现有签名与 `Math.max` 兜底，不引入 `PageQuery` 避免行为变更）；`MyBatisOrderRepository` 实现 `findArbitrationPage`（`eq(status,IN_ARBITRATION)` + `orderByDesc(updatedAt).orderByDesc(id)` + `LIMIT/OFFSET`，不调 `loadHistory`）+ `countArbitration`（`selectCount`）；Service `listArbitrationOrders` 改调新方法，删连带失效的 `Comparator` import。

**Tech Stack:** Java 21 / Spring Boot 3.5.0 / MyBatis-Plus 3.5.7（`BaseMapper` + `LambdaQueryWrapper`，无 XML mapper）/ H2 MySQL 兼容模式（测试）/ MySQL（生产）/ JUnit 5 + AssertJ。

**Spec:** `docs/superpowers/specs/2026-09-28-repository-unify-2b3c-design.md`（设计依据，与本 plan 一同阅读）

## Global Constraints

- **JDK 21**：本机默认 JDK 17，跑 Maven 前必须先设 `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"`（单条命令），再单独跑 `.\mvnw.cmd test`（workdir=`D:\workspace\sec-ii-2026\backend`）。
- **PowerShell 7+**：bash 工具禁止管道 `|`、分号 `;`、`&&`、`&`、重定向 `>`、反引号、`$()`；每条命令单独执行。
- **Maven Wrapper**：用 `.\mvnw.cmd`（非 `./mvnw`），workdir=`D:\workspace\sec-ii-2026\backend`。
- **前端契约不变**：`PageResponse(items, page, size, total)` / `OrderSummaryResponse` 字段结构不变；`AdminController` 转换不变；`listArbitrationOrders` 签名 `(Long, int, int)` 不变；API 行为不变。
- **测试基线 174/174 不回归**（2B.3b 后）：每 task 结束跑全量验证。
- **分支 `refactor/optimization`**：仅 SDD 的 commit step 可执行；push 到 PR #10 延续授权模式；不 merge。
- **无 XML mapper**：全部 `BaseMapper` + `LambdaQueryWrapper`。
- **H2 MySQL 兼容模式**（测试）与 MySQL（生产）行为一致。

## File Structure

| 文件 | 责任 | 本 plan 操作 |
|---|---|---|
| `order/repository/OrderRepository.java` | 仓储接口 | 加 `findArbitrationPage(int, int)` + `countArbitration()`（其余方法保留） |
| `order/repository/MyBatisOrderRepository.java` | MyBatis 实现 | 实现 `findArbitrationPage` + `countArbitration`（不调 loadHistory）+ import（OrderStatus） |
| `admin/service/AdminApplicationServiceImpl.java` | 应用服务 | `listArbitrationOrders` 改用新方法；删 `Comparator` import |
| `order/repository/MyBatisOrderRepositoryTest.java` | Repository 切片测试 | 新增 7 个 `findArbitrationPage`/`countArbitration` 用例 |

---

### Task 1: OrderRepository 扩展 findArbitrationPage/countArbitration + MyBatisOrderRepository 实现

**Files:**
- Modify: `backend/src/main/java/com/campushub/backend/order/repository/OrderRepository.java`
- Modify: `backend/src/main/java/com/campushub/backend/order/repository/MyBatisOrderRepository.java`
- Test: `backend/src/test/java/com/campushub/backend/order/repository/MyBatisOrderRepositoryTest.java`

**Interfaces:**
- Consumes: `OrderEntity`（`@TableName("ord_order")`，字段 `status`(String, 列 `status`)/`updatedAt`(LocalDateTime, 列 `updated_at`, **可 null**)/`id`(Long)）、`OrderStatus`（`order.domain` 枚举，`IN_ARBITRATION`/`PENDING`/`ACCEPTED`/`IN_PROGRESS`/`COMPLETED`/`CANCELLED`，`.name()` 全大写）、`OrderMapper extends BaseMapper<OrderEntity>`（`selectList`/`selectCount`）、`Collections`（已 import，`:11`）。
- Produces: `OrderRepository.findArbitrationPage(int page, int size) -> List<Order>`（不调 loadHistory，Order 含空 statusHistory）、`OrderRepository.countArbitration() -> long`（供 Task 2 Service 调用）。

- [ ] **Step 1: 写失败测试（7 个 findArbitrationPage/countArbitration 用例）**

在 `MyBatisOrderRepositoryTest.java` 类体内末尾（`newOrder` 工厂之前）追加测试方法。

> 现有工厂 `newOrder(demandId, publisherId, accepterId)` 默认 `status=ACCEPTED`/`updatedAt=null`。造 `IN_ARBITRATION` 需 `setStatus(OrderStatus.IN_ARBITRATION)` + `save`。造非 null `updatedAt` 需 save 后 `setUpdatedAt` + 二次 `save`（updateById 路径）。

测试方法（追加到类体内）：

```java
    @Test
    void findArbitrationPage_returns_only_in_arbitration_orders() {
        Order arbitration = repository.save(newOrder(5001L, 10L, 20L));
        arbitration.setStatus(OrderStatus.IN_ARBITRATION);
        repository.save(arbitration);
        Order pending = repository.save(newOrder(5002L, 11L, 21L));
        pending.setStatus(OrderStatus.PENDING);
        repository.save(pending);
        Order completed = repository.save(newOrder(5003L, 12L, 22L));
        completed.setStatus(OrderStatus.COMPLETED);
        repository.save(completed);

        List<Order> page = repository.findArbitrationPage(1, 20);

        assertThat(page).extracting(Order::getId).containsExactly(arbitration.getId());
        assertThat(repository.countArbitration()).isEqualTo(1L);
    }

    @Test
    void findArbitrationPage_sorts_by_updated_at_desc_then_id_desc() {
        Order older = repository.save(newOrder(5101L, 10L, 20L));
        older.setStatus(OrderStatus.IN_ARBITRATION);
        older.setUpdatedAt(LocalDateTime.of(2026, 9, 1, 10, 0));
        repository.save(older);
        Order newer = repository.save(newOrder(5102L, 11L, 21L));
        newer.setStatus(OrderStatus.IN_ARBITRATION);
        newer.setUpdatedAt(LocalDateTime.of(2026, 9, 2, 10, 0));
        repository.save(newer);
        Order sameInstant = repository.save(newOrder(5103L, 12L, 22L));
        sameInstant.setStatus(OrderStatus.IN_ARBITRATION);
        sameInstant.setUpdatedAt(newer.getUpdatedAt());
        repository.save(sameInstant);

        List<Order> page = repository.findArbitrationPage(1, 20);

        assertThat(page).hasSize(3);
        assertThat(page.get(0).getId()).isEqualTo(newer.getId());
        assertThat(page.get(1).getId()).isEqualTo(sameInstant.getId());
        assertThat(page.get(2).getId()).isEqualTo(older.getId());
    }

    @Test
    void findArbitrationPage_places_null_updated_at_last() {
        Order withTime = repository.save(newOrder(5201L, 10L, 20L));
        withTime.setStatus(OrderStatus.IN_ARBITRATION);
        withTime.setUpdatedAt(LocalDateTime.of(2026, 9, 1, 10, 0));
        repository.save(withTime);
        Order nullTime = repository.save(newOrder(5202L, 11L, 21L));
        nullTime.setStatus(OrderStatus.IN_ARBITRATION);
        repository.save(nullTime);

        List<Order> page = repository.findArbitrationPage(1, 20);

        assertThat(page).hasSize(2);
        assertThat(page.get(0).getId()).isEqualTo(withTime.getId());
        assertThat(page.get(1).getId()).isEqualTo(nullTime.getId());
    }

    @Test
    void findArbitrationPage_applies_limit_and_offset_for_pagination() {
        for (int i = 0; i < 5; i++) {
            Order o = repository.save(newOrder(5300L + i, 10L, 20L));
            o.setStatus(OrderStatus.IN_ARBITRATION);
            o.setUpdatedAt(LocalDateTime.now().minusMinutes(5 - i));
            repository.save(o);
        }

        assertThat(repository.findArbitrationPage(1, 2)).hasSize(2);
        assertThat(repository.findArbitrationPage(2, 2)).hasSize(2);
        assertThat(repository.findArbitrationPage(3, 2)).hasSize(1);
        assertThat(repository.countArbitration()).isEqualTo(5L);
    }

    @Test
    void findArbitrationPage_returns_orders_with_empty_status_history() {
        Order arbitration = repository.save(newOrder(5401L, 10L, 20L));
        arbitration.setStatus(OrderStatus.IN_ARBITRATION);
        arbitration.addHistory(null, OrderStatus.ACCEPTED, 20L, "接单", LocalDateTime.now());
        repository.save(arbitration);

        List<Order> page = repository.findArbitrationPage(1, 20);

        assertThat(page).hasSize(1);
        assertThat(page.get(0).getStatusHistory()).isNotNull().isEmpty();
    }

    @Test
    void countArbitration_counts_only_in_arbitration_orders() {
        Order a1 = repository.save(newOrder(5501L, 10L, 20L));
        a1.setStatus(OrderStatus.IN_ARBITRATION);
        repository.save(a1);
        Order a2 = repository.save(newOrder(5502L, 11L, 21L));
        a2.setStatus(OrderStatus.IN_ARBITRATION);
        repository.save(a2);
        Order other = repository.save(newOrder(5503L, 12L, 22L));
        other.setStatus(OrderStatus.COMPLETED);
        repository.save(other);

        assertThat(repository.countArbitration()).isEqualTo(2L);
    }

    @Test
    void countArbitration_matches_findArbitrationPage_total() {
        for (int i = 0; i < 3; i++) {
            Order o = repository.save(newOrder(5600L + i, 10L, 20L));
            o.setStatus(OrderStatus.IN_ARBITRATION);
            repository.save(o);
        }

        assertThat(repository.countArbitration()).isEqualTo(3L);
        assertThat(repository.findArbitrationPage(1, 20)).hasSize(3);
    }
```

> `OrderStatus`/`LocalDateTime`/`List`/`Order`/`OrderStatusHistoryEntry` 已 import，无需重复。

- [ ] **Step 2: 跑测试确认失败（编译错）**

设置 JDK 21（单独一条命令）：

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```

跑 Repository 测试（workdir=`D:\workspace\sec-ii-2026\backend`，单独一条）：

```
.\mvnw.cmd test -Dtest=MyBatisOrderRepositoryTest
```

Expected: 编译失败，`OrderRepository` 无 `findArbitrationPage`/`countArbitration` 方法。

- [ ] **Step 3: 加 OrderRepository 接口方法**

`OrderRepository.java` 在 `findAll` 之后、`deleteById` 之前追加（其余方法保留不动）：

```java
    /**
     * 分页查询仲裁中订单（status=IN_ARBITRATION + updatedAt DESC + LIMIT/OFFSET 下推 SQL）。
     *
     * <p>不加载 statusHistory（调用方用 OrderSummaryResponse 不依赖历史）；避免 findAll 的逐条 loadHistory N+1。</p>
     *
     * @param page 页码（>=1，由 Service 层 Math.max 兜底）
     * @param size 每页大小（>=1，由 Service 层 Math.max 兜底）
     */
    List<Order> findArbitrationPage(int page, int size);

    /**
     * 统计仲裁中订单总数（status=IN_ARBITRATION 下推 SQL，用于分页 total）。
     */
    long countArbitration();
```

> `List` 已 import（:4）。

- [ ] **Step 4: 实现 MyBatisOrderRepository 的 findArbitrationPage/countArbitration**

在 `MyBatisOrderRepository.java` 的 `findAll` 方法之后、`deleteById` 之前追加实现：

```java
    @Override
    public List<Order> findArbitrationPage(int page, int size) {
        LambdaQueryWrapper<OrderEntity> wrapper = new LambdaQueryWrapper<OrderEntity>()
            .eq(OrderEntity::getStatus, OrderStatus.IN_ARBITRATION.name())
            .orderByDesc(OrderEntity::getUpdatedAt)
            .orderByDesc(OrderEntity::getId);
        long offset = (long) (page - 1) * size;
        wrapper.last("LIMIT " + size + " OFFSET " + offset);
        return orderMapper.selectList(wrapper).stream()
            .map(e -> e.toDomain(Collections.emptyList()))
            .toList();
    }

    @Override
    public long countArbitration() {
        LambdaQueryWrapper<OrderEntity> wrapper = new LambdaQueryWrapper<OrderEntity>()
            .eq(OrderEntity::getStatus, OrderStatus.IN_ARBITRATION.name());
        return orderMapper.selectCount(wrapper);
    }
```

在 import 区追加（保持字母序）：

```java
import com.campushub.backend.order.domain.OrderStatus;
```

> `Collections` 已 import（:11）。`LambdaQueryWrapper`/`OrderEntity`/`OrderMapper` 已 import。`OrderStatus` 现未 import（`save`/`findById` 等未直接用 `OrderStatus`），需新增。

- [ ] **Step 5: 跑 Repository 测试确认通过**

确保 `$env:JAVA_HOME` 已设（若新 session 先执行 Step 2 的设置命令）。workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test -Dtest=MyBatisOrderRepositoryTest
```

Expected: PASS，`MyBatisOrderRepositoryTest` 全部用例（含原有 8 + 新增 7 = 15 个）绿。

- [ ] **Step 6: 跑全量测试确认无回归**

workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test
```

Expected: 全绿，测试数 ≥ 174 + 7（新增）= 181。`AdminApplicationServiceImplTest` 此刻仍用旧 `listArbitrationOrders`（调 `findAll`），`findAll` 保留故仍绿。

> 若输出超 2000 行被截断写到 tool-output 文件，用 Grep 工具搜该文件确认无 `BUILD FAILURE`/`<<< FAILURE!`/`<<< ERROR!`，并搜 `Tests run:` 确认总数。

- [ ] **Step 7: Commit**

workdir=`D:\workspace\sec-ii-2026`：

```
git add backend/src/main/java/com/campushub/backend/order/repository/OrderRepository.java backend/src/main/java/com/campushub/backend/order/repository/MyBatisOrderRepository.java backend/src/test/java/com/campushub/backend/order/repository/MyBatisOrderRepositoryTest.java
```

```
git commit -m "refactor(order): push arbitration list query down to SQL via findArbitrationPage/countArbitration in OrderRepository"
```

---

### Task 2: AdminApplicationServiceImpl.listArbitrationOrders 改用 findArbitrationPage/countArbitration + 删 Comparator import

**Files:**
- Modify: `backend/src/main/java/com/campushub/backend/admin/service/AdminApplicationServiceImpl.java:165-181`（`listArbitrationOrders` 方法体）+ import 区
- Test: `backend/src/test/java/com/campushub/backend/admin/service/AdminApplicationServiceImplTest.java`（不改，作回归护栏）

**Interfaces:**
- Consumes: Task 1 的 `OrderRepository.findArbitrationPage(int, int) -> List<Order>` + `OrderRepository.countArbitration() -> long`。
- Produces: `AdminApplicationService.listArbitrationOrders(Long operatorId, int page, int size) -> PageResponse<OrderSummaryResponse>`（签名不变，行为等价但走 SQL 下推）。

- [ ] **Step 1: 改 listArbitrationOrders 方法体**

替换 `AdminApplicationServiceImpl.java:165-181` 的 `listArbitrationOrders` 方法为：

```java
    @Override
    public PageResponse<OrderSummaryResponse> listArbitrationOrders(Long operatorId, int page, int size) {
        requireAdmin(operatorId);
        int resolvedPage = Math.max(page, 1);
        int resolvedSize = Math.max(size, 1);

        List<Order> orders = orderRepository.findArbitrationPage(resolvedPage, resolvedSize);
        List<OrderSummaryResponse> items = orders.stream().map(OrderSummaryResponse::from).toList();
        long total = orderRepository.countArbitration();
        return new PageResponse<>(items, resolvedPage, resolvedSize, total);
    }
```

> `Math.max(page,1)`/`Math.max(size,1)` 兜底保持（与现状一致，不引入 `PageQuery` 避免行为变更）。`requireAdmin` 在前。

- [ ] **Step 2: 删除失效的 Comparator import**

从 `AdminApplicationServiceImpl.java` import 区删除（删前用 Grep 工具确认 `Comparator` 在该文件无其他引用）：

```java
import java.util.Comparator;
```

> grep 已确认（2B.3b 后）：`Comparator` 仅 `listArbitrationOrders`（:174 `Comparator.comparing(...)`）用 + import（:38）。Step 1 删方法体后，`Comparator` 无引用。`Locale` 保留（`updateUserRole`/`reviewDemand`/`resolveOrderArbitration` 用）。

- [ ] **Step 3: 跑 Service 测试确认 listArbitrationOrders 回归绿**

确保 `$env:JAVA_HOME` 已设。workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test -Dtest=AdminApplicationServiceImplTest
```

Expected: PASS，含 `shouldListArbitrationOrdersByActualStatus`（listArbitrationOrders 用例）及 `listUsers`/`listPendingDemands`/`getDashboard`/`resolveArbitrationAndDeleteOrder` 等全绿。

- [ ] **Step 4: 跑全量测试确认不回归**

workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test
```

Expected: 全绿，测试数 = Task 1 Step 6 的数量（本 task 不新增测试，仅重构）。

- [ ] **Step 5: Commit**

workdir=`D:\workspace\sec-ii-2026`：

```
git add backend/src/main/java/com/campushub/backend/admin/service/AdminApplicationServiceImpl.java
```

```
git commit -m "refactor(admin): switch AdminApplicationServiceImpl.listArbitrationOrders to SQL-pushed findArbitrationPage/countArbitration and drop Comparator import"
```

---

## Self-Review

**1. Spec 覆盖**
- §4.1 接口扩展（findArbitrationPage(int,int)/countArbitration()，int page/size）→ Task 1 Step 3。✓
- §4.2 MyBatisOrderRepository 实现（findArbitrationPage 不调 loadHistory 用 toDomain(Collections.emptyList()) / countArbitration selectCount）→ Task 1 Step 4。✓
- §4.3 过滤映射（eq(status,IN_ARBITRATION)）→ Task 1 Step 4。✓
- §4.4 排序（updated_at DESC + id DESC tie-breaker + NULL nullsLast）→ Task 1 Step 4 `orderByDesc(updatedAt).orderByDesc(id)`。✓
- §4.5 不调 loadHistory → Task 1 Step 4 `toDomain(Collections.emptyList())` + 测试 5 验证空 history。✓
- §4.6 Service 改造（保持 Math.max + int 签名）→ Task 2 Step 1。✓
- §4.7 import 清理（删 Comparator；Locale 保留）→ Task 2 Step 2。✓
- §4.8 now 一致性 → 无需代码动作（无 now 依赖）。✓
- §5 影响面文件清单 → File Structure 表。✓
- §6 测试策略（回归护栏 1 + 新增 7）→ Task 1 Step 1（7 新增）+ Task 2 Step 3（1 护栏）。✓
- §7 验收（mvn 全绿 / listArbitrationOrders 不再 findAll/Comparator/subList / findArbitrationPage+countArbitration 暴露 / 不调 loadHistory / 契约不变 / findAll+findByParticipant 保留）→ Task 1 Step 5-6 + Task 2 Step 3-4。✓
- §8 风险（updated_at NULL DESC nullsLast / id DESC tie-breaker / 不调 loadHistory / int offset / Comparator 连带删 / LIMIT 拼接 / status 无索引）→ spec 已记，测试覆盖。✓

**2. 占位符扫描**：无 TBD/TODO/"add appropriate"等；所有代码块为可直接落地实现。✓

**3. 类型一致性**：`findArbitrationPage(int page, int size) -> List<Order>` / `countArbitration() -> long` 在 Task 1（接口+实现）与 Task 2（`orderRepository.findArbitrationPage(resolvedPage, resolvedSize)` + `countArbitration()`）签名一致；`OrderEntity` 字段（`status`/`updatedAt`/`id`）与 spec 及源码 `@TableField` 一致；`toDomain(Collections.emptyList())` 与 `OrderEntity.toDomain(List)` 签名一致（`Collections.emptyList()` 是 `List<OrderStatusHistoryEntry>`）。✓

# 2C.1（Order loadHistory 批量化）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `MyBatisOrderRepository.assembleAll` 的逐条 `loadHistory`（N+1）改为批量 `assembleWithBatchHistory`（一次 `selectList(in(order_id, ids))` + 内存 `groupBy`），`findByParticipant`/`findAll` 改调新方法，行为零回归。

**Architecture:** 新增私有 `assembleWithBatchHistory` + `loadHistories`（批量版）；`findByParticipant`/`findAll` 改调 `assembleWithBatchHistory`；删 `assembleAll`；`findById`/`findByDemandId` 仍用单条 `loadHistory`（非 N+1，不动）。

**Tech Stack:** Java 21 / Spring Boot 3.5.0 / MyBatis-Plus 3.5.7（`BaseMapper` + `LambdaQueryWrapper`）/ H2 MySQL 兼容模式 / JUnit 5 + AssertJ。

**Spec:** `docs/superpowers/specs/2026-10-04-repository-n-plus-1-2c1-design.md`（设计依据）

## Global Constraints

- **JDK 21**：跑 Maven 前先设 `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"`（单条），再单独 `.\mvnw.cmd test`（workdir=`D:\workspace\sec-ii-2026\backend`）。
- **PowerShell 7+**：禁管道 `|`、分号 `;`、`&&`、`&`、重定向 `>`、反引号、`$()`；每条命令单独。
- **Maven**：`.\mvnw.cmd`（非 `./mvnw`），workdir=`D:\workspace\sec-ii-2026\backend`。
- **测试基线 207/207 不回归**（2B.6 后）。
- **分支 `refactor/2c`**：仅 commit；push 到新 PR（延续授权模式）；不 merge。
- **无 XML mapper**：全部 `BaseMapper` + `LambdaQueryWrapper`。

## File Structure

| 文件 | 责任 | 本 plan 操作 |
|---|---|---|
| `order/repository/MyBatisOrderRepository.java` | MyBatis 实现 | `assembleAll` → `assembleWithBatchHistory` + 新增 `loadHistories` + `findByParticipant`/`findAll` 改调 + 删 `assembleAll` + import |
| `order/repository/MyBatisOrderRepositoryTest.java` | 切片测试 | 新增 3 个 batch loadHistory 用例 |

---

### Task 1: MyBatisOrderRepository assembleWithBatchHistory + loadHistories + 改 findByParticipant/findAll

**Files:**
- Modify: `backend/src/main/java/com/campushub/backend/order/repository/MyBatisOrderRepository.java`
- Test: `backend/src/test/java/com/campushub/backend/order/repository/MyBatisOrderRepositoryTest.java`

**Interfaces:**
- Consumes: `OrderEntity`（`getId()`/`toDomain(List)`）、`OrderStatusLogEntity`（`getOrderId()`/`toDomain()`）、`OrderStatusLogMapper`（`selectList`）、`LambdaQueryWrapper`（`in`/`orderByAsc`）、`OrderStatusHistoryEntry`。
- Produces: 无新接口（内部重构，`findByParticipant`/`findAll` 签名不变）。

- [ ] **Step 1: 写失败测试（3 个 batch loadHistory 用例）**

在 `MyBatisOrderRepositoryTest.java` 类体内末尾（`newOrder` 工厂之前）追加测试方法。

> 沿用 `newOrder(demandId, publisherId, accepterId)`（默认 status=ACCEPTED）。`addHistory` 加流水。

测试方法（追加到类体内）：

```java
    @Test
    void findByParticipant_loads_history_in_batch_with_correct_order() {
        Order o1 = newOrder(8001L, 10L, 20L);
        o1.addHistory(null, OrderStatus.ACCEPTED, 20L, "接单1", LocalDateTime.of(2026, 9, 1, 10, 0));
        o1.addHistory(OrderStatus.ACCEPTED, OrderStatus.IN_PROGRESS, 20L, "出发1", LocalDateTime.of(2026, 9, 1, 10, 30));
        repository.save(o1);
        Order o2 = newOrder(8002L, 10L, 21L);
        o2.addHistory(null, OrderStatus.ACCEPTED, 21L, "接单2", LocalDateTime.of(2026, 9, 2, 10, 0));
        repository.save(o2);
        repository.save(newOrder(8003L, 30L, 40L)); // 无关 user

        List<Order> mine = repository.findByParticipant(10L);

        assertThat(mine).hasSize(2);
        Order loaded1 = mine.stream().filter(o -> o.getDemandId().equals(8001L)).findFirst().orElseThrow();
        assertThat(loaded1.getStatusHistory()).hasSize(2);
        assertThat(loaded1.getStatusHistory().get(0).toStatus()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(loaded1.getStatusHistory().get(1).toStatus()).isEqualTo(OrderStatus.IN_PROGRESS);
        Order loaded2 = mine.stream().filter(o -> o.getDemandId().equals(8002L)).findFirst().orElseThrow();
        assertThat(loaded2.getStatusHistory()).hasSize(1);
        assertThat(loaded2.getStatusHistory().get(0).toStatus()).isEqualTo(OrderStatus.ACCEPTED);
    }

    @Test
    void findAll_loads_history_in_batch_for_all_orders() {
        Order o1 = newOrder(8101L, 10L, 20L);
        o1.addHistory(null, OrderStatus.ACCEPTED, 20L, "A", LocalDateTime.of(2026, 9, 1, 8, 0));
        repository.save(o1);
        Order o2 = newOrder(8102L, 11L, 21L);
        o2.addHistory(null, OrderStatus.ACCEPTED, 21L, "B", LocalDateTime.of(2026, 9, 1, 9, 0));
        o2.addHistory(OrderStatus.ACCEPTED, OrderStatus.COMPLETED, 11L, "C", LocalDateTime.of(2026, 9, 1, 12, 0));
        repository.save(o2);

        List<Order> all = repository.findAll();

        assertThat(all).hasSize(2);
        Order loaded1 = all.stream().filter(o -> o.getDemandId().equals(8101L)).findFirst().orElseThrow();
        assertThat(loaded1.getStatusHistory()).hasSize(1);
        Order loaded2 = all.stream().filter(o -> o.getDemandId().equals(8102L)).findFirst().orElseThrow();
        assertThat(loaded2.getStatusHistory()).hasSize(2);
        assertThat(loaded2.getStatusHistory().get(0).note()).isEqualTo("B");
        assertThat(loaded2.getStatusHistory().get(1).note()).isEqualTo("C");
    }

    @Test
    void findByParticipant_returns_empty_status_history_when_no_logs() {
        repository.save(newOrder(8201L, 10L, 20L)); // 无 addHistory

        List<Order> mine = repository.findByParticipant(10L);

        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).getStatusHistory()).isNotNull().isEmpty();
    }
```

> `OrderStatus`/`LocalDateTime`/`List`/`Order`/`OrderStatusHistoryEntry` 已 import。

- [ ] **Step 2: 跑测试确认失败/通过**

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```

```
.\mvnw.cmd test -Dtest=MyBatisOrderRepositoryTest
```

Expected: 3 新用例应**直接通过**（因 `assembleAll` 逐条 `loadHistory` 已能加载 history，batch 是优化非功能变更）。若通过，Step 3-5 重构后须仍通过（验证 batch 等价）。

> 这是"重构前先加测试"——测试验证行为（history 正确加载），重构后行为不变。

- [ ] **Step 3: 实现 assembleWithBatchHistory + loadHistories + 改 findByParticipant/findAll + 删 assembleAll**

在 `MyBatisOrderRepository.java`：

**改 `findByParticipant`**（:82-90）：`assembleAll` → `assembleWithBatchHistory`

```java
    @Override
    public List<Order> findByParticipant(Long userId) {
        if (userId == null) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<OrderEntity> wrapper = new LambdaQueryWrapper<OrderEntity>()
            .eq(OrderEntity::getPublisherId, userId)
            .or()
            .eq(OrderEntity::getAccepterId, userId);
        return assembleWithBatchHistory(orderMapper.selectList(wrapper));
    }
```

**改 `findAll`**（:92-95）：`assembleAll` → `assembleWithBatchHistory`

```java
    @Override
    public List<Order> findAll() {
        return assembleWithBatchHistory(orderMapper.selectList(null));
    }
```

**删 `assembleAll`**（:173-180），**新增 `assembleWithBatchHistory` + `loadHistories`**（在 `loadHistory` 之前）：

```java
    private List<Order> assembleWithBatchHistory(List<OrderEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> orderIds = entities.stream().map(OrderEntity::getId).filter(Objects::nonNull).toList();
        Map<Long, List<OrderStatusHistoryEntry>> historyByOrderId = loadHistories(orderIds);
        List<Order> orders = new ArrayList<>(entities.size());
        for (OrderEntity entity : entities) {
            orders.add(entity.toDomain(historyByOrderId.getOrDefault(entity.getId(), Collections.emptyList())));
        }
        return orders;
    }

    private Map<Long, List<OrderStatusHistoryEntry>> loadHistories(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<OrderStatusLogEntity> logs = statusLogMapper.selectList(
            new LambdaQueryWrapper<OrderStatusLogEntity>()
                .in(OrderStatusLogEntity::getOrderId, orderIds)
                .orderByAsc(OrderStatusLogEntity::getChangedAt)
                .orderByAsc(OrderStatusLogEntity::getId)
        );
        Map<Long, List<OrderStatusHistoryEntry>> result = new HashMap<>();
        for (OrderStatusLogEntity log : logs) {
            result.computeIfAbsent(log.getOrderId(), k -> new ArrayList<>()).add(log.toDomain());
        }
        return result;
    }
```

> `loadHistory`（单条，:184-199）保留不动（`findById`/`findByDemandId` 用）。

**import 区追加**（保持字母序）：

```java
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
```

- [ ] **Step 4: 跑 Repository 测试确认通过**

```
.\mvnw.cmd test -Dtest=MyBatisOrderRepositoryTest
```

Expected: PASS，全部用例（含原有 + 2B.3c/2B.5/2B.3d 的 + 新增 3）绿。重点：`findByParticipant_loads_history_in_batch_*` / `findAll_loads_history_in_batch_*` / `findByParticipant_returns_empty_status_history_*` + 现有 `findByParticipant_matches_publisher_or_accepter` / `findAll_returns_all_orders_with_their_history` / `status_history_is_persisted_and_loaded_in_chronological_order` 全绿。

- [ ] **Step 5: 跑全量测试确认无回归**

```
.\mvnw.cmd test
```

Expected: 全绿，测试数 ≥ 207 + 3（新增）= 210。

> 若输出超 2000 行被截断写到 tool-output 文件，用 Grep 工具搜该文件确认无 `BUILD FAILURE`/`<<< FAILURE!`/`<<< ERROR!`，并搜 `Tests run:` 确认总数。

- [ ] **Step 6: Commit**

workdir=`D:\workspace\sec-ii-2026`：

```
git add backend/src/main/java/com/campushub/backend/order/repository/MyBatisOrderRepository.java backend/src/test/java/com/campushub/backend/order/repository/MyBatisOrderRepositoryTest.java
```

```
git commit -m "perf(order): batch loadHistory in assembleWithBatchHistory to eliminate N+1 in findByParticipant/findAll"
```

---

## Self-Review

**1. Spec 覆盖**
- §3.1 assembleWithBatchHistory → Task 1 Step 3。✓
- §3.2 loadHistories 批量查询 → Task 1 Step 3。✓
- §3.3 findByParticipant/findAll 改调 → Task 1 Step 3。✓
- §3.4 删 assembleAll → Task 1 Step 3。✓
- §3.5 import（HashMap/Map/Objects）→ Task 1 Step 3。✓
- §5 测试策略（回归护栏 3 + 新增 3）→ Task 1 Step 1。✓
- §6 验收（mvn 全绿 / 不再 assembleAll / findByParticipant/findAll 调 assembleWithBatchHistory / history 完整 / findById/findByDemandId 不变）→ Task 1 Step 4-5。✓

**2. 占位符扫描**：无 TBD/TODO；所有代码块可直接落地。✓

**3. 类型一致性**：`assembleWithBatchHistory(List<OrderEntity>) -> List<Order>` 替 `assembleAll(List<OrderEntity>) -> List<Order>` 签名一致；`loadHistories(List<Long>) -> Map<Long, List<OrderStatusHistoryEntry>>`；`OrderEntity.toDomain(List)` 与 `loadHistory` 返回的 `List<OrderStatusHistoryEntry>` 一致；`OrderStatusLogEntity.getOrderId()`/`toDomain()` 与 `loadHistory` 用法一致。✓

**4. 行为等价**：`loadHistories` 的 `orderByAsc(changedAt).orderByAsc(id)` 与 `loadHistory`（:184-199 `orderByAsc(changedAt).orderByAsc(id)`）一致；每个 order 的 `List` 保持插入序（升序）；`getOrDefault(id, emptyList)` 与 `loadHistory`（null orderId → `emptyList`）一致。✓

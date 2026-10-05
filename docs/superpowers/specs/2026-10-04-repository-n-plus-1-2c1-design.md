# 2C.1 设计（Order loadHistory 批量化）

- 日期：2026-10-04
- 状态：待评审（用户已授权直接执行）
- 分支：`refactor/2c`（HEAD `ace729c`，基于 github/main）
- 上游 spec：`docs/superpowers/specs/2026-10-04-repository-n-plus-1-2c-design.md`（2C 总体，本文件为其第 1 子阶段）
- 范围：`MyBatisOrderRepository` 的 `assembleAll` 逐条 `loadHistory` → 批量化

## 1. 背景与现状（探索已核实）

`MyBatisOrderRepository.assembleAll`（:173-180）对每个 `OrderEntity` 逐条调 `loadHistory(orderId)`（查 `ord_order_status_log`），N+1：

```java
private List<Order> assembleAll(List<OrderEntity> entities) {
    if (entities == null || entities.isEmpty()) {
        return new ArrayList<>();
    }
    List<Order> orders = new ArrayList<>(entities.size());
    for (OrderEntity entity : entities) {
        orders.add(entity.toDomain(loadHistory(entity.getId())));  // ← N+1
    }
    return orders;
}
```

调用方：
- `findByParticipant`（:82-90）→ `assembleAll`（autoCompleteOverdueOrders / buildAcceptedCategoryStats / checkPendingReviewsAndAutoComplete）
- `findAll`（:92-95）→ `assembleAll`（getDashboard dailyActiveUsers，2B.3d 保留）

`findById`（:56-65）/`findByDemandId`（:68-78）也调 `loadHistory`，但单条查询非 N+1，**不动**。

`loadHistory`（:184-199）：`selectList(eq(order_id).orderByAsc(changedAt).orderByAsc(id))` → map `toDomain`。

## 2. 目标 / 非目标

### 目标
1. `MyBatisOrderRepository` 新增私有 `assembleWithBatchHistory(List<OrderEntity>)`：一次 `selectList(in(order_id, ids))` 查所有 `OrderStatusLogEntity`（`orderByAsc(changedAt).orderByAsc(id)`），内存 `groupBy(orderId)` → `Map<Long, List<OrderStatusHistoryEntry>>`，逐 entity `toDomain(historyMap.getOrDefault(id, emptyList))`。
2. `findByParticipant`/`findAll` 改调 `assembleWithBatchHistory`（替 `assembleAll`）。
3. 删 `assembleAll`（仅两调用方，改调后无引用）。
4. 行为零回归：`findByParticipant`/`findAll` 返回的 `Order` 含完整 `statusHistory`（按 `changedAt`/`id` 升序），与原逐条 `loadHistory` 一致。

### 非目标
- 不改 `OrderRepository` 接口（仅 MyBatisOrderRepository 内部重构）。
- 不改 `findById`/`findByDemandId`/`findArbitrationPage`/`findHistoryPage`/`save`/`deleteById`/`count*`。
- 不动 `loadHistory` 私有方法（`findById`/`findByDemandId` 仍用单条）。
- 不动 Service 层（调用方无感知）。
- 不改 schema/Entity/Domain。

## 3. 设计

### 3.1 `assembleWithBatchHistory` 实现

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
```

### 3.2 `loadHistories` 批量查询

新增私有 `loadHistories(List<Long> orderIds)`（批量版 `loadHistory`）：

```java
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

> 一次 `selectList(in(order_id, ids))` 替 N 次 `loadHistory`。`orderByAsc(changedAt).orderByAsc(id)` 保证每个 order 的 history 升序（与 `loadHistory` 一致）。`HashMap` 无序，但每个 order 的 `List` 保持插入序（即 `changedAt`/`id` 升序）。

### 3.3 `findByParticipant`/`findAll` 改调

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

@Override
public List<Order> findAll() {
    return assembleWithBatchHistory(orderMapper.selectList(null));
}
```

### 3.4 删 `assembleAll`

`assembleAll` 仅 `findByParticipant`/`findAll` 调用，改调后无引用，删。`loadHistory`（单条）保留（`findById`/`findByDemandId` 用）。

### 3.5 import

新增（若未 import）：`java.util.Objects`（`Objects::nonNull`）/`java.util.HashMap`/`java.util.Map`。需确认现有 import。

## 4. 影响面

### 改动文件
| 文件 | 操作 |
|---|---|
| `order/repository/MyBatisOrderRepository.java` | `assembleAll` → `assembleWithBatchHistory` + 新增 `loadHistories` + `findByParticipant`/`findAll` 改调 + 删 `assembleAll` + import |

### 测试文件
| 文件 | 操作 |
|---|---|
| `order/repository/MyBatisOrderRepositoryTest.java` | **新增** batch loadHistory 验证用例 + 现有 `findByParticipant`/`findAll` 用例作回归护栏 |

### 不受影响
- `OrderRepository` 接口（不变）
- `findById`/`findByDemandId`/`findArbitrationPage`/`findHistoryPage`/`save`/`deleteById`/`count*`
- Service 层（调用方无感知）
- schema/Entity/Domain

## 5. 测试策略

### 5.1 回归护栏（不改，须全绿）
- `findByParticipant_matches_publisher_or_accepter`：验证返回 Order 含 history（现有用例）。
- `findAll_returns_all_orders_with_their_history`：验证返回 Order 含完整 history（现有用例，:138-152）。
- `status_history_is_persisted_and_loaded_in_chronological_order`：验证 findById 的 history 升序（单条 loadHistory 不变）。

### 5.2 新增用例
1. `findByParticipant_loads_history_in_batch`：插入 N order（各含多条 history）→ `findByParticipant` 返回所有 order 含完整 history（按 changedAt/id 升序），验证 batch 查询正确。
2. `findAll_loads_history_in_batch`：同上，`findAll`。
3. `findByParticipant_with_no_history_returns_empty_statusHistory`：插入无 history 的 order → 返回 Order `statusHistory` 为空 list（非 null）。
4. `assembleWithBatchHistory_empty_input`：空 entities 返回空 list（防御，可通过 findByParticipant(null) 间接测）。

## 6. 验收标准
1. `.\mvnw.cmd test`（JDK 21，workdir=backend）全绿，测试数 ≥ 207 + 新增（约 4 个）。
2. `MyBatisOrderRepository` 不再出现 `assembleAll`；`findByParticipant`/`findAll` 调 `assembleWithBatchHistory`。
3. `findByParticipant`/`findAll` 返回的 Order 含完整 `statusHistory`（按 changedAt/id 升序），与原逐条一致。
4. `findById`/`findByDemandId` 行为不变（仍用单条 `loadHistory`）。
5. Service 层无感知（调用方不变）。

## 7. 风险
| 风险 | 对策 |
|---|---|
| batch `in(order_id, ids)` 大列表性能 | 候选集有限（user 的 order）；`in` 列表大时分批（2C.1 不做，YAGNI） |
| `HashMap` groupBy 顺序 | 每个 order 的 `List` 保持插入序（`orderByAsc(changedAt).orderByAsc(id)` 保证），与 `loadHistory` 一致 |
| `getOrDefault(id, emptyList)` 与原 `loadHistory`（null 时 `Collections.emptyList()`）一致 | `loadHistory` null orderId 返回 `emptyList`；batch 同（orderId 在 ids 中无 log → `getOrDefault` 返回 `emptyList`）✓ |

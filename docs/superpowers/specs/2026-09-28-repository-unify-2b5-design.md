# 仓储层统一阶段 2B.5 设计（order listHistory SQL 下推）

- 日期：2026-09-28
- 状态：待评审（用户已授权直接执行）
- 分支：`refactor/optimization`（HEAD `6b57a0e`，2B.4 已完成，测试 194/194）
- 上游 spec：`docs/superpowers/specs/2026-09-28-repository-unify-2b-design.md`（2B 总体，本文件为其第 5 子阶段）
- 范围：`OrderRepository` 扩 `findHistoryPage`/`countHistory` + `MyBatisOrderRepository` 实现 + `OrderApplicationServiceImpl.listHistory` 下沉 SQL

## 1. 背景与现状（探索已核实）

### 1.1 现状定位（2B 总体 spec #9）

`OrderApplicationServiceImpl.listHistory`（`backend/src/main/java/com/campushub/backend/order/service/OrderApplicationServiceImpl.java:182-200`）当前：

```java
findActiveUser(operatorId);
if (query == null) { throw ... }
List<Order> sorted = orderRepository.findByParticipant(operatorId).stream()
    .sorted(Comparator.comparing(Order::getCreatedAt).reversed())
    .toList();
int page = query.pageQuery().page();
int size = query.pageQuery().size();
int fromIndex = Math.max(0, (page - 1) * size);
int toIndex = Math.min(sorted.size(), fromIndex + size);
List<OrderSummaryResponse> items = fromIndex >= sorted.size()
    ? List.of()
    : sorted.subList(fromIndex, toIndex).stream().map(OrderSummaryResponse::from).toList();
return new PageResponse<>(items, page, size, sorted.size());
```

即 `findByParticipant(operatorId)`（`eq(publisher_id) OR eq(accepter_id)` + 逐条 `loadHistory` N+1）+ 内存 `createdAt DESC` 排序 + `subList` 分页 → `OrderSummaryResponse.from`。无 SQL 级 `orderBy`/`LIMIT`/`count`。

### 1.2 与 2B.3c findArbitrationPage 的同（不调 loadHistory）

`listHistory` 用 `OrderSummaryResponse::from`，仅取 `id`/`demandId`/`publisherId`/`accepterId`/`status`/`createdAt`/`completedAt`（`OrderSummaryResponse.java:16-26`），**不依赖 `statusHistory`/`updatedAt`**。故 `findHistoryPage` 直接 `selectList(wrapper).map(e -> e.toDomain(Collections.emptyList()))`，**不调 `loadHistory`**，避免 `findByParticipant` 的 N+1（与 2B.3c `findArbitrationPage` 同模式）。

### 1.3 与 2B.4 review findPage 的同（OR 条件 + PageQuery）

- `OrderHistoryQuery`（`order.dto`，record `(PageQuery pageQuery)`，紧凑构造器 `pageQuery` null 兜底）——同 `ReviewQuery` 极简。
- 过滤 `eq(publisher_id) OR eq(accepter_id)`——同 `findByParticipant` 现有 wrapper，亦同 2B.4 `eq(target_id) OR eq(author_id)` 模式。
- `userId` 独立参数（`listHistory(Long operatorId, OrderHistoryQuery query)`，`operatorId` 即 `userId`）。

### 1.4 `findByParticipant` 保留

`findByParticipant` 调用方（grep 已确认）：
- `listHistory`（:188，本阶段下推）
- `autoCompleteOverdueOrders`（:204，用 `order.getStatusHistory()` 判断自动完成，**需 loadHistory**）

故 2B.5 仅下推 :188，`findByParticipant` 接口与实现**保留**（`autoCompleteOverdueOrders` 需 history，不能用 `findHistoryPage` 替代）。

### 1.5 `Comparator` import 连带删

`Comparator`（grep 已确认）仅 `listHistory`（:189 `Comparator.comparing(...).reversed()`）用 + import（:27）。2B.5 删 listHistory 的 `Comparator.comparing` 后，`Comparator` **无其他引用**，可连带删 import。

### 1.6 调用方契约

`listHistory` 调用方：`OrderController`（拿 `PageResponse<OrderSummaryResponse>` 返回前端）。2B.5 仅改 Service 内部，`PageResponse<OrderSummaryResponse>` 结构不变，Controller 与前端契约不动。签名 `(Long operatorId, OrderHistoryQuery query)` 不变。

## 2. 目标 / 非目标

### 目标
1. `OrderRepository` 新增 `findHistoryPage(Long userId, OrderHistoryQuery query)` + `countHistory(Long userId)`（风格 A，与 2B.4 一致），`findAll`/`findByParticipant`/`findArbitrationPage`/`countArbitration`/`count`/`countByStatus`/`findById`/`findByDemandId`/`save`/`deleteById` 保留。
2. `MyBatisOrderRepository` 实现：私有 `buildHistoryWrapper(userId)`（`eq(publisher_id) OR eq(accepter_id)`，供 `findHistoryPage`/`countHistory` 共用）；`findHistoryPage` 加 `orderByDesc(createdAt).orderByDesc(id)` + `LIMIT/OFFSET`，**不调 `loadHistory`**（`toDomain(Collections.emptyList())`）；`countHistory` 用 `selectCount`。
3. `OrderApplicationServiceImpl.listHistory` 改用 `findHistoryPage` + `countHistory`，删除 `findByParticipant` + 内存排序 + `subList` 分页。
4. 删除连带失效的 `Comparator` import。
5. 行为零回归：`PageResponse`/`OrderSummaryResponse` 字段与语义不变；现有 `listHistory` 用例断言不变且全绿。

### 非目标
- 不删 `findByParticipant`（1.4，`autoCompleteOverdueOrders` 需 history）。
- 不改 `OrderHistoryQuery`/`Order`/`OrderEntity`/`OrderSummaryResponse`/`OrderMapper`/schema。
- 不动 `OrderController`/前端契约。
- 不动 `accept`/`updateStatus`/`requestArbitration`/`getDetail`/`autoCompleteOverdueOrders`。
- 不修 N+1 的彻底批量化（`findHistoryPage` 避免逐条 `loadHistory` 是附带优化；`findByParticipant` 的 N+1 留 `autoCompleteOverdueOrders` 重构或 2C）。

## 3. 约束（继承 2B 总体 + 主 spec）
- 前端兼容契约：`PageResponse(items, page, size, total)`/`OrderSummaryResponse` 字段结构不变；`listHistory` 签名不变；API 行为不变。
- 测试全绿：基线 194/194（2B.4 后），每子阶段不回归。
- 工作分支 `refactor/optimization`。
- Repository 接口扩方法，`MyBatisOrderRepository` 实现；无 XML mapper，全 `BaseMapper` + `LambdaQueryWrapper`。
- H2 MySQL 兼容模式（测试）与 MySQL（生产）行为一致。

## 4. 设计

### 4.1 Repository 接口扩展（风格 A，与 2B.4 一致）

`OrderRepository.java` 新增两方法（其余保留）：

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

并在 import 区追加：

```java
import com.campushub.backend.order.dto.OrderHistoryQuery;
```

### 4.2 MyBatisOrderRepository 实现

新增私有 `buildHistoryWrapper(userId)`（纯过滤，供 `findHistoryPage`/`countHistory` 共用）。`findHistoryPage` 内 `orderByDesc(createdAt).orderByDesc(id)`（固定排序）+ **不调 `loadHistory`**。

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

### 4.3 过滤下沉映射

| # | 内存逻辑（现状） | SQL 下推 | 边界对齐 |
|---|---|---|---|
| 1 | `findByParticipant(operatorId)`（`eq(publisher_id) OR eq(accepter_id)`） | `and(w -> w.eq(publisher_id, uid).or().eq(accepter_id, uid))` → `WHERE (publisher_id = ? OR accepter_id = ?)` | 与 `findByParticipant` 现有 wrapper 等价；`userId == null` 由 `findHistoryPage` 入口守卫返回空 |

### 4.4 排序下沉映射

| 内存逻辑（现状） | SQL 下推 | NULL 处理 |
|---|---|---|
| `Comparator.comparing(Order::getCreatedAt).reversed()` | `ORDER BY created_at DESC, id DESC` | `created_at` 为 `DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP`（`schema-order.sql:17`），无 NULL 位置问题 |

**id tie-breaker**：内存原 `reversed()` 只反转 `createdAt`，无 `thenComparing(id)`，同 `createdAt` 行顺序未定义。2B.5 加 `orderByDesc(id)` tie-breaker（CodeRabbit fix 原则，与 2B.1-2B.4 一致）。

### 4.5 不调 `loadHistory`（附带 N+1 优化，同 2B.3c）

`findHistoryPage` 用 `e.toDomain(Collections.emptyList())`，返回 `Order` 含空 `statusHistory`。`OrderSummaryResponse.from` 不读 `statusHistory`，行为等价（同 2B.3c §4.5）。

### 4.6 Service 改造

`OrderApplicationServiceImpl.listHistory` 改为：

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

`findActiveUser` / null 校验顺序不变。`OrderSummaryResponse.from` 行为不变。

### 4.7 import 清理

删除（grep 确认仅 `listHistory` 用）：

```java
import java.util.Comparator;
```

保留：`Locale`（`parseStatus` 用）；`Order`/`OrderStatus`/`OrderSummaryResponse`/`OrderHistoryQuery`（新 `listHistory`/其他方法用）；`orderRepository` 字段。

新增：

```java
import com.campushub.backend.order.dto.OrderHistoryQuery;
```

> `OrderHistoryQuery` 已在 import（:20，现有 `listHistory` 签名用）——**无需新增**。仅删 `Comparator`。

### 4.8 `now` 一致性

history 查询无 `now`/时间边界依赖，`findHistoryPage`/`countHistory` 无 `now` 取值，无毫秒窗口问题。

## 5. 影响面

### 改动文件
| 文件 | 操作 |
|---|---|
| `order/repository/OrderRepository.java` | 加 `findHistoryPage(Long, OrderHistoryQuery)` + `countHistory(Long)` + import |
| `order/repository/MyBatisOrderRepository.java` | 实现 `findHistoryPage` + `countHistory` + 私有 `buildHistoryWrapper` + import |
| `order/service/OrderApplicationServiceImpl.java` | `listHistory` 改用新方法；删 `Comparator` import |

### 测试文件
| 文件 | 操作 |
|---|---|
| `order/repository/MyBatisOrderRepositoryTest.java` | **新增** `findHistoryPage`/`countHistory` 用例 |
| `order/service/OrderApplicationServiceImplTest.java` | **不改**（`listHistory` 用例作回归护栏） |

### 不受影响
- `OrderHistoryQuery`/`Order`/`OrderEntity`/`OrderSummaryResponse`/`OrderMapper`/schema
- `OrderController`/前端契约
- `accept`/`updateStatus`/`requestArbitration`/`getDetail`/`autoCompleteOverdueOrders`
- `findByParticipant`（`autoCompleteOverdueOrders` 用）/`findArbitrationPage`/`countArbitration`/`count`/`countByStatus`/`findAll`
- 索引（`idx_order_publisher`/`idx_order_accepter` 已覆盖 OR 查询）

## 6. 测试策略

### 6.1 回归护栏（不改，须全绿）
- `OrderApplicationServiceImplTest` 现有 `listHistory` 用例（验证 participant 命中 + createdAt DESC + 分页）。下推后 `WHERE (publisher_id = ? OR accepter_id = ?) ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?` 等价。

### 6.2 新增 `MyBatisOrderRepositoryTest` 用例

> 沿用现有 `newOrder(demandId, publisherId, accepterId)` 工厂（默认 status=ACCEPTED/createdAt=now）。

1. `findHistoryPage` 返回 publisher_id 或 accepter_id 命中（插入 publisher 命中 + accepter 命中 + 都不命中）。
2. `findHistoryPage` 排序 `created_at DESC + id DESC`（同 createdAt 验证 id 降序 tie-breaker）。
3. `findHistoryPage` 分页 `LIMIT/OFFSET`：插入 5 条，`PageQuery(1,2)`/`(2,2)`/`(3,2)` 返回 2/2/1。
4. `findHistoryPage` 返回 `Order` 含空 `statusHistory`（验证不调 loadHistory）。
5. `countHistory` 与 `findHistoryPage` 总数一致。
6. `findHistoryPage`/`countHistory` null 防御：`userId==null` 或 `query==null` 返回空/0。

> 现有用例保留不动。

## 7. 验收标准
1. `.\mvnw.cmd test`（`JAVA_HOME` 指向 JDK 21，workdir=backend）全绿，测试数 ≥ 194 + 新增（约 6 个）。
2. `OrderApplicationServiceImpl.listHistory` 不再出现 `orderRepository.findByParticipant`（在 listHistory 体内）、`Comparator`、`subList`。
3. `OrderRepository` 暴露 `findHistoryPage(Long, OrderHistoryQuery)` + `countHistory(Long)`；`MyBatisOrderRepository` 实现不调 `loadHistory`（用 `toDomain(Collections.emptyList())`）。
4. `PageResponse`/`OrderSummaryResponse` 字段不变；`listHistory` 签名不变；现有用例全绿。
5. `autoCompleteOverdueOrders` 行为不变（仍用 `findByParticipant` + history）。
6. `findByParticipant`/`findArbitrationPage`/`countArbitration` 保留。
7. Controller 及前端契约未触动。

## 8. 风险与对策
| 风险 | 对策 |
|---|---|
| `OR` 查询在 `publisher_id`/`accepter_id` 两索引间无法高效用索引 | MySQL/H2 支持 index merge；`idx_order_publisher`/`idx_order_accepter` 已建；数据量可接受 |
| 不调 `loadHistory` 致 `Order.statusHistory` 为空影响 `OrderSummaryResponse` | `OrderSummaryResponse.from` 不读 `statusHistory`（4.5）；测试 4 显式验证 |
| 加 `id DESC` tie-breaker 改变行为 | 内存原无 `thenComparing(id)`，同 createdAt 顺序未定义；加 id DESC 仅消除不确定性（4.4） |
| 删 `Comparator` import 误伤其他调用方 | grep 已确认仅 `listHistory` 用（1.5） |
| `last("LIMIT ... OFFSET ...")` 拼接 | 入参 `int`/`long`（`PageQuery` 校验），无注入风险 |

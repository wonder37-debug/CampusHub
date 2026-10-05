# 2C.2 设计（filterCandidateDemands 跨仓储 N+1 batch 修复）

- 日期：2026-10-04
- 状态：待评审（用户已授权直接执行）
- 分支：`refactor/2c`（HEAD `00b6fdb`，2C.1 已完成）
- 上游 spec：`docs/superpowers/specs/2026-10-04-repository-n-plus-1-2c-design.md`（2C 总体 §3.2 方案 B）
- 范围：`OrderRepository` 加 `findDemandIdsWithOrder` + `RecommendationApplicationServiceImpl.filterCandidateDemands` 改 batch

## 1. 现状

`RecommendationApplicationServiceImpl.filterCandidateDemands`（:139）当前（2B.6 后）：
```java
return demandRepository.findCandidatePage(userId, query).stream()
    .filter(demand -> orderRepository.findByDemandId(demand.getId()).isEmpty())  // ← 逐 demand N+1
    .toList();
```
每个候选 demand 一次 `findByDemandId` SQL（查 ord_order 是否存在 demand_id），N+1。

## 2. 设计（方案 B：batch 查询）

### 2.1 OrderRepository 加 `findDemandIdsWithOrder`

```java
/**
 * 查询给定 demand 集合中已有订单的 demand_id（用于推荐候选排除已被接单的 demand）。
 *
 * @param demandIds demand ID 集合，为 null/空时返回空集
 */
Set<Long> findDemandIdsWithOrder(Collection<Long> demandIds);
```

### 2.2 MyBatisOrderRepository 实现

```java
@Override
public Set<Long> findDemandIdsWithOrder(Collection<Long> demandIds) {
    if (demandIds == null || demandIds.isEmpty()) {
        return Set.of();
    }
    List<OrderEntity> entities = orderMapper.selectList(
        new LambdaQueryWrapper<OrderEntity>()
            .select(OrderEntity::getDemandId)
            .in(OrderEntity::getDemandId, demandIds));
    return entities.stream().map(OrderEntity::getDemandId).collect(Collectors.toSet());
}
```

> `.select(getDemandId)` 只查 demand_id 列（优化，不加载全部字段）。`in(demand_id, ids)` 一次查询替 N 次 `findByDemandId`。`Set<Long>` 去重（一个 demand 最多一个 order，但 set 语义清晰）。

### 2.3 Service 改造

```java
private List<Demand> filterCandidateDemands(Long userId, DemandQuery query) {
    List<Demand> candidates = demandRepository.findCandidatePage(userId, query);
    if (candidates.isEmpty()) {
        return List.of();
    }
    Set<Long> demandIdsWithOrder = orderRepository.findDemandIdsWithOrder(
        candidates.stream().map(Demand::getId).toList());
    return candidates.stream()
        .filter(demand -> !demandIdsWithOrder.contains(demand.getId()))
        .toList();
}
```

2 次 SQL（findCandidatePage + findDemandIdsWithOrder），无 N+1。

### 2.4 import

`RecommendationApplicationServiceImpl` 新增 `import java.util.Set;`（filterCandidateDemands 用）。`java.util.stream.Collectors`？现有 import 需确认（2B.6 删了 Stream import，但 Collectors 可能未用）。实际 `Collectors.toSet` 在 Repository 实现，Service 只用 `Set<Long>` 局部变量。

`MyBatisOrderRepository` 新增 `import java.util.Set;`（返回类型）+ `import java.util.stream.Collectors;`（`.collect(Collectors.toSet())`）。`.select(OrderEntity::getDemandId)` 需确认 LambdaQueryWrapper 的 select 方法签名。

## 3. 影响面

| 文件 | 操作 |
|---|---|
| `order/repository/OrderRepository.java` | 加 `findDemandIdsWithOrder(Collection<Long>)` + import（Collection/Set） |
| `order/repository/MyBatisOrderRepository.java` | 实现 + import（Set/Collectors） |
| `recommendation/service/RecommendationApplicationServiceImpl.java` | `filterCandidateDemands` 改 batch + import（Set） |

测试：`MyBatisOrderRepositoryTest` 新增 `findDemandIdsWithOrder` 用例；`RecommendationApplicationServiceImplTest` 现有 recommend 用例回归。

## 4. 测试策略

### 4.1 回归护栏
`RecommendationApplicationServiceImplTest` 现有 recommend/recommendDemandList 用例（验证候选排除已被接单 demand）。

### 4.2 新增
1. `findDemandIdsWithOrder` 返回有 order 的 demand_id（插入有 order + 无 order 的 demand，验证仅前者命中）。
2. `findDemandIdsWithOrder` 空/null 防御。
3. `findDemandIdsWithOrder` 去重（一个 demand 多 order？schema 唯一索引 uk_order_demand 保证一 demand 一 order，但 set 语义清晰）。

## 5. 验收
1. mvn test 全绿 ≥210（无新增测试数变化？2C.2 新增 2-3 用例）。
2. `filterCandidateDemands` 不再逐 demand `findByDemandId`（grep 确认）。
3. `OrderRepository.findDemandIdsWithOrder` 暴露。
4. recommend 行为不变。

## 6. 风险
- `.select(getDemandId)` LambdaQueryWrapper 列选择：MyBatis-Plus 支持，返回 OrderEntity 只 demandId 字段有值。若不支持用 selectList(全列) + map（性能略低但安全）。
- `in(demand_id, ids)` 大列表：候选集有限（PENDING demand），可接受。

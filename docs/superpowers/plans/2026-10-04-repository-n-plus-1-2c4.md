# 2C.4 设计 + 实施（buildAcceptedCategoryStats batch 修复）

- 日期：2026-10-04
- 状态：待评审（用户已授权直接执行）
- 分支：`refactor/2c`（HEAD `50985d6`，2C.3 已完成）
- 上游 spec：`docs/superpowers/specs/2026-10-04-repository-n-plus-1-2c-design.md`（2C 总体 §3.4）
- 范围：`DemandRepository` 加 `findAllById` + `RecommendationApplicationServiceImpl.buildAcceptedCategoryStats` 改 batch

## 1. 现状

`RecommendationApplicationServiceImpl.buildAcceptedCategoryStats`（:150-161）：
```java
for (Order order : orderRepository.findByParticipant(userId)) {
    if (!userId.equals(order.getAccepterId())) { continue; }
    demandRepository.findById(order.getDemandId()).ifPresent(demand ->  // ← N+1
        stats.merge(demand.getCategory().name(), 1L, Long::sum));
}
```
逐 user 接单的 order 调 `demandRepository.findById`（N+1）。`findByParticipant` 已 2C.1 批量化 loadHistory，但 `findById` demand 仍是 N+1。

## 2. 设计

### 2.1 DemandRepository 加 `findAllById`

```java
/**
 * 按主键集合批量查询需求（用于 Service 层避免逐条 findById N+1）。
 */
List<Demand> findAllById(Collection<Long> ids);
```

### 2.2 MyBatisDemandRepository 实现

```java
@Override
public List<Demand> findAllById(Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) {
        return List.of();
    }
    return demandMapper.selectBatchIds(ids).stream().map(DemandEntity::toDomain).toList();
}
```

> `selectBatchIds` 是 `BaseMapper` 方法（`SELECT * FROM ord_demand WHERE id IN (...)`）。import `java.util.Collection`。

### 2.3 Service 改造

```java
private Map<String, Long> buildAcceptedCategoryStats(Long userId) {
    List<Long> demandIds = orderRepository.findByParticipant(userId).stream()
        .filter(order -> userId.equals(order.getAccepterId()))
        .map(Order::getDemandId)
        .toList();
    if (demandIds.isEmpty()) {
        return Map.of();
    }
    Map<Long, Demand> demandById = demandRepository.findAllById(demandIds).stream()
        .collect(Collectors.toMap(Demand::getId, d -> d));
    Map<String, Long> stats = new HashMap<>();
    for (Long demandId : demandIds) {
        Demand demand = demandById.get(demandId);
        if (demand != null) {
            stats.merge(demand.getCategory().name(), 1L, Long::sum);
        }
    }
    return stats;
}
```

2 次 SQL（findByParticipant + findAllById），无 N+1。import `java.util.stream.Collectors`（若未 import）。

## 3. 影响面

| 文件 | 操作 |
|---|---|
| `demand/repository/DemandRepository.java` + `MyBatisDemandRepository.java` | 加 `findAllById(Collection<Long>)` + import |
| `recommendation/service/RecommendationApplicationServiceImpl.java` | `buildAcceptedCategoryStats` 改 batch + import |

测试：`MyBatisDemandRepositoryTest` 新增 `findAllById` 用例；`RecommendationApplicationServiceImplTest` recommend 用例回归。

## 4. 测试策略

### 4.1 回归
`RecommendationApplicationServiceImplTest` recommend/recommendDemandList 用例（验证 acceptedCategoryStats 影响 score）。

### 4.2 新增
1. `findAllById` 返回匹配的 demand（插入 3 demand，findAllById(2 ids) 返回 2）。
2. `findAllById` null/空防御。

## 5. 验收
1. mvn test 全绿 ≥220 + 2 新增 = 222。
2. `buildAcceptedCategoryStats` 不再逐 order `findById`。
3. `DemandRepository.findAllById` 暴露。
4. recommend 行为不变。

## 6. 风险
- `selectBatchIds` 大列表：user 接单数有限，可接受。
- `Collectors.toMap` 重复 key：demandId 唯一，无冲突。

---

# 实施计划

## Global Constraints
- JDK 21 / PowerShell 7+ / mvnw.cmd / workdir=backend / 基线 220 / 分支 refactor/2c HEAD 50985d6 / push PR #11。

## File Structure
| 文件 | 操作 |
|---|---|
| `demand/repository/DemandRepository.java` + `MyBatisDemandRepository.java` | 加 `findAllById` + import |
| `recommendation/service/RecommendationApplicationServiceImpl.java` | `buildAcceptedCategoryStats` 改 batch + import |
| `demand/repository/MyBatisDemandRepositoryTest.java` | 新增 2 用例 |

## Task 1（单 Task）

- [ ] **Step 1: 写失败测试**

`MyBatisDemandRepositoryTest` 末尾追加：
```java
    @Test
    void findAllById_returns_matching_demands() {
        Demand d1 = repository.save(newDemand("d1", DemandCategory.OTHER));
        Demand d2 = repository.save(newDemand("d2", DemandCategory.EXPRESS));
        repository.save(newDemand("d3", DemandCategory.OTHER));

        List<Demand> result = repository.findAllById(List.of(d1.getId(), d2.getId()));

        assertThat(result).extracting(Demand::getId).containsExactlyInAnyOrder(d1.getId(), d2.getId());
    }

    @Test
    void findAllById_handles_null_and_empty() {
        assertThat(repository.findAllById(null)).isEmpty();
        assertThat(repository.findAllById(List.of())).isEmpty();
    }
```

- [ ] **Step 2: 跑测试确认失败**

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```
```
.\mvnw.cmd test -Dtest=MyBatisDemandRepositoryTest
```
Expected: 编译失败，`findAllById` 不存在。

- [ ] **Step 3: 加 DemandRepository 接口 + MyBatis 实现**

参照 §2.1/§2.2。import `java.util.Collection`。

- [ ] **Step 4: 改 buildAcceptedCategoryStats**

参照 §2.3。import `java.util.stream.Collectors`（若未 import）。

- [ ] **Step 5: 跑 Repository + Service 测试**

```
.\mvnw.cmd test -Dtest=MyBatisDemandRepositoryTest,RecommendationApplicationServiceImplTest
```
Expected: PASS。

- [ ] **Step 6: 跑全量测试**

```
.\mvnw.cmd test
```
Expected: 全绿 ≥222。若截断写 tool-output，Grep 搜无 BUILD FAILURE/<<< FAILURE!/<<< ERROR!。

- [ ] **Step 7: Commit**

```
git add backend/src/main/java/com/campushub/backend/demand/repository/DemandRepository.java backend/src/main/java/com/campushub/backend/demand/repository/MyBatisDemandRepository.java backend/src/main/java/com/campushub/backend/recommendation/service/RecommendationApplicationServiceImpl.java backend/src/test/java/com/campushub/backend/demand/repository/MyBatisDemandRepositoryTest.java
```
```
git commit -m "perf(recommendation): batch findAllById to eliminate buildAcceptedCategoryStats N+1 findById"
```

## Self-Review
- §2.1 接口 → Step 3 ✓；§2.2 实现 → Step 3 ✓；§2.3 Service 改造 → Step 4 ✓
- §4.2 新增 2 用例 → Step 1 ✓；回归 → Step 5 ✓
- §5 验收 → Step 5-6 ✓

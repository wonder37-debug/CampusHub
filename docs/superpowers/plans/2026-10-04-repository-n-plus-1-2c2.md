# 2C.2（filterCandidateDemands 跨仓储 N+1 batch 修复）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** 把 `filterCandidateDemands` 的逐 demand `findByDemandId`（N+1）改为 batch `findDemandIdsWithOrder`（一次 `in(demand_id, ids)` 查询 + 内存 set 过滤），行为零回归。

**Architecture:** `OrderRepository` 加 `findDemandIdsWithOrder(Collection<Long>)`；`MyBatisOrderRepository` 用 `selectList(in + select(demandId))` + `Collectors.toSet()` 实现；Service `filterCandidateDemands` 改 batch。

**Spec:** `docs/superpowers/specs/2026-10-04-repository-n-plus-1-2c2-design.md`

## Global Constraints
- JDK 21：先 `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"`，再单独 `.\mvnw.cmd test`（workdir=backend）。
- PowerShell 7+：禁管道/分号/&&/&/重定向/反引号/$()；每条命令单独。
- 基线 210/210（2C.1 后）；2C.2 新增约 2 用例后应 212。
- 分支 refactor/2c，HEAD 00b6fdb。仅 commit，push 到 PR #11。

## File Structure
| 文件 | 操作 |
|---|---|
| `order/repository/OrderRepository.java` | 加 `findDemandIdsWithOrder(Collection<Long>)` + import |
| `order/repository/MyBatisOrderRepository.java` | 实现 + import |
| `recommendation/service/RecommendationApplicationServiceImpl.java` | `filterCandidateDemands` 改 batch + import |
| `order/repository/MyBatisOrderRepositoryTest.java` | 新增 2 用例 |

---

### Task 1: OrderRepository findDemandIdsWithOrder + Service batch 改造

- [ ] **Step 1: 写失败测试**

`MyBatisOrderRepositoryTest.java` 末尾追加：

```java
    @Test
    void findDemandIdsWithOrder_returns_ids_with_order() {
        repository.save(newOrder(9001L, 10L, 20L)); // demand 9001 有 order
        repository.save(newOrder(9002L, 11L, 21L)); // demand 9002 有 order
        // demand 9003 无 order（不入 demandIds 输入或入但无命中）

        Set<Long> result = repository.findDemandIdsWithOrder(List.of(9001L, 9002L, 9003L));

        assertThat(result).containsExactlyInAnyOrder(9001L, 9002L);
    }

    @Test
    void findDemandIdsWithOrder_handles_null_and_empty() {
        assertThat(repository.findDemandIdsWithOrder(null)).isEmpty();
        assertThat(repository.findDemandIdsWithOrder(List.of())).isEmpty();
    }
```

import：`import java.util.Set;`（若未 import）。

- [ ] **Step 2: 跑测试确认失败**

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```
```
.\mvnw.cmd test -Dtest=MyBatisOrderRepositoryTest
```
Expected: 编译失败，`findDemandIdsWithOrder` 不存在。

- [ ] **Step 3: 加 OrderRepository 接口方法**

`OrderRepository.java` 在 `countByStatus`/`findHistoryPage`/`countHistory` 之后追加：

```java
    /**
     * 查询给定 demand 集合中已有订单的 demand_id（用于推荐候选排除已被接单的 demand）。
     *
     * @param demandIds demand ID 集合，为 null/空时返回空集
     */
    Set<Long> findDemandIdsWithOrder(Collection<Long> demandIds);
```

import：`import java.util.Collection;` + `import java.util.Set;`（若未 import）。

- [ ] **Step 4: 实现 MyBatisOrderRepository**

`MyBatisOrderRepository.java` 在 `buildHistoryWrapper` 之后追加：

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

import：`import java.util.Set;` + `import java.util.stream.Collectors;`。

- [ ] **Step 5: 改 RecommendationApplicationServiceImpl.filterCandidateDemands**

替换 `filterCandidateDemands`（:138-142）为：

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

import：`import java.util.Set;`（若未 import）。

- [ ] **Step 6: 跑 Repository + Service 测试**

```
.\mvnw.cmd test -Dtest=MyBatisOrderRepositoryTest,RecommendationApplicationServiceImplTest
```
Expected: PASS（原有 + 新增 2 用例 + recommend 回归）。

- [ ] **Step 7: 跑全量测试**

```
.\mvnw.cmd test
```
Expected: 全绿 ≥212。若截断写 tool-output，Grep 搜无 BUILD FAILURE/<<< FAILURE!/<<< ERROR!。

- [ ] **Step 8: Commit**

```
git add backend/src/main/java/com/campushub/backend/order/repository/OrderRepository.java backend/src/main/java/com/campushub/backend/order/repository/MyBatisOrderRepository.java backend/src/main/java/com/campushub/backend/recommendation/service/RecommendationApplicationServiceImpl.java backend/src/test/java/com/campushub/backend/order/repository/MyBatisOrderRepositoryTest.java
```
```
git commit -m "perf(recommendation): batch findDemandIdsWithOrder to eliminate filterCandidateDemands N+1 cross-repo query"
```

## Self-Review
- §2.1 接口 → Step 3 ✓；§2.2 实现 → Step 4 ✓；§2.3 Service 改造 → Step 5 ✓
- §4.2 新增 2 用例 → Step 1 ✓；回归护栏 → Step 6 ✓
- §5 验收（mvn 全绿 / 不再逐 demand findByDemandId / findDemandIdsWithOrder 暴露 / recommend 不变）→ Step 6-7 ✓
- `.select(getDemandId)` LambdaQueryWrapper 列选择 MyBatis-Plus 3.5.7 支持 ✓；`Set.of()` 空防御 ✓

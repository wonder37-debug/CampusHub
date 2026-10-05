# 子项目2 收尾设计 + 实施（categoryDistribution SQL groupBy + 索引补充）

- 日期：2026-10-04
- 分支：`refactor/2-finalize`（基于 `github/main` `a98e422`，2C 已合并）
- 范围：① `DemandRepository.countByCategory` SQL groupBy 替 `getDashboard` 的 `demands.findAll` 内存 groupBy；② schema 补 `idx_order_created_at`/`idx_review_created_at` 加速 2C.3 范围查询

## 1. categoryDistribution SQL groupBy

### 1.1 现状
`AdminApplicationServiceImpl.getDashboard:279` 保留 `demandRepository.findAll()` for categoryDistribution（:288-289 内存 `Collectors.groupingBy(category, counting)`）。2C.3 妥协遗留——demands 表大时全表加载。

### 1.2 设计
`DemandRepository` 加 `Map<String, Long> countByCategory()`：
```java
Map<String, Long> countByCategory();
```

`MyBatisDemandRepository` 实现（`QueryWrapper` + `selectMaps` + `groupBy`，因 `count(*)` 非 Lambda 字段引用）：
```java
@Override
public Map<String, Long> countByCategory() {
    QueryWrapper<DemandEntity> wrapper = new QueryWrapper<>();
    wrapper.select("category, count(*) as cnt").groupBy("category");
    List<Map<String, Object>> maps = demandMapper.selectMaps(wrapper);
    Map<String, Long> result = new HashMap<>();
    for (Map<String, Object> m : maps) {
        String category = (String) m.get("category");
        Object cnt = m.get("cnt");
        if (category != null && cnt != null) {
            result.put(category, ((Number) cnt).longValue());
        }
    }
    return result;
}
```

> `demandMapper.selectMaps(QueryWrapper)` 是 `BaseMapper` 方法。`select("category, count(*) as cnt").groupBy("category")` 生成 `SELECT category, count(*) as cnt FROM ord_demand GROUP BY category`。H2 MySQL 模式支持。import `com.baomidou.mybatisplus.core.conditions.query.QueryWrapper` + `java.util.HashMap`/`Map`。

`getDashboard` 改用 `countByCategory`（去 `demands.findAll`）：
```java
Map<String, Long> categoryDistribution = demandRepository.countByCategory();
```
删 `List<Demand> demands = demandRepository.findAll();`（:279）。排序逻辑（:291-294）不变（仍对 categoryDistribution.entrySet 排序）。

### 1.3 影响面
- `demand/repository/DemandRepository.java` + `MyBatisDemandRepository.java`：加 `countByCategory` + import
- `admin/service/AdminApplicationServiceImpl.java`：`getDashboard` 改用 `countByCategory`，删 `demands.findAll` + `Demand`/`Collectors` import 失效检查
- 测试：`MyBatisDemandRepositoryTest` 新增 `countByCategory` 用例；`AdminApplicationServiceImplTest.shouldBuildDashboardStats` 回归

## 2. 索引补充（2C.3 范围查询加速）

### 2.1 现状
2C.3 的 `findActivePublisherIdsByDate`/`findActiveParticipantIdsByDate`/`findActiveAuthorIdsByDate`/`findActiveUserIdsByDate` 用 `ge(created_at, start).lt(created_at, end)` 范围查询。`ord_order`/`ord_review` 无 `created_at` 索引，范围查询全表 scan。

### 2.2 设计
| 文件 | 加索引 |
|---|---|
| `backend/src/test/resources/schema-order.sql` | `CREATE INDEX idx_order_created_at ON ord_order(created_at);` |
| `backend/src/test/resources/schema-review.sql` | `CREATE INDEX idx_review_created_at ON ord_review(created_at);` |
| `backend/src/main/resources/init_schema.sql` | `KEY idx_order_created_at (created_at)`（ord_order 表内）/ `CREATE INDEX idx_review_created_at ON ord_review(created_at)`（ord_review 表，与现有 `idx_review_author` 风格一致） |

> 仅加 `created_at` 索引（最常用范围列）。`updated_at`/`completed_at` 范围查询的 OR 条件可能 index merge，但 YAGNI——今日数据范围小，`created_at` 索引覆盖主要场景。生产性能评估后可补 `updated_at`/`completed_at`。

### 2.3 影响面
- 3 schema 文件加索引
- 无代码改动（索引透明）
- 测试自动用新 schema（@Sql 加载）

## 3. 验收
1. `mvn test` 全绿 ≥224 + 新增（countByCategory 约 2 用例）= 226。
2. `getDashboard` 不再 `demandRepository.findAll()`。
3. `DemandRepository.countByCategory` 暴露。
4. `shouldBuildDashboardStats` categoryDistribution 断言不变。
5. schema 3 文件加 `idx_*_created_at`。

## 4. 风险
- `QueryWrapper` 非 Lambda 引入：MyBatis-Plus 标准 API，不破坏风格（`selectMaps` 是 `BaseMapper` 方法）。
- `count(*) as cnt` H2/MySQL 兼容：H2 MySQL 模式支持 `count(*)` + `as` + `groupBy`。
- 索引补充无 migration 风险（新增索引，不改列/数据）。

---

# 实施计划

## Global Constraints
- JDK 21 / PowerShell 7+ / mvnw.cmd / workdir=backend / 基线 224 / 分支 refactor/2-finalize HEAD a98e422 / push 新 PR。

## File Structure
| 文件 | 操作 |
|---|---|
| `demand/repository/DemandRepository.java` + `MyBatisDemandRepository.java` | 加 `countByCategory` + import |
| `admin/service/AdminApplicationServiceImpl.java` | `getDashboard` 改用 `countByCategory` + 删 `demands.findAll` + import 清理 |
| `demand/repository/MyBatisDemandRepositoryTest.java` | 新增 2 用例 |
| `backend/src/test/resources/schema-order.sql` | 加 `idx_order_created_at` |
| `backend/src/test/resources/schema-review.sql` | 加 `idx_review_created_at` |
| `backend/src/main/resources/init_schema.sql` | 加 `idx_order_created_at` + `idx_review_created_at` |

## Task 1（单 Task，合并 2 优化）

- [ ] **Step 1: 写失败测试**

`MyBatisDemandRepositoryTest` 末尾追加：
```java
    @Test
    void countByCategory_returns_category_counts() {
        repository.save(newDemand("d1", DemandCategory.EXPRESS));
        repository.save(newDemand("d2", DemandCategory.EXPRESS));
        repository.save(newDemand("d3", DemandCategory.OTHER));

        Map<String, Long> result = repository.countByCategory();

        assertThat(result).hasSize(2);
        assertThat(result.get(DemandCategory.EXPRESS.name())).isEqualTo(2L);
        assertThat(result.get(DemandCategory.OTHER.name())).isEqualTo(1L);
    }

    @Test
    void countByCategory_returns_empty_when_no_demands() {
        assertThat(repository.countByCategory()).isEmpty();
    }
```
import：`import java.util.Map;`（若未 import）。

- [ ] **Step 2: 跑测试确认失败**

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```
```
.\mvnw.cmd test -Dtest=MyBatisDemandRepositoryTest
```
Expected: 编译失败，`countByCategory` 不存在。

- [ ] **Step 3: 加 DemandRepository 接口 + MyBatis 实现**

参照 §1.2。import `com.baomidou.mybatisplus.core.conditions.query.QueryWrapper` + `java.util.HashMap`/`Map`。

- [ ] **Step 4: 改 getDashboard**

参照 §1.2。`Map<String, Long> categoryDistribution = demandRepository.countByCategory();`，删 `List<Demand> demands = demandRepository.findAll();`。import 清理：`Demand`/`Collectors` 若失效则删（grep 确认——`Demand` 可能仍被其他方法用，`Collectors` 可能仍被排序用）。

- [ ] **Step 5: 加 schema 索引**

`schema-order.sql`（在 `idx_order_accepter` 后）：`CREATE INDEX idx_order_created_at ON ord_order(created_at);`
`schema-review.sql`（在 `idx_review_author` 后）：`CREATE INDEX idx_review_created_at ON ord_review(created_at);`
`init_schema.sql`：ord_order 表内加 `KEY idx_order_created_at (created_at),`（:189 后）；ord_review 表后加 `CREATE INDEX idx_review_created_at ON ord_review(created_at);`（:257 后）。

- [ ] **Step 6: 跑全量测试**

```
.\mvnw.cmd test
```
Expected: 全绿 ≥226。若截断写 tool-output，Grep 搜无 BUILD FAILURE/<<< FAILURE!/<<< ERROR!。

- [ ] **Step 7: Commit**

```
git add backend/src/main/java/com/campushub/backend/demand/repository/DemandRepository.java backend/src/main/java/com/campushub/backend/demand/repository/MyBatisDemandRepository.java backend/src/main/java/com/campushub/backend/admin/service/AdminApplicationServiceImpl.java backend/src/test/java/com/campushub/backend/demand/repository/MyBatisDemandRepositoryTest.java backend/src/test/resources/schema-order.sql backend/src/test/resources/schema-review.sql backend/src/main/resources/init_schema.sql
```
```
git commit -m "perf(admin): push categoryDistribution to SQL groupBy via countByCategory and add idx_*_created_at for dailyActiveUsers range query"
```

## Self-Review
- §1.2 countByCategory → Step 3 ✓；§1.2 getDashboard 改 → Step 4 ✓
- §2.2 schema 索引 → Step 5 ✓
- §3 验收 → Step 6 ✓

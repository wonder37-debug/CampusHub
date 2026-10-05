# 仓储层统一 2B.1（demand list SQL 下推）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `DemandApplicationServiceImpl.list` 的 `findAll` + 6 道内存过滤 + 内存排序 + `subList` 分页下沉为 `DemandRepository.findPage` / `count` 的 SQL（`LambdaQueryWrapper` 过滤 + `orderByDesc` + `LIMIT/OFFSET` + `selectCount`），行为零回归。

**Architecture:** Repository 接口新增 `findPage(DemandQuery)` + `count(DemandQuery)`（风格 A，分页与计数分离，返回 domain）；`MyBatisDemandRepository` 提取私有 `buildWrapper(query)`（可见性 `OR` + 6 过滤，供 `findPage`/`count` 共用）+ `applySort(wrapper, sort)`；Service `list` 改调新方法并删除 8 个仅服务于旧实现的私有方法及失效 import。

**Tech Stack:** Java 21 / Spring Boot 3.5.0 / MyBatis-Plus 3.5.7（`BaseMapper` + `LambdaQueryWrapper`，无 XML mapper）/ H2 MySQL 兼容模式（测试）/ MySQL（生产）/ JUnit 5 + AssertJ。

**Spec:** `docs/superpowers/specs/2026-09-28-repository-unify-2b1-design.md`（设计依据，与本 plan 一同阅读）

## Global Constraints

- **JDK 21**：本机默认 JDK 17，跑 Maven 前必须先设 `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"`（单条命令），再单独跑 `.\mvnw.cmd test -q`（workdir=`D:\workspace\sec-ii-2026\backend`）。
- **PowerShell 7+**：bash 工具禁止管道 `|`、分号 `;`、`&&`、`&`、重定向 `>`、反引号、`$()`；每条命令单独执行。
- **Maven Wrapper**：用 `.\mvnw.cmd`（非 `./mvnw`）。
- **前端契约不变**：`PageResponse(items, page, size, total)` / `DemandSummaryResponse` 字段结构不变；API 行为不变。
- **测试基线 125/125 不回归**：每 task 结束跑全量验证。
- **分支 `refactor/optimization`**：仅 SDD 的 commit step 可执行；不 push / 不 merge。
- **无 XML mapper**：全部 `BaseMapper` + `LambdaQueryWrapper`。
- **H2 MySQL 兼容模式**（测试）与 MySQL（生产）行为一致；LIKE 依赖默认 ci collation。

## File Structure

| 文件 | 责任 | 本 plan 操作 |
|---|---|---|
| `demand/repository/DemandRepository.java` | 仓储接口 | 加 `findPage` + `count`（`findAll`/`findByStatus` 保留） |
| `demand/repository/MyBatisDemandRepository.java` | MyBatis 实现 | 实现 `findPage` + `count` + 私有 `buildWrapper` + `applySort` |
| `demand/service/DemandApplicationServiceImpl.java` | 应用服务 | `list` 改用新方法；删 8 私有方法 + 失效 import |
| `demand/repository/MyBatisDemandRepositoryTest.java` | Repository 切片测试 | 新增 11 个 `findPage`/`count` 用例 + 1 个辅助工厂 |
| `demand/service/DemandApplicationServiceImplTest.java` | Service 集成测试 | **不改**（2 个 `list` 用例作回归护栏） |

---

### Task 1: DemandRepository 扩展 findPage/count + MyBatisDemandRepository 实现

**Files:**
- Modify: `backend/src/main/java/com/campushub/backend/demand/repository/DemandRepository.java`
- Modify: `backend/src/main/java/com/campushub/backend/demand/repository/MyBatisDemandRepository.java`
- Test: `backend/src/test/java/com/campushub/backend/demand/repository/MyBatisDemandRepositoryTest.java`

**Interfaces:**
- Consumes: `DemandQuery`（`demand.dto`，record，含 `q`/`category`/`campusZone`/`location`/`startTimeFrom`/`startTimeTo`/`sort`/`pageQuery`/`currentUserId`）、`DemandSort`（`demand.domain`，枚举 `TIME`/`DISTANCE`/`REWARD`/`RECOMMEND`）、`PageQuery`（`common.model`，record `(int page, int size)`，构造校验 `page>=1`、`1<=size<=100`）、`DemandEntity`（`@TableName("ord_demand")`，字段 `publisherId`/`title`/`description`/`category`/`campusZone`/`location`/`startTime`/`endTime`/`reward`/`status`/`createdAt` 等 String/LocalDateTime/BigDecimal 列）、`DemandMapper extends BaseMapper<DemandEntity>`（`selectList`/`selectCount`）。
- Produces: `DemandRepository.findPage(DemandQuery) -> List<Demand>`、`DemandRepository.count(DemandQuery) -> long`（供 Task 2 Service 调用）。

- [ ] **Step 1: 写失败测试（11 个 findPage/count 用例 + 1 个辅助工厂）**

在 `MyBatisDemandRepositoryTest.java` 末尾（`newDemand` 工厂方法之前）追加测试方法，并在 `newDemand` 之后追加 `newDemandWithCreated` 辅助工厂。

测试方法（追加到类体内）：

```java
    @Test
    void findPage_returns_all_visible_sorted_by_created_desc_with_default_time_sort() {
        Demand older = repository.save(newDemandWithCreated("旧需求", DemandCategory.OTHER, LocalDateTime.now().minusMinutes(10)));
        Demand newer = repository.save(newDemandWithCreated("新需求", DemandCategory.OTHER, LocalDateTime.now().minusMinutes(1)));

        DemandQuery query = new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(1, 20));

        List<Demand> page = repository.findPage(query);
        assertThat(page).hasSize(2);
        assertThat(page.get(0).getId()).isEqualTo(newer.getId());
        assertThat(repository.count(query)).isEqualTo(2L);
    }

    @Test
    void findPage_applies_limit_and_offset_for_pagination() {
        for (int i = 0; i < 5; i++) {
            repository.save(newDemandWithCreated("需求" + i, DemandCategory.OTHER, LocalDateTime.now().minusMinutes(5 - i)));
        }
        DemandQuery page1 = new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(1, 2));
        DemandQuery page2 = new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(2, 2));
        DemandQuery page3 = new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(3, 2));

        assertThat(repository.findPage(page1)).hasSize(2);
        assertThat(repository.findPage(page2)).hasSize(2);
        assertThat(repository.findPage(page3)).hasSize(1);
        assertThat(repository.count(page1)).isEqualTo(5L);
    }

    @Test
    void findPage_filters_by_keyword_on_title_or_description() {
        Demand d1 = repository.save(newDemand("取快递帮拿", DemandCategory.EXPRESS));
        Demand d2 = repository.save(newDemand("辅导高数", DemandCategory.STUDY_TUTORING));
        d2.setDescription("线代答疑辅导");
        repository.save(d2);
        Demand d3 = repository.save(newDemand("无关标题", DemandCategory.OTHER));
        d3.setDescription("无关描述");
        repository.save(d3);

        DemandQuery query = new DemandQuery("辅导", null, null, null, null, null, DemandSort.TIME, new PageQuery(1, 20));

        assertThat(repository.findPage(query)).extracting(Demand::getId).containsExactly(d2.getId());
        assertThat(repository.count(query)).isEqualTo(1L);
    }

    @Test
    void findPage_filters_by_category_with_case_insensitive_match() {
        Demand express = repository.save(newDemand("快递", DemandCategory.EXPRESS));
        repository.save(newDemand("辅导", DemandCategory.STUDY_TUTORING));

        DemandQuery query = new DemandQuery(null, "express", null, null, null, null, DemandSort.TIME, new PageQuery(1, 20));

        assertThat(repository.findPage(query)).extracting(Demand::getId).containsExactly(express.getId());
        assertThat(repository.count(query)).isEqualTo(1L);
    }

    @Test
    void findPage_filters_by_campus_zone_with_case_insensitive_match() {
        repository.save(newDemand("仙林", DemandCategory.OTHER));
        Demand gulou = repository.save(newDemand("鼓楼", DemandCategory.OTHER));
        gulou.setCampusZone(CampusZone.GULOU);
        repository.save(gulou);

        DemandQuery query = new DemandQuery(null, null, "gulou", null, null, null, DemandSort.TIME, new PageQuery(1, 20));

        assertThat(repository.findPage(query)).extracting(Demand::getId).containsExactly(gulou.getId());
    }

    @Test
    void findPage_filters_by_location_like() {
        Demand d1 = repository.save(newDemand("d1", DemandCategory.OTHER));
        d1.setLocation("仙林菜鸟驿站");
        repository.save(d1);
        Demand d2 = repository.save(newDemand("d2", DemandCategory.OTHER));
        d2.setLocation("鼓楼教学楼");
        repository.save(d2);

        DemandQuery query = new DemandQuery(null, null, null, "菜鸟", null, null, DemandSort.TIME, new PageQuery(1, 20));

        assertThat(repository.findPage(query)).extracting(Demand::getId).containsExactly(d1.getId());
    }

    @Test
    void findPage_filters_by_start_time_range_and_excludes_null_start_time_when_range_given() {
        Demand withStart = repository.save(newDemand("有开始", DemandCategory.OTHER));
        withStart.setStartTime(LocalDateTime.of(2026, 9, 28, 10, 0));
        repository.save(withStart);
        Demand noStart = repository.save(newDemand("无开始", DemandCategory.OTHER));

        LocalDateTime from = LocalDateTime.of(2026, 9, 28, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 9, 28, 23, 59);
        DemandQuery ranged = new DemandQuery(null, null, null, null, from, to, DemandSort.TIME, new PageQuery(1, 20));
        assertThat(repository.findPage(ranged)).extracting(Demand::getId).containsExactly(withStart.getId());

        DemandQuery all = new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(1, 20));
        assertThat(repository.findPage(all)).extracting(Demand::getId).contains(withStart.getId(), noStart.getId());
    }

    @Test
    void findPage_excludes_reviewing_and_expired_but_includes_own_when_current_user_id_present() {
        Demand pending = repository.save(newDemand("公开待接", DemandCategory.EXPRESS));
        Demand reviewing = repository.save(newDemand("审核中", DemandCategory.EXPRESS));
        reviewing.setStatus(DemandStatus.REVIEWING);
        repository.save(reviewing);
        Demand expired = repository.save(newDemand("已过期", DemandCategory.EXPRESS));
        expired.setEndTime(LocalDateTime.now().minusDays(1));
        repository.save(expired);

        DemandQuery publicQuery = new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(1, 20));
        List<Demand> publicPage = repository.findPage(publicQuery);
        assertThat(publicPage).extracting(Demand::getId).contains(pending.getId());
        assertThat(publicPage).extracting(Demand::getId).doesNotContain(reviewing.getId(), expired.getId());

        DemandQuery ownQuery = new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(1, 20), pending.getPublisherId());
        List<Demand> ownPage = repository.findPage(ownQuery);
        assertThat(ownPage).extracting(Demand::getId).contains(reviewing.getId(), expired.getId());
    }

    @Test
    void findPage_sorts_by_reward_desc_then_created_desc() {
        Demand high = repository.save(newDemandWithCreated("高报酬", DemandCategory.OTHER, LocalDateTime.now().minusMinutes(5)));
        high.setReward(new BigDecimal("50.00"));
        repository.save(high);
        Demand low = repository.save(newDemandWithCreated("低报酬", DemandCategory.OTHER, LocalDateTime.now().minusMinutes(1)));
        low.setReward(new BigDecimal("10.00"));
        repository.save(low);
        Demand zero = repository.save(newDemandWithCreated("零报酬", DemandCategory.OTHER, LocalDateTime.now().minusMinutes(10)));
        zero.setReward(BigDecimal.ZERO);
        repository.save(zero);

        DemandQuery query = new DemandQuery(null, null, null, null, null, null, DemandSort.REWARD, new PageQuery(1, 20));

        assertThat(repository.findPage(query)).extracting(Demand::getId)
            .containsExactly(high.getId(), low.getId(), zero.getId());
    }

    @Test
    void findPage_recommend_sort_equals_created_at_desc() {
        Demand older = repository.save(newDemandWithCreated("旧", DemandCategory.OTHER, LocalDateTime.now().minusMinutes(10)));
        Demand newer = repository.save(newDemandWithCreated("新", DemandCategory.OTHER, LocalDateTime.now().minusMinutes(1)));

        DemandQuery query = new DemandQuery(null, null, null, null, null, null, DemandSort.RECOMMEND, new PageQuery(1, 20));

        assertThat(repository.findPage(query)).extracting(Demand::getId).containsExactly(newer.getId(), older.getId());
    }

    @Test
    void count_matches_findPage_total_for_filtered_query() {
        repository.save(newDemand("快递一", DemandCategory.EXPRESS));
        repository.save(newDemand("辅导", DemandCategory.STUDY_TUTORING));
        repository.save(newDemand("快递二", DemandCategory.EXPRESS));

        DemandQuery query = new DemandQuery(null, "EXPRESS", null, null, null, null, DemandSort.TIME, new PageQuery(1, 20));

        assertThat(repository.count(query)).isEqualTo(2L);
        assertThat(repository.findPage(query)).hasSize(2);
    }

    @Test
    void findPage_and_count_return_empty_when_query_null() {
        assertThat(repository.findPage(null)).isEmpty();
        assertThat(repository.count(null)).isEqualTo(0L);
    }
```

辅助工厂（追加到 `newDemand` 之后）：

```java
    private static Demand newDemandWithCreated(String title, DemandCategory category, LocalDateTime createdAt) {
        Demand demand = newDemand(title, category);
        demand.setCreatedAt(createdAt);
        return demand;
    }
```

补充 import（在现有 import 区追加，保持顺序）：

```java
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.demand.dto.DemandQuery;
import com.campushub.backend.demand.domain.DemandSort;
```

- [ ] **Step 2: 跑测试确认失败（编译错）**

设置 JDK 21（单独一条命令）：

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```

跑 Repository 测试（workdir=`D:\workspace\sec-ii-2026\backend`，单独一条）：

```
.\mvnw.cmd test -q -Dtest=MyBatisDemandRepositoryTest
```

Expected: 编译失败，`DemandRepository` 无 `findPage`/`count` 方法。

- [ ] **Step 3: 加 DemandRepository 接口方法**

`DemandRepository.java` 在 `findByStatus` 之后追加（`findAll`/`findByStatus` 保留不动）：

```java
    /**
     * 按查询条件分页查询需求（过滤 + 排序 + LIMIT/OFFSET 下推 SQL）。
     *
     * @param query 查询条件，为 null 时返回空列表
     */
    List<Demand> findPage(DemandQuery query);

    /**
     * 按查询条件统计匹配的需求总数（过滤下推 SQL，用于分页 total）。
     *
     * @param query 查询条件，为 null 时返回 0
     */
    long count(DemandQuery query);
```

并在 import 区追加：

```java
import com.campushub.backend.demand.dto.DemandQuery;
```

- [ ] **Step 4: 实现 MyBatisDemandRepository 的 findPage/count/buildWrapper/applySort**

在 `MyBatisDemandRepository.java` 的 `findByStatus` 方法之后追加实现：

```java
    @Override
    public List<Demand> findPage(DemandQuery query) {
        if (query == null) {
            return List.of();
        }
        LambdaQueryWrapper<DemandEntity> wrapper = buildWrapper(query);
        applySort(wrapper, query.sort());
        int size = query.pageQuery().size();
        int offset = (query.pageQuery().page() - 1) * size;
        wrapper.last("LIMIT " + size + " OFFSET " + offset);
        return demandMapper.selectList(wrapper).stream().map(DemandEntity::toDomain).toList();
    }

    @Override
    public long count(DemandQuery query) {
        if (query == null) {
            return 0L;
        }
        return demandMapper.selectCount(buildWrapper(query));
    }

    private LambdaQueryWrapper<DemandEntity> buildWrapper(DemandQuery query) {
        LocalDateTime now = LocalDateTime.now();
        Long currentUserId = query.currentUserId();
        LambdaQueryWrapper<DemandEntity> wrapper = new LambdaQueryWrapper<>();
        List<String> publicStatuses = List.of(
            DemandStatus.PENDING.name(),
            DemandStatus.IN_PROGRESS.name(),
            DemandStatus.COMPLETED.name());
        wrapper.and(w -> {
            w.and(v -> v.isNull(DemandEntity::getEndTime).or().ge(DemandEntity::getEndTime, now))
                .in(DemandEntity::getStatus, publicStatuses);
            if (currentUserId != null) {
                w.or(o -> o.eq(DemandEntity::getPublisherId, currentUserId));
            }
        });
        String q = query.q();
        if (q != null && !q.isBlank()) {
            String keyword = q.trim();
            wrapper.and(w -> w.like(DemandEntity::getTitle, keyword).or().like(DemandEntity::getDescription, keyword));
        }
        String category = query.category();
        if (category != null && !category.isBlank()) {
            wrapper.eq(DemandEntity::getCategory, category.trim().toUpperCase(Locale.ROOT));
        }
        String zone = query.campusZone();
        if (zone != null && !zone.isBlank()) {
            wrapper.eq(DemandEntity::getCampusZone, zone.trim().toUpperCase(Locale.ROOT));
        }
        String loc = query.location();
        if (loc != null && !loc.isBlank()) {
            wrapper.like(DemandEntity::getLocation, loc.trim());
        }
        LocalDateTime from = query.startTimeFrom();
        LocalDateTime to = query.startTimeTo();
        if (from != null || to != null) {
            wrapper.isNotNull(DemandEntity::getStartTime);
            if (from != null) {
                wrapper.ge(DemandEntity::getStartTime, from);
            }
            if (to != null) {
                wrapper.le(DemandEntity::getStartTime, to);
            }
        }
        return wrapper;
    }

    private void applySort(LambdaQueryWrapper<DemandEntity> wrapper, DemandSort sort) {
        DemandSort resolved = sort == null ? DemandSort.TIME : sort;
        switch (resolved) {
            case REWARD -> wrapper.orderByDesc(DemandEntity::getReward)
                                  .orderByDesc(DemandEntity::getCreatedAt);
            case TIME, DISTANCE, RECOMMEND -> wrapper.orderByDesc(DemandEntity::getCreatedAt);
        }
    }
```

在 import 区追加（保持字母序，与现有风格一致）：

```java
import com.campushub.backend.demand.domain.DemandSort;
import com.campushub.backend.demand.dto.DemandQuery;
import java.time.LocalDateTime;
import java.util.Locale;
```

- [ ] **Step 5: 跑 Repository 测试确认通过**

确保 `$env:JAVA_HOME` 已设（若新 session 先执行 Step 2 的设置命令）。workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test -q -Dtest=MyBatisDemandRepositoryTest
```

Expected: PASS，`MyBatisDemandRepositoryTest` 全部用例（含原有 11 + 新增 11 = 22 个）绿。

- [ ] **Step 6: 跑全量测试确认无回归**

workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test -q
```

Expected: 全绿，测试数 ≥ 125 + 11（新增）= 136。`DemandApplicationServiceImplTest` 此刻仍用旧 `list`（调 `findAll`），`findAll` 保留故仍绿。

- [ ] **Step 7: Commit**

workdir=`D:\workspace\sec-ii-2026`：

```
git add backend/src/main/java/com/campushub/backend/demand/repository/DemandRepository.java backend/src/main/java/com/campushub/backend/demand/repository/MyBatisDemandRepository.java backend/src/test/java/com/campushub/backend/demand/repository/MyBatisDemandRepositoryTest.java
```

```
git commit -m "refactor(demand): push list query down to SQL via findPage/count in DemandRepository"
```

---

### Task 2: DemandApplicationServiceImpl.list 改用 findPage/count + 清理私有方法

**Files:**
- Modify: `backend/src/main/java/com/campushub/backend/demand/service/DemandApplicationServiceImpl.java:121-148`（`list` 方法体）+ `:402-456`（删 8 私有方法）+ import 区
- Test: `backend/src/test/java/com/campushub/backend/demand/service/DemandApplicationServiceImplTest.java`（不改，作回归护栏）

**Interfaces:**
- Consumes: Task 1 的 `DemandRepository.findPage(DemandQuery) -> List<Demand>` + `DemandRepository.count(DemandQuery) -> long`。
- Produces: `DemandApplicationService.list(DemandQuery) -> PageResponse<DemandSummaryResponse>`（签名不变，行为等价但走 SQL 下推）。

- [ ] **Step 1: 改 list 方法体**

替换 `DemandApplicationServiceImpl.java:121-148` 的 `list` 方法为：

```java
    @Override
    public PageResponse<DemandSummaryResponse> list(DemandQuery query) {
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "demand query must not be null");
        }
        List<Demand> demands = demandRepository.findPage(query);
        List<DemandSummaryResponse> items = demands.stream()
            .map(DemandSummaryResponse::from)
            .toList();
        long total = demandRepository.count(query);
        int page = query.pageQuery().page();
        int size = query.pageQuery().size();
        return new PageResponse<>(items, page, size, total);
    }
```

- [ ] **Step 2: 删除 8 个仅服务于旧 list 的私有方法**

删除 `DemandApplicationServiceImpl.java` 中的以下私有方法（连续位于 `:402-456`，整体删除）：

- `isPubliclyVisible(Demand, LocalDateTime)`
- `isExpired(Demand, LocalDateTime)`
- `matchesKeyword(Demand, String)`
- `matchesCategory(Demand, String)`
- `matchesCampusZone(Demand, String)`
- `matchesLocation(Demand, String)`
- `matchesStartTimeRange(Demand, LocalDateTime, LocalDateTime)`
- `resolveComparator(DemandSort)`

> 删除前 grep 确认这些方法仅被旧 `list` 调用：`rg -n "isPubliclyVisible|isExpired|matchesKeyword|matchesCategory|matchesCampusZone|matchesLocation|matchesStartTimeRange|resolveComparator" backend/src/main/java/com/campushub/backend/demand/service/DemandApplicationServiceImpl.java`，命中应仅旧 `list` 体内与各方法定义。

- [ ] **Step 3: 清理失效 import**

从 `DemandApplicationServiceImpl.java` import 区删除（编译通过为准绳，逐项确认无其他引用）：

```java
import java.util.Comparator;
import java.util.stream.Stream;
import com.campushub.backend.demand.domain.DemandSort;
```

保留（仍被其他方法使用）：`java.util.Locale`（`parseCampusZone`）、`java.util.UUID`（`generateAnonymousCode`）、`java.util.List`（`list` 用 `List<Demand>`）、`java.time.LocalDateTime`（`publish`/`update`/`withdraw`）、`DemandStatus`（`publish`/`withdraw`）、`DemandCategory`/`CampusZone`（`parseCategory`/`parseCampusZone`）。

- [ ] **Step 4: 跑 Service 测试确认 list 回归绿**

确保 `$env:JAVA_HOME` 已设。workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test -q -Dtest=DemandApplicationServiceImplTest
```

Expected: PASS，含 `shouldFilterDemandListByCategoryAndCampusZone`、`shouldIncludeOwnReviewingAndCancelledDemandsInList` 两个 `list` 用例及 `publish`/`update` 等全绿。

- [ ] **Step 5: 跑全量测试确认不回归**

workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test -q
```

Expected: 全绿，测试数 = Task 1 Step 6 的数量（本 task 不新增测试，仅重构）。`FrontendIntegrationFlowTest`（若存在）含 demand list 调用路径亦须绿。

- [ ] **Step 6: Commit**

workdir=`D:\workspace\sec-ii-2026`：

```
git add backend/src/main/java/com/campushub/backend/demand/service/DemandApplicationServiceImpl.java
```

```
git commit -m "refactor(demand): switch DemandApplicationServiceImpl.list to SQL-pushed findPage/count and drop in-memory filters"
```

---

## Self-Review

**1. Spec 覆盖**
- §4.1 接口扩展（findPage/count 风格 A）→ Task 1 Step 3。✓
- §4.2 MyBatisDemandRepository 实现（buildWrapper/applySort/findPage/count）→ Task 1 Step 4。✓
- §4.3 过滤下沉映射（6 过滤）→ Task 1 Step 4 `buildWrapper`（可见性 OR / keyword like / category eq+upper / campusZone eq+upper / location like / startTime isNotNull+ge+le）。✓
- §4.4 可见性 OR 构造 → Task 1 Step 4 `buildWrapper` 可见性片段。✓
- §4.5 排序下沉（REWARD/TIME/DISTANCE/RECOMMEND）→ Task 1 Step 4 `applySort`。✓
- §4.6 Service 改造 → Task 2 Step 1。✓
- §4.7 私有方法 + import 清理 → Task 2 Step 2-3。✓
- §4.8 now 一致性 → 不需代码动作，设计妥协已在 spec 记。✓
- §5 影响面文件清单 → File Structure 表。✓
- §6 测试策略（回归护栏 2 + 新增 11）→ Task 1 Step 1（11 新增）+ Task 2 Step 4（2 护卫）。✓
- §7 验收（mvn 全绿 / list 不再 findAll / findPage+count 暴露 / 契约不变 / RECOMMEND 不变）→ Task 1 Step 5-6 + Task 2 Step 4-5。✓

**2. 占位符扫描**：无 TBD/TODO/"add appropriate"等；所有代码块为可直接落地实现。✓

**3. 类型一致性**：`findPage(DemandQuery) -> List<Demand>` 与 `count(DemandQuery) -> long` 在 Task 1（接口+实现）与 Task 2（Service 调用）签名一致；`DemandQuery`/`DemandSort`/`PageQuery`/`DemandEntity` 字段名（`publisherId`/`endTime`/`status`/`startTime`/`category`/`campusZone`/`location`/`title`/`description`/`reward`/`createdAt`）与 spec 及源码 `@TableField` 一致。✓

# 仓储层统一 2B.6（recommendation filterCandidateDemands SQL 下推）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `RecommendationApplicationServiceImpl.filterCandidateDemands` 的 `findAll` + 7 道内存过滤（status PENDING / 排除自己 publisherId / keyword / category / campusZone / location / startTime）下沉为 `DemandRepository.findCandidatePage` 的 SQL（`LambdaQueryWrapper`），保留 `findByDemandId.isEmpty()` N+1 在 Service（2C 修），删除 5 个 `matches*` 私有方法，行为零回归。

**Architecture:** `DemandRepository` 新增 `findCandidatePage(Long userId, DemandQuery query)`（无分页/无 count，返回候选 List）；`MyBatisDemandRepository` 提取私有 `buildCandidateWrapper(userId, query)`（`eq(status,PENDING)` + `ne(publisher_id,userId)` + keyword/category/campusZone/location/startTime 过滤）；Service `filterCandidateDemands` 改调新方法 + 保留 N+1，删 5 matches* + `Locale` import（连带）。

**Tech Stack:** Java 21 / Spring Boot 3.5.0 / MyBatis-Plus 3.5.7（`BaseMapper` + `LambdaQueryWrapper`，无 XML mapper）/ H2 MySQL 兼容模式（测试）/ MySQL（生产）/ JUnit 5 + AssertJ。

**Spec:** `docs/superpowers/specs/2026-09-28-repository-unify-2b6-design.md`（设计依据，与本 plan 一同阅读）

## Global Constraints

- **JDK 21**：本机默认 JDK 17，跑 Maven 前必须先设 `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"`（单条命令），再单独跑 `.\mvnw.cmd test`（workdir=`D:\workspace\sec-ii-2026\backend`）。
- **PowerShell 7+**：bash 工具禁止管道 `|`、分号 `;`、`&&`、`&`、重定向 `>`、反引号、`$()`；每条命令单独执行。
- **Maven Wrapper**：用 `.\mvnw.cmd`（非 `./mvnw`），workdir=`D:\workspace\sec-ii-2026\backend`。
- **前端契约不变**：`PageResponse` / `RecommendationItemResponse` / `DemandSummaryResponse` 字段结构不变；API 行为不变。
- **测试基线 200/200 不回归**（2B.5 后）：每 task 结束跑全量验证。
- **分支 `refactor/optimization`**：仅 SDD 的 commit step 可执行；push 到 PR #10 延续授权模式；不 merge。
- **无 XML mapper**：全部 `BaseMapper` + `LambdaQueryWrapper`。
- **H2 MySQL 兼容模式**（测试）与 MySQL（生产）行为一致。

## File Structure

| 文件 | 责任 | 本 plan 操作 |
|---|---|---|
| `demand/repository/DemandRepository.java` | 仓储接口 | 加 `findCandidatePage(Long, DemandQuery)`（其余保留） |
| `demand/repository/MyBatisDemandRepository.java` | MyBatis 实现 | 实现 `findCandidatePage` + 私有 `buildCandidateWrapper` |
| `recommendation/service/RecommendationApplicationServiceImpl.java` | 应用服务 | `filterCandidateDemands` 改用新方法 + 保留 N+1；删 5 matches* + `Locale` import |
| `demand/repository/MyBatisDemandRepositoryTest.java` | 切片测试 | 新增 7 个 `findCandidatePage` 用例 |

---

### Task 1: DemandRepository 扩展 findCandidatePage + MyBatisDemandRepository 实现

**Files:**
- Modify: `backend/src/main/java/com/campushub/backend/demand/repository/DemandRepository.java`
- Modify: `backend/src/main/java/com/campushub/backend/demand/repository/MyBatisDemandRepository.java`
- Test: `backend/src/test/java/com/campushub/backend/demand/repository/MyBatisDemandRepositoryTest.java`

**Interfaces:**
- Consumes: `DemandQuery`（`demand.dto`，含 `q`/`category`/`campusZone`/`location`/`startTimeFrom`/`startTimeTo`/`sort`/`pageQuery`/`currentUserId`）、`DemandEntity`（字段 `status`/`publisherId`/`title`/`description`/`category`/`campusZone`/`location`/`startTime`）、`DemandStatus`（`PENDING.name()`）、`DemandMapper`。
- Produces: `DemandRepository.findCandidatePage(Long userId, DemandQuery) -> List<Demand>`（无分页，候选集，供 Task 2 Service 调用）。

- [ ] **Step 1: 写失败测试（7 个 findCandidatePage 用例）**

在 `MyBatisDemandRepositoryTest.java` 类体内末尾（工厂之前）追加测试方法。

> 沿用 `newDemand(title, category)`（默认 `status=PENDING`）/`newDemandWithCreated`。造非 PENDING 需 `setStatus`+save。`findCandidatePage` 无 orderBy，测试用 `containsExactlyInAnyOrder`/`contains`（不关心顺序）。

测试方法（追加到类体内）：

```java
    @Test
    void findCandidatePage_returns_only_pending_demands() {
        Demand pending = repository.save(newDemand("待接", DemandCategory.OTHER));
        Demand inProgress = repository.save(newDemand("进行中", DemandCategory.OTHER));
        inProgress.setStatus(DemandStatus.IN_PROGRESS);
        repository.save(inProgress);
        Demand completed = repository.save(newDemand("已完成", DemandCategory.OTHER));
        completed.setStatus(DemandStatus.COMPLETED);
        repository.save(completed);

        List<Demand> candidates = repository.findCandidatePage(10L, new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(1, 20)));

        assertThat(candidates).extracting(Demand::getId).containsExactly(pending.getId());
    }

    @Test
    void findCandidatePage_excludes_own_demands() {
        Demand own = repository.save(newDemand("自己的", DemandCategory.OTHER));
        own.setPublisherId(10L);
        repository.save(own);
        Demand others = repository.save(newDemand("他人的", DemandCategory.OTHER));
        others.setPublisherId(20L);
        repository.save(others);

        List<Demand> candidates = repository.findCandidatePage(10L, new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(1, 20)));

        assertThat(candidates).extracting(Demand::getId).containsExactly(others.getId());
    }

    @Test
    void findCandidatePage_filters_by_keyword() {
        Demand match = repository.save(newDemand("取快递", DemandCategory.EXPRESS));
        Demand noMatch = repository.save(newDemand("辅导高数", DemandCategory.STUDY_TUTORING));

        List<Demand> candidates = repository.findCandidatePage(10L, new DemandQuery("快递", null, null, null, null, null, DemandSort.TIME, new PageQuery(1, 20)));

        assertThat(candidates).extracting(Demand::getId).containsExactly(match.getId());
    }

    @Test
    void findCandidatePage_filters_by_category_campus_zone_and_location() {
        Demand match = repository.save(newDemand("快递", DemandCategory.EXPRESS));
        match.setLocation("仙林菜鸟驿站");
        repository.save(match);
        Demand otherCategory = repository.save(newDemand("快递", DemandCategory.OTHER));
        Demand otherZone = repository.save(newDemand("快递", DemandCategory.EXPRESS));
        otherZone.setCampusZone(CampusZone.GULOU);
        repository.save(otherZone);
        Demand otherLocation = repository.save(newDemand("快递", DemandCategory.EXPRESS));
        otherLocation.setLocation("鼓楼教学楼");
        repository.save(otherLocation);

        List<Demand> candidates = repository.findCandidatePage(10L, new DemandQuery(null, "EXPRESS", "XIANLIN", "菜鸟", null, null, DemandSort.TIME, new PageQuery(1, 20)));

        assertThat(candidates).extracting(Demand::getId).containsExactly(match.getId());
    }

    @Test
    void findCandidatePage_filters_by_start_time_range_excluding_null_start() {
        Demand withStart = repository.save(newDemand("有开始", DemandCategory.OTHER));
        withStart.setStartTime(LocalDateTime.of(2026, 9, 28, 10, 0));
        repository.save(withStart);
        Demand noStart = repository.save(newDemand("无开始", DemandCategory.OTHER));

        LocalDateTime from = LocalDateTime.of(2026, 9, 28, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 9, 28, 23, 59);
        List<Demand> ranged = repository.findCandidatePage(10L, new DemandQuery(null, null, null, null, from, to, DemandSort.TIME, new PageQuery(1, 20)));
        assertThat(ranged).extracting(Demand::getId).containsExactly(withStart.getId());

        List<Demand> all = repository.findCandidatePage(10L, new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(1, 20)));
        assertThat(all).extracting(Demand::getId).contains(withStart.getId(), noStart.getId());
    }

    @Test
    void findCandidatePage_combines_status_publisher_keyword_category() {
        Demand match = repository.save(newDemand("快递帮拿", DemandCategory.EXPRESS));
        match.setPublisherId(20L);
        repository.save(match);
        Demand own = repository.save(newDemand("快递帮拿", DemandCategory.EXPRESS));
        own.setPublisherId(10L);
        repository.save(own);
        Demand otherCategory = repository.save(newDemand("快递帮拿", DemandCategory.OTHER));
        otherCategory.setPublisherId(20L);
        repository.save(otherCategory);
        Demand noKeyword = repository.save(newDemand("无关", DemandCategory.EXPRESS));
        noKeyword.setPublisherId(20L);
        repository.save(noKeyword);

        List<Demand> candidates = repository.findCandidatePage(10L, new DemandQuery("快递", "EXPRESS", null, null, null, null, DemandSort.TIME, new PageQuery(1, 20)));

        assertThat(candidates).extracting(Demand::getId).containsExactly(match.getId());
    }

    @Test
    void findCandidatePage_returns_empty_when_query_null() {
        assertThat(repository.findCandidatePage(10L, null)).isEmpty();
    }
```

> `DemandSort`/`DemandStatus`/`DemandCategory`/`CampusZone`/`PageQuery`/`LocalDateTime`/`DemandQuery` 已 import（2B.1/2B.3b）。

- [ ] **Step 2: 跑测试确认失败（编译错）**

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```

```
.\mvnw.cmd test -Dtest=MyBatisDemandRepositoryTest
```

Expected: 编译失败，`DemandRepository` 无 `findCandidatePage` 方法。

- [ ] **Step 3: 加 DemandRepository 接口方法**

`DemandRepository.java` 在 `countReview`/`countAll`/`countByStatus` 之后追加（其余保留）：

```java
    /**
     * 按推荐候选条件查询需求（status=PENDING + 排除自己 + keyword/category/campusZone/location/startTime 过滤下推 SQL）。
     *
     * <p>不分页（返回全部候选），不排序（Service 层做 score 排序）；不加载跨仓储的 order 存在性（留 Service N+1，2C 修）。</p>
     *
     * @param userId 推荐目标用户 ID（排除自己发的 demand），为 null 时不加 publisher_id 条件
     * @param query 查询条件，为 null 时返回空列表
     */
    List<Demand> findCandidatePage(Long userId, DemandQuery query);
```

> `DemandQuery` 已 import（2B.1）。无需新增 import。

- [ ] **Step 4: 实现 MyBatisDemandRepository 的 findCandidatePage/buildCandidateWrapper**

在 `MyBatisDemandRepository.java` 的 `buildReviewWrapper`/`countAll`/`countByStatus` 之后追加实现：

```java
    @Override
    public List<Demand> findCandidatePage(Long userId, DemandQuery query) {
        if (query == null) {
            return List.of();
        }
        LambdaQueryWrapper<DemandEntity> wrapper = buildCandidateWrapper(userId, query);
        return demandMapper.selectList(wrapper).stream().map(DemandEntity::toDomain).toList();
    }

    private LambdaQueryWrapper<DemandEntity> buildCandidateWrapper(Long userId, DemandQuery query) {
        LambdaQueryWrapper<DemandEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DemandEntity::getStatus, DemandStatus.PENDING.name());
        if (userId != null) {
            wrapper.ne(DemandEntity::getPublisherId, userId);
        }
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
```

> `LambdaQueryWrapper`/`DemandEntity`/`DemandStatus`/`DemandQuery`/`Locale`/`LocalDateTime` 已 import（2B.1）。无需新增 import。

- [ ] **Step 5: 跑 Repository 测试确认通过**

```
.\mvnw.cmd test -Dtest=MyBatisDemandRepositoryTest
```

Expected: PASS，`MyBatisDemandRepositoryTest` 全部用例（含 2B.1 的 11 + 2B.3b 的 9 + 2B.3d 的 3 + 新增 7 = 30+ 个）绿。

- [ ] **Step 6: 跑全量测试确认无回归**

```
.\mvnw.cmd test
```

Expected: 全绿，测试数 ≥ 200 + 7（新增）= 207。`RecommendationApplicationServiceImplTest` 此刻仍用旧 `filterCandidateDemands`（调 `findAll`），`findAll` 保留故仍绿。

> 若输出超 2000 行被截断写到 tool-output 文件，用 Grep 工具搜该文件确认无 `BUILD FAILURE`/`<<< FAILURE!`/`<<< ERROR!`，并搜 `Tests run:` 确认总数。

- [ ] **Step 7: Commit**

workdir=`D:\workspace\sec-ii-2026`：

```
git add backend/src/main/java/com/campushub/backend/demand/repository/DemandRepository.java backend/src/main/java/com/campushub/backend/demand/repository/MyBatisDemandRepository.java backend/src/test/java/com/campushub/backend/demand/repository/MyBatisDemandRepositoryTest.java
```

```
git commit -m "refactor(demand): push recommendation candidate filter down to SQL via findCandidatePage in DemandRepository"
```

---

### Task 2: RecommendationApplicationServiceImpl.filterCandidateDemands 改用 findCandidatePage + 删 5 matches* + Locale import

**Files:**
- Modify: `backend/src/main/java/com/campushub/backend/recommendation/service/RecommendationApplicationServiceImpl.java:140-151`（`filterCandidateDemands` 方法体）+ `:274-305`（删 5 matches*）+ import 区
- Test: `backend/src/test/java/com/campushub/backend/recommendation/service/RecommendationApplicationServiceImplTest.java`（不改，作回归护栏）

**Interfaces:**
- Consumes: Task 1 的 `DemandRepository.findCandidatePage(Long userId, DemandQuery) -> List<Demand>`。
- Produces: `filterCandidateDemands` 行为等价（7 过滤下推 SQL + N+1 保留）。

- [ ] **Step 1: 改 filterCandidateDemands 方法体**

替换 `RecommendationApplicationServiceImpl.java:140-151` 的 `filterCandidateDemands` 方法为：

```java
    private List<Demand> filterCandidateDemands(Long userId, DemandQuery query) {
        return demandRepository.findCandidatePage(userId, query).stream()
            .filter(demand -> orderRepository.findByDemandId(demand.getId()).isEmpty())
            .toList();
    }
```

> 保留 `findByDemandId.isEmpty()` N+1 过滤（留 2C）。删 7 道内存过滤（status/publisherId/keyword/category/campusZone/location/startTime 下推 SQL）。

- [ ] **Step 2: 删除 5 个仅服务于旧 filterCandidateDemands 的私有方法**

删除 `RecommendationApplicationServiceImpl.java` 中的以下私有方法（位于 `:274-305`，整体删除）：

- `matchesKeyword(Demand demand, String keyword)`（:274-282）
- `matchesCategory(Demand demand, String category)`（:284-286）
- `matchesCampusZone(Demand demand, String campusZone)`（:288-290）
- `matchesLocation(Demand demand, String location)`（:292-296）
- `matchesStartTimeRange(Demand demand, LocalDateTime from, LocalDateTime to)`（:298-305）

> 删前用 Grep 工具确认这 5 方法仅 `filterCandidateDemands`（:145-149）调用 + 各定义。grep 已确认（spec §1.5）。

- [ ] **Step 3: 删除连带失效的 Locale import**

从 `RecommendationApplicationServiceImpl.java` import 区删除（grep 已确认 `Locale` 仅被 `matchesKeyword`（:278-279,281）/`matchesLocation`（:295）用，删 5 matches* 后无引用）：

```java
import java.util.Locale;
```

> `LocalDateTime` 保留（`resolveComparator`/`scoreDemand`/`computeUrgencyScore`/`computeFreshnessScore`/`buildReasonTags` 用）。`Stream` 无 import（用 `.stream()` 链式）。`Comparator`/`HashMap`/`ArrayList`/`BigDecimal`/`ChronoUnit` 保留（recommend 评分逻辑用）。

- [ ] **Step 4: 跑 Service 测试确认 recommend 回归绿**

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```

```
.\mvnw.cmd test -Dtest=RecommendationApplicationServiceImplTest
```

Expected: PASS，含 `recommend`/`recommendDemandList` 用例全绿。

- [ ] **Step 5: 跑全量测试确认不回归**

```
.\mvnw.cmd test
```

Expected: 全绿，测试数 = Task 1 Step 6 的数量（本 task 不新增测试，仅重构）。

- [ ] **Step 6: Commit**

workdir=`D:\workspace\sec-ii-2026`：

```
git add backend/src/main/java/com/campushub/backend/recommendation/service/RecommendationApplicationServiceImpl.java
```

```
git commit -m "refactor(recommendation): switch filterCandidateDemands to SQL-pushed findCandidatePage and drop in-memory matches* (N+1 kept for 2C)"
```

---

## Self-Review

**1. Spec 覆盖**
- §4.1 接口扩展（findCandidatePage 无分页/无 count）→ Task 1 Step 3。✓
- §4.2 MyBatisDemandRepository 实现（buildCandidateWrapper）→ Task 1 Step 4。✓
- §4.3 过滤映射（7 过滤下推 + N+1 保留）→ Task 1 Step 4 + Task 2 Step 1。✓
- §4.4 无排序/无分页 → Task 1 Step 4（无 orderBy/LIMIT/count）。✓
- §4.5 Service 改造（findCandidatePage + 保留 N+1）→ Task 2 Step 1。✓
- §4.6 私有方法 + import 清理（删 5 matches* + Locale 连带）→ Task 2 Step 2-3。✓
- §4.7 now 一致性 → 无需代码动作。✓
- §5 影响面 → File Structure 表。✓
- §6 测试策略（回归护栏 + 新增 7）→ Task 1 Step 1 + Task 2 Step 4。✓
- §7 验收（mvn 全绿 / filterCandidateDemands 不再 findAll/matches* / findCandidatePage 暴露 / recommend 不变 / N+1 保留 / findAll 保留）→ Task 1 Step 5-6 + Task 2 Step 4-5。✓
- §8 风险（N+1 未消除 / 无分页大候选集 / status PENDING 隐含 / ne publisher_id / Locale 连带删 / like NULL）→ spec 已记。✓

**2. 占位符扫描**：无 TBD/TODO；所有代码块为可直接落地实现。✓

**3. 类型一致性**：`findCandidatePage(Long userId, DemandQuery) -> List<Demand>` 在 Task 1（接口+实现）与 Task 2（`demandRepository.findCandidatePage(userId, query)`）签名一致；`DemandEntity` 字段（`status`/`publisherId`/`title`/`description`/`category`/`campusZone`/`location`/`startTime`）与 spec 及源码 `@TableField` 一致；`DemandQuery` 字段（`q`/`category`/`campusZone`/`location`/`startTimeFrom`/`startTimeTo`/`sort`/`pageQuery`/`currentUserId`）与 `buildCandidateWrapper` 读取一致（忽略 `sort`/`pageQuery`/`currentUserId`，candidate 无分页/无排序/用方法参数 userId）。✓

**4. 无排序测试**：`findCandidatePage` 无 `orderBy`，测试用 `containsExactly`/`containsExactlyInAnyOrder`/`contains`（不依赖顺序），避免 id DESC 断言方向问题。✓

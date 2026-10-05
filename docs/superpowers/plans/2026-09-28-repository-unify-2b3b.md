# 仓储层统一 2B.3b（admin listPendingDemands SQL 下推）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `AdminApplicationServiceImpl.listPendingDemands` 的 `findByStatus(REVIEWING)` + 3 道内存过滤（keyword title/description/location / category / campusZone）+ 内存 `createdAt DESC` 排序 + `subList` 分页下沉为 `DemandRepository.findReviewPage` / `countReview` 的 SQL（`LambdaQueryWrapper` `eq(status,REVIEWING)` + `like` + `orderByDesc` + `LIMIT/OFFSET` + `selectCount`），并新建 `demand.dto.DemandReviewQuery` 解决 demand 仓储依赖 admin DTO 的层次反转，行为零回归。

**Architecture:** 新建 `demand.dto.DemandReviewQuery` record（字段与 `AdminDemandQuery` 对齐）；`DemandRepository` 新增 `findReviewPage(DemandReviewQuery)` + `countReview(DemandReviewQuery)`（风格 A，与 2B.1/2B.3a 一致）；`MyBatisDemandRepository` 提取私有 `buildReviewWrapper(query)`（`eq(status,REVIEWING)` + keyword 三字段 OR like + category/campusZone `eq`+upper，供 `findReviewPage`/`countReview` 共用）；`findReviewPage` 固定 `orderByDesc(createdAt).orderByDesc(id)` + `LIMIT/OFFSET`；Service `listPendingDemands` 内 `AdminDemandQuery → DemandReviewQuery` 映射后调新方法，删除 4 个仅服务于旧实现的私有方法（`matchesDemandKeyword`/`matchesDemandCategory`/`matchesDemandCampusZone`/`containsIgnoreCase`）。

**Tech Stack:** Java 21 / Spring Boot 3.5.0 / MyBatis-Plus 3.5.7（`BaseMapper` + `LambdaQueryWrapper`，无 XML mapper）/ H2 MySQL 兼容模式（测试）/ MySQL（生产）/ JUnit 5 + AssertJ。

**Spec:** `docs/superpowers/specs/2026-09-28-repository-unify-2b3b-design.md`（设计依据，与本 plan 一同阅读）

## Global Constraints

- **JDK 21**：本机默认 JDK 17，跑 Maven 前必须先设 `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"`（单条命令），再单独跑 `.\mvnw.cmd test`（workdir=`D:\workspace\sec-ii-2026\backend`）。
- **PowerShell 7+**：bash 工具禁止管道 `|`、分号 `;`、`&&`、`&`、重定向 `>`、反引号、`$()`；每条命令单独执行。
- **Maven Wrapper**：用 `.\mvnw.cmd`（非 `./mvnw`），workdir=`D:\workspace\sec-ii-2026\backend`。
- **前端契约不变**：`PageResponse(items, page, size, total)` / `DemandSummaryResponse` 字段结构不变；`AdminController` 转换不变；API 行为不变。
- **测试基线 165/165 不回归**（2B.3a 后）：每 task 结束跑全量验证。
- **分支 `refactor/optimization`**：仅 SDD 的 commit step 可执行；push 到 PR #10 延续授权模式；不 merge。
- **无 XML mapper**：全部 `BaseMapper` + `LambdaQueryWrapper`。
- **H2 MySQL 兼容模式**（测试）与 MySQL（生产）行为一致；LIKE 依赖默认 ci collation。

## File Structure

| 文件 | 责任 | 本 plan 操作 |
|---|---|---|
| `demand/dto/DemandReviewQuery.java` | 审核查询条件 record（demand 层） | **新建**（字段同 `AdminDemandQuery`） |
| `demand/repository/DemandRepository.java` | 仓储接口 | 加 `findReviewPage(DemandReviewQuery)` + `countReview(DemandReviewQuery)`（其余方法保留）+ import |
| `demand/repository/MyBatisDemandRepository.java` | MyBatis 实现 | 实现 `findReviewPage` + `countReview` + 私有 `buildReviewWrapper` + import |
| `admin/service/AdminApplicationServiceImpl.java` | 应用服务 | `listPendingDemands` 改用新方法 + `AdminDemandQuery→DemandReviewQuery` 映射；删 4 私有方法；加 `DemandReviewQuery` import |
| `demand/repository/MyBatisDemandRepositoryTest.java` | Repository 切片测试 | 新增 9 个 `findReviewPage`/`countReview` 用例 |

---

### Task 1: 新建 DemandReviewQuery + DemandRepository 扩展 findReviewPage/countReview + MyBatisDemandRepository 实现

**Files:**
- Create: `backend/src/main/java/com/campushub/backend/demand/dto/DemandReviewQuery.java`
- Modify: `backend/src/main/java/com/campushub/backend/demand/repository/DemandRepository.java`
- Modify: `backend/src/main/java/com/campushub/backend/demand/repository/MyBatisDemandRepository.java`
- Test: `backend/src/test/java/com/campushub/backend/demand/repository/MyBatisDemandRepositoryTest.java`

**Interfaces:**
- Consumes: `AdminDemandQuery`（`admin.dto`，record `(String q, String category, String campusZone, PageQuery pageQuery)`，紧凑构造器 `pageQuery` null 兜底，3 参数重载无 campusZone）、`PageQuery`（`common.model`，record `(int page, int size)`，构造校验 `page>=1`、`1<=size<=100`、`page<=1000`）、`DemandEntity`（`@TableName("ord_demand")`，字段 `status`(String, 列 `status`)/`title`(String)/`description`(String)/`location`(String)/`category`(String)/`campusZone`(String, 列 `campus_zone`)/`createdAt`(LocalDateTime, 列 `created_at`)/`id`(Long)）、`DemandStatus`（`demand.domain` 枚举，`REVIEWING`/`PENDING`/`IN_PROGRESS`/`COMPLETED`/`CANCELLED`，`.name()` 返回全大写）、`DemandMapper extends BaseMapper<DemandEntity>`（`selectList`/`selectCount`）。
- Produces: `DemandReviewQuery` record（demand 层审核查询条件）、`DemandRepository.findReviewPage(DemandReviewQuery) -> List<Demand>`、`DemandRepository.countReview(DemandReviewQuery) -> long`（供 Task 2 Service 调用）。

- [ ] **Step 1: 写失败测试（9 个 findReviewPage/countReview 用例）**

在 `MyBatisDemandRepositoryTest.java` 类体内末尾（`newDemand`/`newDemandWithCreated` 工厂之前）追加测试方法。

> 现有工厂 `newDemand(title, category)` 默认 `status=REVIEWING`（见 2B.1 测试）；`newDemandWithCreated(title, category, createdAt)` 设 createdAt。造非 REVIEWING 需 `setStatus` + `save`。

测试方法（追加到类体内）：

```java
    @Test
    void findReviewPage_returns_only_reviewing_demands() {
        Demand reviewing = repository.save(newDemand("审核中", DemandCategory.OTHER));
        Demand pending = repository.save(newDemand("待接", DemandCategory.OTHER));
        pending.setStatus(DemandStatus.PENDING);
        repository.save(pending);
        Demand completed = repository.save(newDemand("已完成", DemandCategory.OTHER));
        completed.setStatus(DemandStatus.COMPLETED);
        repository.save(completed);

        DemandReviewQuery query = new DemandReviewQuery(null, null, null, new PageQuery(1, 20));

        assertThat(repository.findReviewPage(query)).extracting(Demand::getId).containsExactly(reviewing.getId());
        assertThat(repository.countReview(query)).isEqualTo(1L);
    }

    @Test
    void findReviewPage_keyword_matches_title_or_description_or_location() {
        Demand byTitle = repository.save(newDemand("跑腿取快递", DemandCategory.OTHER));
        Demand byDescription = repository.save(newDemand("无关标题", DemandCategory.OTHER));
        byDescription.setDescription("代取快递帮拿");
        repository.save(byDescription);
        Demand byLocation = repository.save(newDemand("另一需求", DemandCategory.OTHER));
        byLocation.setLocation("菜鸟驿站快递点");
        repository.save(byLocation);
        Demand noMatch = repository.save(newDemand("无关需求", DemandCategory.OTHER));
        noMatch.setDescription("无关描述");
        noMatch.setLocation("无关地点");
        repository.save(noMatch);

        DemandReviewQuery query = new DemandReviewQuery("快递", null, null, new PageQuery(1, 20));

        assertThat(repository.findReviewPage(query)).extracting(Demand::getId)
            .containsExactlyInAnyOrder(byTitle.getId(), byDescription.getId(), byLocation.getId());
        assertThat(repository.countReview(query)).isEqualTo(3L);
    }

    @Test
    void findReviewPage_filters_by_category_case_insensitive() {
        Demand express = repository.save(newDemand("快递", DemandCategory.EXPRESS));
        repository.save(newDemand("辅导", DemandCategory.STUDY_TUTORING));

        DemandReviewQuery query = new DemandReviewQuery(null, "express", null, new PageQuery(1, 20));

        assertThat(repository.findReviewPage(query)).extracting(Demand::getId).containsExactly(express.getId());
        assertThat(repository.countReview(query)).isEqualTo(1L);
    }

    @Test
    void findReviewPage_filters_by_campus_zone_case_insensitive() {
        repository.save(newDemand("仙林", DemandCategory.OTHER));
        Demand gulou = repository.save(newDemand("鼓楼", DemandCategory.OTHER));
        gulou.setCampusZone(CampusZone.GULOU);
        repository.save(gulou);

        DemandReviewQuery query = new DemandReviewQuery(null, null, "xianlin", new PageQuery(1, 20));

        assertThat(repository.findReviewPage(query)).hasSize(1);
        assertThat(repository.countReview(query)).isEqualTo(1L);
    }

    @Test
    void findReviewPage_sorts_by_created_desc_then_id_desc() {
        Demand older = repository.save(newDemandWithCreated("旧", DemandCategory.OTHER, LocalDateTime.now().minusMinutes(10)));
        Demand newer = repository.save(newDemandWithCreated("新", DemandCategory.OTHER, LocalDateTime.now().minusMinutes(1)));
        Demand sameInstant = repository.save(newDemandWithCreated("同瞬", DemandCategory.OTHER, newer.getCreatedAt()));

        DemandReviewQuery query = new DemandReviewQuery(null, null, null, new PageQuery(1, 20));

        List<Demand> page = repository.findReviewPage(query);
        assertThat(page).hasSize(3);
        assertThat(page.get(0).getId()).isEqualTo(newer.getId());
        assertThat(page.get(1).getId()).isEqualTo(sameInstant.getId());
        assertThat(page.get(2).getId()).isEqualTo(older.getId());
    }

    @Test
    void findReviewPage_applies_limit_and_offset_for_pagination() {
        for (int i = 0; i < 5; i++) {
            repository.save(newDemandWithCreated("需求" + i, DemandCategory.OTHER, LocalDateTime.now().minusMinutes(5 - i)));
        }
        DemandReviewQuery page1 = new DemandReviewQuery(null, null, null, new PageQuery(1, 2));
        DemandReviewQuery page2 = new DemandReviewQuery(null, null, null, new PageQuery(2, 2));
        DemandReviewQuery page3 = new DemandReviewQuery(null, null, null, new PageQuery(3, 2));

        assertThat(repository.findReviewPage(page1)).hasSize(2);
        assertThat(repository.findReviewPage(page2)).hasSize(2);
        assertThat(repository.findReviewPage(page3)).hasSize(1);
        assertThat(repository.countReview(page1)).isEqualTo(5L);
    }

    @Test
    void findReviewPage_combines_keyword_category_and_campus_zone() {
        Demand match = repository.save(newDemand("跑腿快递", DemandCategory.EXPRESS));
        repository.save(newDemand("跑腿快递", DemandCategory.OTHER));
        Demand otherZone = repository.save(newDemand("仙林快递", DemandCategory.EXPRESS));
        otherZone.setCampusZone(CampusZone.GULOU);
        repository.save(otherZone);

        DemandReviewQuery query = new DemandReviewQuery("快递", "EXPRESS", "XIANLIN", new PageQuery(1, 20));

        assertThat(repository.findReviewPage(query)).extracting(Demand::getId).containsExactly(match.getId());
        assertThat(repository.countReview(query)).isEqualTo(1L);
    }

    @Test
    void countReview_matches_findReviewPage_total_for_filtered_query() {
        repository.save(newDemand("快递一", DemandCategory.EXPRESS));
        repository.save(newDemand("辅导", DemandCategory.STUDY_TUTORING));
        repository.save(newDemand("快递二", DemandCategory.EXPRESS));

        DemandReviewQuery query = new DemandReviewQuery(null, "EXPRESS", null, new PageQuery(1, 20));

        assertThat(repository.countReview(query)).isEqualTo(2L);
        assertThat(repository.findReviewPage(query)).hasSize(2);
    }

    @Test
    void findReviewPage_and_countReview_return_empty_when_query_null() {
        assertThat(repository.findReviewPage(null)).isEmpty();
        assertThat(repository.countReview(null)).isEqualTo(0L);
    }
```

补充 import（在现有 import 区追加）：

```java
import com.campushub.backend.demand.dto.DemandReviewQuery;
```

> `PageQuery`/`DemandStatus`/`DemandCategory`/`CampusZone`/`LocalDateTime` 已在 2B.1 时 import，无需重复。

- [ ] **Step 2: 跑测试确认失败（编译错）**

设置 JDK 21（单独一条命令）：

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```

跑 Repository 测试（workdir=`D:\workspace\sec-ii-2026\backend`，单独一条）：

```
.\mvnw.cmd test -Dtest=MyBatisDemandRepositoryTest
```

Expected: 编译失败，`DemandReviewQuery` 不存在，`DemandRepository` 无 `findReviewPage`/`countReview` 方法。

- [ ] **Step 3: 新建 DemandReviewQuery record**

创建 `backend/src/main/java/com/campushub/backend/demand/dto/DemandReviewQuery.java`：

```java
package com.campushub.backend.demand.dto;

import com.campushub.backend.common.model.PageQuery;

public record DemandReviewQuery(
    String q,
    String category,
    String campusZone,
    PageQuery pageQuery
) {

    public DemandReviewQuery {
        pageQuery = pageQuery == null ? PageQuery.defaultPage() : pageQuery;
    }
}
```

- [ ] **Step 4: 加 DemandRepository 接口方法**

`DemandRepository.java` 在 `count` 之后追加（`findAll`/`findByStatus`/`findPage`/`count` 保留不动）：

```java
    /**
     * 按审核查询条件分页查询审核中需求（status=REVIEWING + 过滤 + 排序 + LIMIT/OFFSET 下推 SQL）。
     *
     * @param query 查询条件，为 null 时返回空列表
     */
    List<Demand> findReviewPage(DemandReviewQuery query);

    /**
     * 按审核查询条件统计匹配的审核中需求总数（过滤下推 SQL，用于分页 total）。
     *
     * @param query 查询条件，为 null 时返回 0
     */
    long countReview(DemandReviewQuery query);
```

并在 import 区追加（保持字母序）：

```java
import com.campushub.backend.demand.dto.DemandReviewQuery;
```

- [ ] **Step 5: 实现 MyBatisDemandRepository 的 findReviewPage/countReview/buildReviewWrapper**

在 `MyBatisDemandRepository.java` 的 `applySort` 方法之后追加实现：

```java
    @Override
    public List<Demand> findReviewPage(DemandReviewQuery query) {
        if (query == null) {
            return List.of();
        }
        LambdaQueryWrapper<DemandEntity> wrapper = buildReviewWrapper(query);
        wrapper.orderByDesc(DemandEntity::getCreatedAt)
               .orderByDesc(DemandEntity::getId);
        int size = query.pageQuery().size();
        long offset = (long) (query.pageQuery().page() - 1) * size;
        wrapper.last("LIMIT " + size + " OFFSET " + offset);
        return demandMapper.selectList(wrapper).stream().map(DemandEntity::toDomain).toList();
    }

    @Override
    public long countReview(DemandReviewQuery query) {
        if (query == null) {
            return 0L;
        }
        return demandMapper.selectCount(buildReviewWrapper(query));
    }

    private LambdaQueryWrapper<DemandEntity> buildReviewWrapper(DemandReviewQuery query) {
        LambdaQueryWrapper<DemandEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DemandEntity::getStatus, DemandStatus.REVIEWING.name());
        String q = query.q();
        if (q != null && !q.isBlank()) {
            String keyword = q.trim();
            wrapper.and(w -> w.like(DemandEntity::getTitle, keyword)
                .or().like(DemandEntity::getDescription, keyword)
                .or().like(DemandEntity::getLocation, keyword));
        }
        String category = query.category();
        if (category != null && !category.isBlank()) {
            wrapper.eq(DemandEntity::getCategory, category.trim().toUpperCase(Locale.ROOT));
        }
        String zone = query.campusZone();
        if (zone != null && !zone.isBlank()) {
            wrapper.eq(DemandEntity::getCampusZone, zone.trim().toUpperCase(Locale.ROOT));
        }
        return wrapper;
    }
```

在 import 区追加（保持字母序，与现有风格一致）：

```java
import com.campushub.backend.demand.dto.DemandReviewQuery;
```

> `Locale`/`DemandStatus`/`LocalDateTime` 已在 2B.1 时 import，无需重复。

- [ ] **Step 6: 跑 Repository 测试确认通过**

确保 `$env:JAVA_HOME` 已设（若新 session 先执行 Step 2 的设置命令）。workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test -Dtest=MyBatisDemandRepositoryTest
```

Expected: PASS，`MyBatisDemandRepositoryTest` 全部用例（含原有 + 2B.1 的 11 + 新增 9）绿。

- [ ] **Step 7: 跑全量测试确认无回归**

workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test
```

Expected: 全绿，测试数 ≥ 165 + 9（新增）= 174。`AdminApplicationServiceImplTest` 此刻仍用旧 `listPendingDemands`（调 `findByStatus`），`findByStatus` 保留故仍绿。

> 若输出超 2000 行被截断写到 tool-output 文件，用 Grep 工具搜该文件确认无 `BUILD FAILURE`/`<<< FAILURE!`/`<<< ERROR!`，并搜 `Tests run:` 确认总数。

- [ ] **Step 8: Commit**

workdir=`D:\workspace\sec-ii-2026`：

```
git add backend/src/main/java/com/campushub/backend/demand/dto/DemandReviewQuery.java backend/src/main/java/com/campushub/backend/demand/repository/DemandRepository.java backend/src/main/java/com/campushub/backend/demand/repository/MyBatisDemandRepository.java backend/src/test/java/com/campushub/backend/demand/repository/MyBatisDemandRepositoryTest.java
```

```
git commit -m "refactor(demand): push admin review list query down to SQL via DemandReviewQuery/findReviewPage/countReview in DemandRepository"
```

---

### Task 2: AdminApplicationServiceImpl.listPendingDemands 改用 findReviewPage/countReview + 清理私有方法

**Files:**
- Modify: `backend/src/main/java/com/campushub/backend/admin/service/AdminApplicationServiceImpl.java:154-175`（`listPendingDemands` 方法体）+ `:351-368`（删 4 私有方法）+ import 区
- Test: `backend/src/test/java/com/campushub/backend/admin/service/AdminApplicationServiceImplTest.java`（不改，作回归护栏）

**Interfaces:**
- Consumes: Task 1 的 `DemandRepository.findReviewPage(DemandReviewQuery) -> List<Demand>` + `DemandRepository.countReview(DemandReviewQuery) -> long` + `DemandReviewQuery` record。
- Produces: `AdminApplicationService.listPendingDemands(Long operatorId, AdminDemandQuery query) -> PageResponse<DemandSummaryResponse>`（签名不变，行为等价但走 SQL 下推）。

- [ ] **Step 1: 改 listPendingDemands 方法体**

替换 `AdminApplicationServiceImpl.java:154-175` 的 `listPendingDemands` 方法为：

```java
    @Override
    public PageResponse<DemandSummaryResponse> listPendingDemands(Long operatorId, AdminDemandQuery query) {
        requireAdmin(operatorId);
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "admin demand query must not be null");
        }

        DemandReviewQuery reviewQuery = new DemandReviewQuery(
            query.q(), query.category(), query.campusZone(), query.pageQuery());
        List<Demand> demands = demandRepository.findReviewPage(reviewQuery);
        List<DemandSummaryResponse> items = demands.stream().map(DemandSummaryResponse::from).toList();
        long total = demandRepository.countReview(reviewQuery);
        int page = query.pageQuery().page();
        int size = query.pageQuery().size();
        return new PageResponse<>(items, page, size, total);
    }
```

- [ ] **Step 2: 删除 4 个仅服务于旧 listPendingDemands 的私有方法**

删除 `AdminApplicationServiceImpl.java` 中的以下私有方法（位于 `:351-368`，整体删除）：

- `matchesDemandKeyword(Demand demand, String keyword)`（:351-358）
- `matchesDemandCategory(Demand demand, String category)`（:360-363）
- `matchesDemandCampusZone(Demand demand, String campusZone)`（:365-368）
- `containsIgnoreCase(String value, String normalizedKeyword)`（:364）—— **连带删**

> 删除前用 Grep 工具确认这 4 方法仅被旧 `listPendingDemands` 调用：搜 `matchesDemandKeyword|matchesDemandCategory|matchesDemandCampusZone|containsIgnoreCase` 在 `AdminApplicationServiceImpl.java`，命中应仅旧 `listPendingDemands` 体内（:161-163）与各方法定义（:351/360/364/365）。2B.3a 已删 `matchesUserKeyword`，`containsIgnoreCase` 此刻仅 `matchesDemandKeyword` 用，删后者后无引用。

- [ ] **Step 3: import 处理**

从 `AdminApplicationServiceImpl.java` import 区**删除前确认**（编译通过为准绳，逐项 grep 确认无其他引用）：

- `java.util.Comparator`：仍被 `listArbitrationOrders`（:185 `Comparator.comparing(...)`）用，**保留**。
- `java.util.Locale`：仍被 `updateUserRole`（:137）/ `reviewDemand`（:210）/ `resolveOrderArbitration`（:262）用，**保留**。
- `com.campushub.backend.demand.domain.Demand`：仍被新 `listPendingDemands`（`List<Demand> demands`）/`reviewDemand`/`getDashboard` 用，**保留**。
- `com.campushub.backend.demand.domain.DemandStatus`：仍被 `reviewDemand`（:206）/`getDashboard`（:306）用，**保留**。
- `com.campushub.backend.demand.dto.DemandSummaryResponse`：仍被新 `listPendingDemands` 用，**保留**。

故 import 区**仅新增**（不删）：

```java
import com.campushub.backend.demand.dto.DemandReviewQuery;
```

> 删除 4 私有方法后，若编译报某 import 失效再删；预期无失效（`Comparator`/`Locale`/`Demand`/`DemandStatus`/`DemandSummaryResponse` 均有其他引用）。

- [ ] **Step 4: 跑 Service 测试确认 listPendingDemands 回归绿**

确保 `$env:JAVA_HOME` 已设。workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test -Dtest=AdminApplicationServiceImplTest
```

Expected: PASS，含 `shouldListAndApproveReviewingDemand`（listPendingDemands 用例）及 `listUsers`/`banUser`/`reviewDemand`/`getDashboard`/`listArbitrationOrders` 等全绿。

- [ ] **Step 5: 跑全量测试确认不回归**

workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test
```

Expected: 全绿，测试数 = Task 1 Step 7 的数量（本 task 不新增测试，仅重构）。

- [ ] **Step 6: Commit**

workdir=`D:\workspace\sec-ii-2026`：

```
git add backend/src/main/java/com/campushub/backend/admin/service/AdminApplicationServiceImpl.java
```

```
git commit -m "refactor(admin): switch AdminApplicationServiceImpl.listPendingDemands to SQL-pushed findReviewPage/countReview and drop in-memory filters"
```

---

## Self-Review

**1. Spec 覆盖**
- §4.1 新建 `DemandReviewQuery`（字段同 `AdminDemandQuery`）→ Task 1 Step 3。✓
- §4.2 接口扩展（findReviewPage/countReview 风格 A）→ Task 1 Step 4。✓
- §4.3 MyBatisDemandRepository 实现（buildReviewWrapper/findReviewPage/countReview）→ Task 1 Step 5。✓
- §4.4 过滤下沉映射（eq(status,REVIEWING) / keyword 三字段 OR like / category eq+upper / campusZone eq+upper）→ Task 1 Step 5 `buildReviewWrapper`。✓
- §4.5 排序下沉（createdAt DESC + id DESC tie-breaker + NULL 边界）→ Task 1 Step 5 `findReviewPage` 内 `orderByDesc(createdAt).orderByDesc(id)`。✓
- §4.6 Service 改造（AdminDemandQuery→DemandReviewQuery 映射 + findReviewPage/countReview）→ Task 2 Step 1。✓
- §4.7 私有方法 + import 清理（删 4 方法含 containsIgnoreCase 连带；Comparator/Locale/Demand/DemandStatus/DemandSummaryResponse 保留；新增 DemandReviewQuery import）→ Task 2 Step 2-3。✓
- §4.8 now 一致性 → 无需代码动作（审核查询无 now 依赖）。✓
- §5 影响面文件清单 → File Structure 表。✓
- §6 测试策略（回归护栏 1 + 新增 9）→ Task 1 Step 1（9 新增）+ Task 2 Step 4（1 护栏）。✓
- §7 验收（mvn 全绿 / listPendingDemands 不再 findByStatus/matchesDemand*/containsIgnoreCase/subList / findReviewPage+countReview 暴露 / DemandReviewQuery 存在 / 契约不变 / Comparator+findByStatus 保留）→ Task 1 Step 6-7 + Task 2 Step 4-5。✓
- §8 风险（like 大小写 / description/location NULL / id DESC tie-breaker / 映射零损耗 / containsIgnoreCase 连带删 / LIMIT 拼接）→ spec 已记，测试覆盖。✓

**2. 占位符扫描**：无 TBD/TODO/"add appropriate"等；所有代码块为可直接落地实现。✓

**3. 类型一致性**：`DemandReviewQuery(String q, String category, String campusZone, PageQuery pageQuery)` 在 Task 1（定义）与 Task 2（`new DemandReviewQuery(query.q(), query.category(), query.campusZone(), query.pageQuery())`）字段顺序一致；`findReviewPage(DemandReviewQuery) -> List<Demand>` / `countReview(DemandReviewQuery) -> long` 在 Task 1（接口+实现）与 Task 2（`demandRepository.findReviewPage(reviewQuery)` + `countReview(reviewQuery)`）签名一致；`DemandEntity` 字段（`status`/`title`/`description`/`location`/`category`/`campusZone`/`createdAt`/`id`）与 spec 及源码 `@TableField` 一致；`AdminDemandQuery` 字段（`q`/`category`/`campusZone`/`pageQuery`）与 `DemandReviewQuery` 逐字对齐。✓

# 仓储层统一阶段 2B.6 设计（recommendation filterCandidateDemands SQL 下推）

- 日期：2026-09-28
- 状态：待评审（用户已授权直接执行）
- 分支：`refactor/optimization`（HEAD `cb10874`，2B.5 已完成，测试 200/200）
- 上游 spec：`docs/superpowers/specs/2026-09-28-repository-unify-2b-design.md`（2B 总体，本文件为其第 6 子阶段，2B 最后一个）
- 范围：`DemandRepository` 扩 `findCandidatePage` + `MyBatisDemandRepository` 实现 + `RecommendationApplicationServiceImpl.filterCandidateDemands` 下沉 SQL（N+1 留 2C）

## 1. 背景与现状（探索已核实）

### 1.1 现状定位（2B 总体 spec #3）

`RecommendationApplicationServiceImpl.filterCandidateDemands`（`backend/src/main/java/com/campushub/backend/recommendation/service/RecommendationApplicationServiceImpl.java:140-151`）当前：

```java
return demandRepository.findAll().stream()
    .filter(demand -> demand.getStatus() == DemandStatus.PENDING)
    .filter(demand -> orderRepository.findByDemandId(demand.getId()).isEmpty())  // ← 跨仓储 N+1
    .filter(demand -> !demand.getPublisherId().equals(userId))
    .filter(demand -> matchesKeyword(demand, query.q()))
    .filter(demand -> matchesCategory(demand, query.category()))
    .filter(demand -> matchesCampusZone(demand, query.campusZone()))
    .filter(demand -> matchesLocation(demand, query.location()))
    .filter(demand -> matchesStartTimeRange(demand, query.startTimeFrom(), query.startTimeTo()))
    .toList();
```

即 `findAll()` 全表加载 → 8 道内存过滤（status PENDING / **跨仓储 findByDemandId N+1** / 排除自己 publisherId / keyword / category / campusZone / location / startTime）→ 返回候选 `List<Demand>`（无分页——`buildRankedPage` :121-133 做 score 排序 + `subList` 分页）。

### 1.2 N+1 与跨仓储（关键，留 2C）

`orderRepository.findByDemandId(demand.getId()).isEmpty()` 是跨仓储过滤（demand 查 ord_order 是否存在 demand_id）。每个候选 demand 一次 SQL——N+1。

彻底下推需 SQL `NOT EXISTS (SELECT 1 FROM ord_order WHERE demand_id = ord_demand.id)`，但 `DemandRepository`（demand 层）不应依赖 ord_order 表（order 层）——跨层。且 demand 接单后 `status: PENDING → IN_PROGRESS`（`OrderApplicationServiceImpl.accept:97`），故 `status=PENDING` 隐含未被接单，但 `findByDemandId.isEmpty()` 是防御性显式检查（防异常数据）。

**裁决**：2B.6 **保留 `findByDemandId.isEmpty()` 在 Service 层**（N+1 留 2C），**仅下推 7 道非跨仓储过滤**（status / publisherId / keyword / category / campusZone / location / startTime）到 SQL。2C 再用 `NOT EXISTS` 子查询或反规范化消除 N+1。

### 1.3 无分页/无 count（与 2B.1-2B.5 差异）

`filterCandidateDemands` 返回候选 `List<Demand>`（无分页），`buildRankedPage` 在 Service 层做 score 排序 + `subList` 分页。`total = ranked.size()`（N+1 过滤后的候选数）。

故 `findCandidatePage` **无 LIMIT/OFFSET**（返回全部 SQL 过滤后的候选），**无 count**（total 在 Service 层 N+1 过滤后算 = `ranked.size()`）。

### 1.4 `findAll` 保留（与用户指令差异）

用户指令提"可删 findAll，因唯一调用方下推"。但 grep 已确认 `demandRepository.findAll` 仍被 `AdminApplicationServiceImpl.getDashboard:282`（2B.3d 保留 dailyActiveUsers 用）调用。故 2B.6 下推 `filterCandidateDemands` 后，`findAll` 仍有 `getDashboard` 调用方，**保留**。2C 优化 `dailyActiveUsers` 去 `findAll` 后再评估删除。

### 1.5 私有方法引用范围（grep 已核实）

- `matchesKeyword`（:274-282）/ `matchesCategory`（:284-286）/ `matchesCampusZone`（:288-290）/ `matchesLocation`（:292-296）/ `matchesStartTimeRange`（:298-305）：5 方法仅 `filterCandidateDemands`（:145-149）调用，下推后可删。
- `resolveComparator`（:166-180）/ `scoreDemand`/`scoreColdStart`/`computeRewardScore`/`computeUrgencyScore`/`computeFreshnessScore`/`buildReasonTags`/`buildAcceptedCategoryStats`：recommend 排序/评分逻辑，**保留不动**。
- `Locale` import：`matchesKeyword`/`matchesLocation` 用 `toLowerCase(Locale.ROOT)`。删 5 matches* 后，`Locale` 仍被... 需 grep 确认。若 `Locale` 仅 matches* 用，连带删；否则保留。

### 1.6 调用方契约

`filterCandidateDemands` 是 `RecommendationApplicationServiceImpl` 私有方法（:140），被 `buildRankedPage`（:106）调用。`recommend`/`recommendDemandList`（public）调 `buildRankedPage`。2B.6 仅改 `filterCandidateDemands` 内部实现，`PageResponse<RecommendationItemResponse>`/`PageResponse<DemandSummaryResponse>` 结构不变，Controller 与前端契约不动。

## 2. 目标 / 非目标

### 目标
1. `DemandRepository` 新增 `findCandidatePage(Long userId, DemandQuery query)`（风格 A 变体——无分页/无 count，返回候选 List），`findAll`/`findByStatus`/`findPage`/`count`/`findReviewPage`/`countReview`/`countAll`/`countByStatus` 保留。
2. `MyBatisDemandRepository` 实现：私有 `buildCandidateWrapper(userId, query)`（`eq(status, PENDING)` + `ne(publisher_id, userId)` + keyword like(title/description) + category eq+upper + campusZone eq+upper + location like + startTime isNotNull+ge+le，供 `findCandidatePage` 用）；`findCandidatePage` 用 `selectList(wrapper).map(toDomain)`（无 LIMIT/OFFSET，无 orderBy——Service 重排）。
3. `RecommendationApplicationServiceImpl.filterCandidateDemands` 改用 `findCandidatePage`，保留 `orderRepository.findByDemandId.isEmpty()` N+1 过滤（留 2C），删除 5 个 `matches*` 私有方法。
4. 行为零回归：`recommend`/`recommendDemandList` 行为不变；现有 recommendation 用例断言不变且全绿。

### 非目标
- 不下推 `findByDemandId.isEmpty()` N+1（1.2，2C）。
- 不加 LIMIT/OFFSET/count（1.3，候选集，Service 做排序+分页）。
- 不删 `findAll`（1.4，getDashboard 用）。
- 不动 `resolveComparator`/`scoreDemand`/`buildAcceptedCategoryStats`/`buildReasonTags`（recommend 评分逻辑）。
- 不改 `DemandQuery`/`Demand`/`DemandEntity`/`RecommendationItem`/schema/Controller。
- 不动 `recommend`/`recommendDemandList`/`buildRankedPage`/`normalizeQuery`/`validateUser`。
- 不修 N+1（2C）。

## 3. 约束（继承 2B 总体 + 主 spec）
- 前端兼容契约：`PageResponse(items, page, size, total)`/`RecommendationItemResponse`/`DemandSummaryResponse` 字段结构不变；API 行为不变。
- 测试全绿：基线 200/200（2B.5 后），每子阶段不回归。
- 工作分支 `refactor/optimization`。
- Repository 接口扩方法，`MyBatisDemandRepository` 实现；无 XML mapper，全 `BaseMapper` + `LambdaQueryWrapper`。
- H2 MySQL 兼容模式（测试）与 MySQL（生产）行为一致；LIKE 依赖默认 ci collation。

## 4. 设计

### 4.1 Repository 接口扩展（风格 A 变体——无分页/无 count）

`DemandRepository.java` 新增一方法（`findReviewPage`/`countReview`（2B.3b）等保留）：

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

并在 import 区追加（`DemandQuery` 已 import，2B.1）——**无需新增 import**。

### 4.2 MyBatisDemandRepository 实现

新增私有 `buildCandidateWrapper(userId, query)`（纯过滤）。`findCandidatePage` 用 `selectList(wrapper).map(toDomain)`（无 LIMIT/OFFSET，无 orderBy）。

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

> `LambdaQueryWrapper`/`DemandEntity`/`DemandStatus`/`DemandQuery`/`Locale`/`LocalDateTime` 已 import（2B.1）。`wrapper.ne(publisher_id, userId)` 生成 `publisher_id <> ?`。

### 4.3 过滤下沉映射

| # | 内存逻辑（现状） | SQL 下推 | 边界对齐 |
|---|---|---|---|
| 1 | `demand.getStatus() == DemandStatus.PENDING` | `eq(status, 'PENDING')` | `DemandStatus.PENDING.name()` = `"PENDING"`，enum name 全大写存库 |
| 2 | `orderRepository.findByDemandId(demand.getId()).isEmpty()` | **保留 Service 层 N+1**（2C 修） | 不下推（1.2）；`findCandidatePage` 返回候选后 Service 逐条 `findByDemandId` 过滤 |
| 3 | `!demand.getPublisherId().equals(userId)` | `ne(publisher_id, userId)` | `userId == null` 时不加条件（防御，与内存 `userId.equals` NPE 防御一致——实际 userId 非 null，`validateUser` 保证） |
| 4 | `matchesKeyword`（title/description containsIgnoreCase） | `and(w -> w.like(title, q).or().like(description, q))`，`q = q.trim()` | 同 2B.1 keyword；`description` NULL → `NULL LIKE` false，与内存 `description != null &&` 一致 |
| 5 | `matchesCategory`（category.name().equalsIgnoreCase） | `eq(category, category.trim().toUpperCase(Locale.ROOT))` | 同 2B.1/2B.3b |
| 6 | `matchesCampusZone` | `eq(campus_zone, zone.trim().toUpperCase(Locale.ROOT))` | 同 2B.1/2B.3b |
| 7 | `matchesLocation`（location containsIgnoreCase） | `like(location, loc.trim())` | 同 2B.1；`location` NULL → false，与内存一致 |
| 8 | `matchesStartTimeRange`（startTime==null→from==null&&to==null；否则 startTime>=from && startTime<=to） | `from`/`to` 任一非 null：`isNotNull(start_time)` + `ge(from)` + `le(to)` | 同 2B.1 buildWrapper startTime 处理 |

### 4.4 无排序/无分页

`findCandidatePage` 无 `orderBy`（Service `buildRankedPage:121` 用 `resolveComparator` score 排序重排覆盖）。无 `LIMIT/OFFSET`（候选集，Service `subList:131-133` 分页）。无 `count`（`total = ranked.size()`，N+1 过滤后候选数）。

### 4.5 Service 改造

`RecommendationApplicationServiceImpl.filterCandidateDemands` 改为：

```java
private List<Demand> filterCandidateDemands(Long userId, DemandQuery query) {
    return demandRepository.findCandidatePage(userId, query).stream()
        .filter(demand -> orderRepository.findByDemandId(demand.getId()).isEmpty())
        .toList();
}
```

保留 `findByDemandId.isEmpty()` N+1 过滤（留 2C）。删 5 个 `matches*` 私有方法。

### 4.6 私有方法与 import 清理

删除（仅 `filterCandidateDemands` 用，grep 已确认）：
- `matchesKeyword`（:274-282）
- `matchesCategory`（:284-286）
- `matchesCampusZone`（:288-290）
- `matchesLocation`（:292-296）
- `matchesStartTimeRange`（:298-305）

随之失效的 import（删前 grep 确认）：
- `java.util.Locale`：`matchesKeyword`/`matchesLocation` 用 `toLowerCase(Locale.ROOT)`。删后，`Locale` 是否仍被其他方法用？`normalizeQuery`/`scoreDemand`/`buildReasonTags` 等未用 `Locale`。**需 grep 确认**——若仅 matches* 用，连带删 `Locale` import。
- `java.time.LocalDateTime`：`matchesStartTimeRange` 签名用，但 `resolveComparator`/`scoreDemand`/`computeUrgencyScore`/`computeFreshnessScore`/`buildReasonTags` 等也用 `LocalDateTime`——**保留**。
- `java.util.stream.Stream`：仅 `filterCandidateDemands` 用 `.stream()`（非 `Stream` 类型引用）。**无 `Stream` import**（用 `.stream()` 链式，不需 `Stream` 类型）。

> `Locale` import 处理：grep 确认 `Locale` 在 `RecommendationApplicationServiceImpl` 的引用。若仅 `matchesKeyword`/`matchesLocation` 用 `Locale.ROOT`，删后连带删 import；若 `buildReasonTags` 或其他用，保留。plan 实现时以编译通过为准绳。

### 4.7 `now` 一致性

`findCandidatePage` 无 `now`/时间边界依赖（不像 2B.1 的 `end_time >= now`）。`computeUrgencyScore`/`computeFreshnessScore` 在 Service 层用 `LocalDateTime.now()`，不影响 Repository。

## 5. 影响面

### 改动文件
| 文件 | 操作 |
|---|---|
| `demand/repository/DemandRepository.java` | 加 `findCandidatePage(Long, DemandQuery)`（其余方法保留） |
| `demand/repository/MyBatisDemandRepository.java` | 实现 `findCandidatePage` + 私有 `buildCandidateWrapper` |
| `recommendation/service/RecommendationApplicationServiceImpl.java` | `filterCandidateDemands` 改用新方法 + 保留 N+1；删 5 matches* 方法 + 失效 import（Locale 若连带） |

### 测试文件
| 文件 | 操作 |
|---|---|
| `demand/repository/MyBatisDemandRepositoryTest.java` | **新增** `findCandidatePage` 用例 |
| `recommendation/service/RecommendationApplicationServiceImplTest.java` | **不改**（`recommend`/`recommendDemandList` 用例作回归护栏） |

### 不受影响
- `DemandQuery`/`Demand`/`DemandEntity`/`RecommendationItem`/`RecommendationItemResponse`/`DemandSummaryResponse`/schema
- `recommend`/`recommendDemandList`/`buildRankedPage`/`normalizeQuery`/`validateUser`/`resolveComparator`/`scoreDemand`/`buildAcceptedCategoryStats`/`buildReasonTags`
- `findAll`（getDashboard 用）/`findPage`/`count`/`findReviewPage`/`countReview`/`countAll`/`countByStatus`/`findByStatus`
- Controller/前端契约
- 索引（`idx_demand_status`/`idx_demand_publisher`/`idx_demand_category`/`idx_demand_campus_zone` 已覆盖）

## 6. 测试策略

### 6.1 回归护栏（不改，须全绿）
- `RecommendationApplicationServiceImplTest` 现有 `recommend`/`recommendDemandList` 用例（验证 candidate 过滤 + score 排序 + 分页 + reasonTags）。下推后 `findCandidatePage` SQL 过滤 + Service N+1 + score 排序等价。

### 6.2 新增 `MyBatisDemandRepositoryTest` 用例

> 沿用 `newDemand(title, category)`（默认 status=PENDING）/`newDemandWithCreated`。造非 PENDING 需 `setStatus`+save。

1. `findCandidatePage` 仅返回 PENDING（插入 PENDING + IN_PROGRESS + COMPLETED，仅 PENDING 命中）。
2. `findCandidatePage` 排除自己发的（插入 userId=publisher + 其他 publisher，仅其他命中）。
3. `findCandidatePage` keyword 过滤（title/description 含命中）。
4. `findCandidatePage` category/campusZone/location 过滤。
5. `findCandidatePage` startTime range（from/to 排除 null start）。
6. `findCandidatePage` 组合过滤（status + publisher + keyword + category）。
7. `findCandidatePage(null)` 防御。

> 现有用例保留不动。

## 7. 验收标准
1. `.\mvnw.cmd test`（`JAVA_HOME` 指向 JDK 21，workdir=backend）全绿，测试数 ≥ 200 + 新增（约 7 个）。
2. `RecommendationApplicationServiceImpl.filterCandidateDemands` 不再出现 `demandRepository.findAll()`、`matchesKeyword`/`matchesCategory`/`matchesCampusZone`/`matchesLocation`/`matchesStartTimeRange`。
3. `DemandRepository` 暴露 `findCandidatePage(Long, DemandQuery)`；`MyBatisDemandRepository` 实现含 `buildCandidateWrapper`。
4. `recommend`/`recommendDemandList` 行为不变；现有用例全绿。
5. `findByDemandId.isEmpty()` N+1 保留在 Service（2C 修）。
6. `findAll`/`findPage`/`findReviewPage` 等保留。
7. Controller 及前端契约未触动。

## 8. 风险与对策
| 风险 | 对策 |
|---|---|
| N+1（findByDemandId）未消除 | 非目标（1.2，2C 用 NOT EXISTS 或反规范化） |
| `findCandidatePage` 无分页返回大候选集 | recommend 流程需全候选 score 排序（`buildRankedPage`），无分页是语义必需；`MAX_RECOMMEND_SIZE=50` 限制 page size |
| `status=PENDING` 隐含未被接单 vs 显式 `findByDemandId.isEmpty()` | 2B.6 保留 N+1 显式检查（防御异常数据），不下推（1.2） |
| `ne(publisher_id, userId)` 与内存 `!publisherId.equals(userId)` | `publisher_id <> ?` 等价；`userId==null` 不加条件（防御，实际非 null） |
| `Locale` import 连带删误伤 | grep 确认（4.6）；以编译通过为准绳 |
| `like` 大小写 / NULL | ci collation 一致；`description`/`location` NULL → false 与内存一致（4.3） |

# 仓储层统一 2B.4（review listUserReviews SQL 下推）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `ReviewApplicationServiceImpl.listUserReviews` 的 `findByTargetId`+`findByAuthorId` 两路 + `Stream.concat` + 内存 `createdAt DESC` 排序 + `subList` 分页下沉为 `ReviewRepository.findPage` / `count` 的单次 SQL（`LambdaQueryWrapper` `eq(target_id) OR eq(author_id)` + `orderByDesc` + `LIMIT/OFFSET` + `selectCount`），行为零回归。

**Architecture:** `ReviewRepository` 新增 `findPage(Long targetUserId, ReviewQuery query)` + `count(Long targetUserId, ReviewQuery query)`（风格 A，与 2B.2 一致，`targetUserId` 独立参数）；`MyBatisReviewRepository` 提取私有 `buildWrapper(targetUserId, query)`（`eq(target_id) OR eq(author_id)`，供 `findPage`/`count` 共用）；`findPage` 固定 `orderByDesc(createdAt).orderByDesc(id)` + `LIMIT/OFFSET`；Service `listUserReviews` 改调新方法，删 `Stream.concat` + 内存排序 + `subList`。

**Tech Stack:** Java 21 / Spring Boot 3.5.0 / MyBatis-Plus 3.5.7（`BaseMapper` + `LambdaQueryWrapper`，无 XML mapper）/ H2 MySQL 兼容模式（测试）/ MySQL（生产）/ JUnit 5 + AssertJ。

**Spec:** `docs/superpowers/specs/2026-09-28-repository-unify-2b4-design.md`（设计依据，与本 plan 一同阅读）

## Global Constraints

- **JDK 21**：本机默认 JDK 17，跑 Maven 前必须先设 `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"`（单条命令），再单独跑 `.\mvnw.cmd test`（workdir=`D:\workspace\sec-ii-2026\backend`）。
- **PowerShell 7+**：bash 工具禁止管道 `|`、分号 `;`、`&&`、`&`、重定向 `>`、反引号、`$()`；每条命令单独执行。
- **Maven Wrapper**：用 `.\mvnw.cmd`（非 `./mvnw`），workdir=`D:\workspace\sec-ii-2026\backend`。
- **前端契约不变**：`PageResponse(items, page, size, total)` / `ReviewResponse` 字段结构不变；API 行为不变。
- **测试基线 189/189 不回归**（2B.3d 后）：每 task 结束跑全量验证。
- **分支 `refactor/optimization`**：仅 SDD 的 commit step 可执行；push 到 PR #10 延续授权模式；不 merge。
- **无 XML mapper**：全部 `BaseMapper` + `LambdaQueryWrapper`。
- **H2 MySQL 兼容模式**（测试）与 MySQL（生产）行为一致。

## File Structure

| 文件 | 责任 | 本 plan 操作 |
|---|---|---|
| `review/repository/ReviewRepository.java` | 仓储接口 | 加 `findPage(Long, ReviewQuery)` + `count(Long, ReviewQuery)` + import |
| `review/repository/MyBatisReviewRepository.java` | MyBatis 实现 | 实现 `findPage` + `count` + 私有 `buildWrapper` + import |
| `review/service/ReviewApplicationServiceImpl.java` | 应用服务 | `listUserReviews` 改用新方法；删 `Stream.concat` + 内存排序 + `subList`；加 `ReviewQuery` import |
| `review/repository/MyBatisReviewRepositoryTest.java` | Repository 切片测试 | 新增 5 个 `findPage`/`count` 用例 + 1 个辅助工厂 |

---

### Task 1: ReviewRepository 扩展 findPage/count + MyBatisReviewRepository 实现

**Files:**
- Modify: `backend/src/main/java/com/campushub/backend/review/repository/ReviewRepository.java`
- Modify: `backend/src/main/java/com/campushub/backend/review/repository/MyBatisReviewRepository.java`
- Test: `backend/src/test/java/com/campushub/backend/review/repository/MyBatisReviewRepositoryTest.java`

**Interfaces:**
- Consumes: `ReviewQuery`（`review.dto`，record `(PageQuery pageQuery)`，紧凑构造器 `pageQuery` null 兜底）、`PageQuery`（`common.model`，record `(int page, int size)`，构造校验 `page>=1`、`1<=size<=100`、`page<=1000`）、`ReviewEntity`（`@TableName("ord_review")`，字段 `targetId`(Long, 列 `target_id`)/`authorId`(Long, 列 `author_id`)/`createdAt`(LocalDateTime, 列 `created_at`)/`id`(Long)）、`ReviewMapper extends BaseMapper<ReviewEntity>`（`selectList`/`selectCount`）。
- Produces: `ReviewRepository.findPage(Long targetUserId, ReviewQuery) -> List<Review>`、`ReviewRepository.count(Long targetUserId, ReviewQuery) -> long`（供 Task 2 Service 调用）。

- [ ] **Step 1: 写失败测试（5 个 findPage/count 用例 + 1 个辅助工厂）**

在 `MyBatisReviewRepositoryTest.java` 类体内末尾（`newReview` 工厂之前）追加测试方法，并在 `newReview` 之后追加 `newReviewWithCreated` 辅助工厂。

测试方法（追加到类体内）：

```java
    @Test
    void findPage_returns_reviews_where_target_id_or_author_id_matches() {
        repository.save(newReview(5001L, 10L, 30L, 5)); // targetId=30 命中（received）
        repository.save(newReview(5002L, 30L, 40L, 4));  // authorId=30 命中（given）
        repository.save(newReview(5003L, 11L, 40L, 3));  // 都不命中

        List<Review> page = repository.findPage(30L, new ReviewQuery(new PageQuery(1, 20)));

        assertThat(page).hasSize(2);
        assertThat(page).extracting(Review::getOrderId).containsExactlyInAnyOrder(5001L, 5002L);
        assertThat(repository.count(30L, new ReviewQuery(new PageQuery(1, 20)))).isEqualTo(2L);
    }

    @Test
    void findPage_sorts_by_created_at_desc_then_id_desc() {
        Review older = repository.save(newReviewWithCreated(5101L, 10L, 30L, 5, LocalDateTime.now().minusMinutes(10)));
        Review newer = repository.save(newReviewWithCreated(5102L, 30L, 40L, 4, LocalDateTime.now().minusMinutes(1)));
        Review sameInstant = repository.save(newReviewWithCreated(5103L, 12L, 30L, 3, newer.getCreatedAt()));

        List<Review> page = repository.findPage(30L, new ReviewQuery(new PageQuery(1, 20)));

        assertThat(page).hasSize(3);
        assertThat(page.get(0).getId()).isEqualTo(newer.getId());
        assertThat(page.get(1).getId()).isEqualTo(sameInstant.getId());
        assertThat(page.get(2).getId()).isEqualTo(older.getId());
    }

    @Test
    void findPage_applies_limit_and_offset_for_pagination() {
        for (int i = 0; i < 5; i++) {
            repository.save(newReviewWithCreated(5200L + i, 10L, 30L, 5, LocalDateTime.now().minusMinutes(5 - i)));
        }
        ReviewQuery page1 = new ReviewQuery(new PageQuery(1, 2));
        ReviewQuery page2 = new ReviewQuery(new PageQuery(2, 2));
        ReviewQuery page3 = new ReviewQuery(new PageQuery(3, 2));

        assertThat(repository.findPage(30L, page1)).hasSize(2);
        assertThat(repository.findPage(30L, page2)).hasSize(2);
        assertThat(repository.findPage(30L, page3)).hasSize(1);
        assertThat(repository.count(30L, page1)).isEqualTo(5L);
    }

    @Test
    void count_matches_findPage_total_for_target_and_author() {
        repository.save(newReview(5301L, 10L, 30L, 5)); // targetId=30
        repository.save(newReview(5302L, 30L, 40L, 4)); // authorId=30
        repository.save(newReview(5303L, 11L, 30L, 3)); // targetId=30

        ReviewQuery query = new ReviewQuery(new PageQuery(1, 20));

        assertThat(repository.count(30L, query)).isEqualTo(3L);
        assertThat(repository.findPage(30L, query)).hasSize(3);
    }

    @Test
    void findPage_and_count_return_empty_or_zero_when_targetUserId_or_query_null() {
        assertThat(repository.findPage(null, new ReviewQuery(new PageQuery(1, 20)))).isEmpty();
        assertThat(repository.findPage(30L, null)).isEmpty();
        assertThat(repository.count(null, new ReviewQuery(new PageQuery(1, 20)))).isZero();
        assertThat(repository.count(30L, null)).isZero();
    }
```

辅助工厂（追加到 `newReview` 之后）：

```java
    private static Review newReviewWithCreated(Long orderId, Long authorId, Long targetId, int rating, LocalDateTime createdAt) {
        Review review = newReview(orderId, authorId, targetId, rating);
        review.setCreatedAt(createdAt);
        return review;
    }
```

补充 import：

```java
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.review.dto.ReviewQuery;
```

> `Review`/`LocalDateTime`/`List`/`Optional` 已 import。

- [ ] **Step 2: 跑测试确认失败（编译错）**

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```

```
.\mvnw.cmd test -Dtest=MyBatisReviewRepositoryTest
```

Expected: 编译失败，`ReviewRepository` 无 `findPage`/`count` 方法。

- [ ] **Step 3: 加 ReviewRepository 接口方法**

`ReviewRepository.java` 在 `findByOrderId` 之后追加（其余方法保留）：

```java
    /**
     * 按用户与查询条件分页查询评价（target_id 或 author_id 命中 + 排序 + LIMIT/OFFSET 下推 SQL）。
     *
     * @param targetUserId 用户 ID，为 null 时返回空列表
     * @param query 查询条件，为 null 时返回空列表
     */
    List<Review> findPage(Long targetUserId, ReviewQuery query);

    /**
     * 按用户与查询条件统计匹配的评价总数（过滤下推 SQL，用于分页 total）。
     *
     * @param targetUserId 用户 ID，为 null 时返回 0
     * @param query 查询条件，为 null 时返回 0
     */
    long count(Long targetUserId, ReviewQuery query);
```

并在 import 区追加：

```java
import com.campushub.backend.review.dto.ReviewQuery;
```

- [ ] **Step 4: 实现 MyBatisReviewRepository 的 findPage/count/buildWrapper**

在 `MyBatisReviewRepository.java` 的 `findByOrderId` 方法之后追加实现：

```java
    @Override
    public List<Review> findPage(Long targetUserId, ReviewQuery query) {
        if (targetUserId == null || query == null) {
            return List.of();
        }
        LambdaQueryWrapper<ReviewEntity> wrapper = buildWrapper(targetUserId, query);
        wrapper.orderByDesc(ReviewEntity::getCreatedAt)
               .orderByDesc(ReviewEntity::getId);
        int size = query.pageQuery().size();
        long offset = (long) (query.pageQuery().page() - 1) * size;
        wrapper.last("LIMIT " + size + " OFFSET " + offset);
        return reviewMapper.selectList(wrapper).stream().map(ReviewEntity::toDomain).toList();
    }

    @Override
    public long count(Long targetUserId, ReviewQuery query) {
        if (targetUserId == null || query == null) {
            return 0L;
        }
        return reviewMapper.selectCount(buildWrapper(targetUserId, query));
    }

    private LambdaQueryWrapper<ReviewEntity> buildWrapper(Long targetUserId, ReviewQuery query) {
        LambdaQueryWrapper<ReviewEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.eq(ReviewEntity::getTargetId, targetUserId)
            .or().eq(ReviewEntity::getAuthorId, targetUserId));
        return wrapper;
    }
```

在 import 区追加：

```java
import com.campushub.backend.review.dto.ReviewQuery;
```

> `LambdaQueryWrapper`/`ReviewEntity`/`ReviewMapper` 已 import。

- [ ] **Step 5: 跑 Repository 测试确认通过**

```
.\mvnw.cmd test -Dtest=MyBatisReviewRepositoryTest
```

Expected: PASS，`MyBatisReviewRepositoryTest` 全部用例（含原有 8 + 新增 5 = 13 个）绿。

- [ ] **Step 6: 跑全量测试确认无回归**

```
.\mvnw.cmd test
```

Expected: 全绿，测试数 ≥ 189 + 5（新增）= 194。`ReviewApplicationServiceImplTest` 此刻仍用旧 `listUserReviews`（调 `findByTargetId`+`findByAuthorId`），两方法保留故仍绿。

> 若输出超 2000 行被截断写到 tool-output 文件，用 Grep 工具搜该文件确认无 `BUILD FAILURE`/`<<< FAILURE!`/`<<< ERROR!`，并搜 `Tests run:` 确认总数。

- [ ] **Step 7: Commit**

workdir=`D:\workspace\sec-ii-2026`：

```
git add backend/src/main/java/com/campushub/backend/review/repository/ReviewRepository.java backend/src/main/java/com/campushub/backend/review/repository/MyBatisReviewRepository.java backend/src/test/java/com/campushub/backend/review/repository/MyBatisReviewRepositoryTest.java
```

```
git commit -m "refactor(review): push user reviews list query down to SQL via findPage/count in ReviewRepository"
```

---

### Task 2: ReviewApplicationServiceImpl.listUserReviews 改用 findPage/count

**Files:**
- Modify: `backend/src/main/java/com/campushub/backend/review/service/ReviewApplicationServiceImpl.java:87-107`（`listUserReviews` 方法体）+ import 区
- Test: `backend/src/test/java/com/campushub/backend/review/service/ReviewApplicationServiceImplTest.java`（不改，作回归护栏）

**Interfaces:**
- Consumes: Task 1 的 `ReviewRepository.findPage(Long targetUserId, ReviewQuery) -> List<Review>` + `ReviewRepository.count(Long targetUserId, ReviewQuery) -> long`。
- Produces: `ReviewApplicationService.listUserReviews(Long targetUserId, ReviewQuery query) -> PageResponse<ReviewResponse>`（签名不变，行为等价但走 SQL 下推）。

- [ ] **Step 1: 改 listUserReviews 方法体**

替换 `ReviewApplicationServiceImpl.java:87-107` 的 `listUserReviews` 方法为：

```java
    @Override
    public PageResponse<ReviewResponse> listUserReviews(Long targetUserId, ReviewQuery query) {
        if (targetUserId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "targetUserId must not be null");
        }
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "review query must not be null");
        }
        List<Review> reviews = reviewRepository.findPage(targetUserId, query);
        List<ReviewResponse> items = reviews.stream().map(ReviewResponse::from).toList();
        long total = reviewRepository.count(targetUserId, query);
        int page = query.pageQuery().page();
        int size = query.pageQuery().size();
        return new PageResponse<>(items, page, size, total);
    }
```

- [ ] **Step 2: import 处理**

从 `ReviewApplicationServiceImpl.java` import 区**新增**：

```java
import com.campushub.backend.review.dto.ReviewQuery;
```

> `Comparator` import 保留——`recalculateCreditScore`（:115 `Comparator.comparing(...)`）仍用（grep 已确认）。`java.util.stream.Stream` 用全限定名（:96 `java.util.stream.Stream.concat`），无 import，删 `Stream.concat` 后无影响（无需删 import）。`List` 保留（新 `listUserReviews` + `recalculateCreditScore` 用）。

- [ ] **Step 3: 跑 Service 测试确认 listUserReviews 回归绿**

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```

```
.\mvnw.cmd test -Dtest=ReviewApplicationServiceImplTest
```

Expected: PASS，含 `listUserReviews` 用例及 `submit`/`recalculateCreditScore` 全绿。

- [ ] **Step 4: 跑全量测试确认不回归**

```
.\mvnw.cmd test
```

Expected: 全绿，测试数 = Task 1 Step 6 的数量（本 task 不新增测试，仅重构）。

- [ ] **Step 5: Commit**

workdir=`D:\workspace\sec-ii-2026`：

```
git add backend/src/main/java/com/campushub/backend/review/service/ReviewApplicationServiceImpl.java
```

```
git commit -m "refactor(review): switch ReviewApplicationServiceImpl.listUserReviews to SQL-pushed findPage/count and drop in-memory concat/sort"
```

---

## Self-Review

**1. Spec 覆盖**
- §4.1 接口扩展（findPage/count 风格 A，targetUserId+query）→ Task 1 Step 3。✓
- §4.2 MyBatisReviewRepository 实现（buildWrapper eq(target_id) OR eq(author_id) / findPage orderByDesc / count selectCount）→ Task 1 Step 4。✓
- §4.3 过滤映射（两路 concat → OR 单次）→ Task 1 Step 4 `buildWrapper`。✓
- §4.4 排序（created_at DESC + id DESC tie-breaker + NULL 无问题）→ Task 1 Step 4 `orderByDesc(createdAt).orderByDesc(id)`。✓
- §4.5 Service 改造 → Task 2 Step 1。✓
- §4.6 import 清理（Comparator 保留 / Stream 全限定名无 import / 新增 ReviewQuery import）→ Task 2 Step 2。✓
- §4.7 now 一致性 → 无需代码动作。✓
- §5 影响面 → File Structure 表。✓
- §6 测试策略（回归护栏 + 新增 5）→ Task 1 Step 1 + Task 2 Step 3。✓
- §7 验收（mvn 全绿 / listUserReviews 不再 findByTargetId+findByAuthorId/Stream.concat/Comparator(在 listUserReviews)/subList / findPage+count 暴露 / 契约不变 / recalculateCreditScore 不变 / findByTargetId+findByAuthorId 保留）→ Task 1 Step 5-6 + Task 2 Step 3-4。✓
- §8 风险（OR index merge / 两路与 OR 等价 / id DESC tie-breaker / Comparator 保留 / Stream 无 import / LIMIT 拼接）→ spec 已记。✓

**2. 占位符扫描**：无 TBD/TODO；所有代码块为可直接落地实现。✓

**3. 类型一致性**：`findPage(Long targetUserId, ReviewQuery) -> List<Review>` / `count(Long targetUserId, ReviewQuery) -> long` 在 Task 1（接口+实现）与 Task 2（`reviewRepository.findPage(targetUserId, query)` + `count(targetUserId, query)`）签名一致；`ReviewQuery(PageQuery pageQuery)` 与 2B.2 `NotificationQuery(boolean unreadOnly, PageQuery pageQuery)` 同 `Query` 后缀风格；`ReviewEntity` 字段（`targetId`/`authorId`/`createdAt`/`id`）与 spec 及源码 `@TableField` 一致。✓

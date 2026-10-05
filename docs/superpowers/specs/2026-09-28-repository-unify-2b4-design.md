# 仓储层统一阶段 2B.4 设计（review listUserReviews SQL 下推）

- 日期：2026-09-28
- 状态：待评审（用户已授权直接执行）
- 分支：`refactor/optimization`（HEAD `323c0c2`，2B.3 全部完成，测试 189/189）
- 上游 spec：`docs/superpowers/specs/2026-09-28-repository-unify-2b-design.md`（2B 总体，本文件为其第 4 子阶段）
- 范围：`ReviewRepository` 扩 `findPage`/`count` + `MyBatisReviewRepository` 实现 + `ReviewApplicationServiceImpl.listUserReviews` 下沉 SQL

## 1. 背景与现状（探索已核实）

### 1.1 现状定位（2B 总体 spec #8）

`ReviewApplicationServiceImpl.listUserReviews`（`backend/src/main/java/com/campushub/backend/review/service/ReviewApplicationServiceImpl.java:87-107`）当前：

```java
List<Review> received = reviewRepository.findByTargetId(targetUserId);
List<Review> given = reviewRepository.findByAuthorId(targetUserId);
List<Review> reviews = java.util.stream.Stream.concat(received.stream(), given.stream())
    .sorted(Comparator.comparing(Review::getCreatedAt).reversed())
    .toList();
int page = query.pageQuery().page();
int size = query.pageQuery().size();
int fromIndex = Math.max(0, (page - 1) * size);
int toIndex = Math.min(reviews.size(), fromIndex + size);
List<ReviewResponse> items = fromIndex >= reviews.size()
    ? List.of()
    : reviews.subList(fromIndex, toIndex).stream().map(ReviewResponse::from).toList();
return new PageResponse<>(items, page, size, reviews.size());
```

即 `findByTargetId`（收到的评价）+ `findByAuthorId`（发出的评价）两次查询 → `Stream.concat` 合并 → 内存 `createdAt DESC` 排序 → `subList` 内存分页 → `reviews.size()` 作 total。无 SQL 级 `orderBy`/`LIMIT`/`count`。

### 1.2 两路合并为单次 SQL（关键设计）

`received`（`target_id=targetUserId`）与 `given`（`author_id=targetUserId`）合并。`Review` 语义中 `targetId` 是被评价者，`authorId` 是评价者，二者不可能相等（`submit` 方法 :70 `targetId = order.getPublisherId().equals(operatorId) ? order.getAccepterId() : order.getPublisherId()`，必为对方），故 `received ∪ given` 无重复。

下推用单次 SQL：`target_id = :uid OR author_id = :uid`。等价于两路 concat（无重复，无需 distinct）。

### 1.3 与 2B.2 notification 的异同

- 同：`userId` 独立参数（`listUserReviews(Long targetUserId, ReviewQuery query)`，`targetUserId` 不入 query）→ `findPage(Long targetUserId, ReviewQuery query)` / `count(Long targetUserId, ReviewQuery query)`；`ReviewQuery` 仅 `PageQuery`；固定排序；无层次反转（`ReviewQuery`/`ReviewRepository` 同模块）。
- 异：2B.2 单条件 `eq(user_id)`；2B.4 双条件 `eq(target_id) OR eq(author_id)`。

### 1.4 `findByTargetId`/`findByAuthorId` 保留

- `findByTargetId`：被 `listUserReviews`（:94，本阶段下推）+ `recalculateCreditScore`（:114）+ `AdminApplicationServiceImpl.collectReviewActivity`/`listAllReviews`（2B.3d dailyActiveUsers）调用，**保留**。
- `findByAuthorId`：仅 `listUserReviews`（:95）调用。下推后 main 不再调，但接口保留（test 仍测，2B 总体非目标删接口；后续清理阶段评估）。

### 1.5 调用方契约

`listUserReviews` 调用方：`ReviewController`（拿 `PageResponse<ReviewResponse>` 返回前端）。2B.4 仅改 Service 内部，`PageResponse<ReviewResponse>` 结构不变，Controller 与前端契约不动。

## 2. 目标 / 非目标

### 目标
1. `ReviewRepository` 新增 `findPage(Long targetUserId, ReviewQuery query)` + `count(Long targetUserId, ReviewQuery query)`（风格 A，与 2B.1/2B.2/2B.3a 一致），`save`/`findByOrderIdAndAuthorId`/`findByTargetId`/`findByAuthorId`/`findByOrderId` 保留。
2. `MyBatisReviewRepository` 实现：私有 `buildWrapper(targetUserId, query)`（`eq(target_id) OR eq(author_id)`，供 `findPage`/`count` 共用）；`findPage` 加 `orderByDesc(createdAt).orderByDesc(id)` + `LIMIT/OFFSET`；`count` 用 `selectCount`。
3. `ReviewApplicationServiceImpl.listUserReviews` 改用 `findPage` + `count`，删除 `findByTargetId`+`findByAuthorId` 两路 + `Stream.concat` + 内存排序 + `subList` 分页。
4. 删除仅服务于旧 `listUserReviews` 的失效 import（`java.util.Comparator`）。
5. 行为零回归：`PageResponse`/`ReviewResponse` 字段与语义不变；现有 `listUserReviews` 用例断言不变且全绿。

### 非目标
- 不删 `findByTargetId`/`findByAuthorId`（1.4，recalculateCreditScore/dailyActiveUsers 用）。
- 不改 `ReviewQuery`/`Review`/`ReviewEntity`/`ReviewResponse`/`ReviewMapper`/schema。
- 不动 `ReviewController`/前端契约。
- 不动 `submit`/`recalculateCreditScore`（:110-128）。
- 不动 `AdminApplicationServiceImpl` 双构造函数与 `@Autowired(required=false)`（子项目4）。
- 不修 N+1（2C）。

## 3. 约束（继承 2B 总体 + 主 spec）
- 前端兼容契约：`PageResponse(items, page, size, total)`/`ReviewResponse` 字段结构不变；API 行为不变。
- 测试全绿：基线 189/189（2B.3d 后），每子阶段不回归。
- 工作分支 `refactor/optimization`。
- Repository 接口扩方法，`MyBatisReviewRepository` 实现；无 XML mapper，全 `BaseMapper` + `LambdaQueryWrapper`。
- H2 MySQL 兼容模式（测试）与 MySQL（生产）行为一致。

## 4. 设计

### 4.1 Repository 接口扩展（风格 A，与 2B.2 一致）

`ReviewRepository.java` 新增两方法（`save`/`findByOrderIdAndAuthorId`/`findByTargetId`/`findByAuthorId`/`findByOrderId` 保留）：

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

### 4.2 MyBatisReviewRepository 实现

新增私有 `buildWrapper(targetUserId, query)`（纯过滤，供 `findPage`/`count` 共用）。`findPage` 内 `orderByDesc(createdAt).orderByDesc(id)`（固定排序，无 `applySort`）。

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

> `LambdaQueryWrapper`/`ReviewEntity`/`ReviewMapper` 已 import。`buildWrapper` 用 `wrapper.and(w -> w.eq(target_id).or().eq(author_id))` 生成 `((target_id = ?) OR (author_id = ?))`，等价两路 concat。

### 4.3 过滤下沉映射

| # | 内存逻辑（现状） | SQL 下推 | 边界对齐 |
|---|---|---|---|
| 1 | `findByTargetId(targetUserId)` + `findByAuthorId(targetUserId)` 两路 concat | `and(w -> w.eq(target_id, uid).or().eq(author_id, uid))` → `WHERE (target_id = ? OR author_id = ?)` | 单次 SQL 替两路；`targetId ≠ authorId`（submit 语义）故无重复，`Stream.concat` 与 `OR` 等价；`targetUserId == null` 由 `findPage` 入口守卫返回空 |

### 4.4 排序下沉映射

| 内存逻辑（现状） | SQL 下推 | NULL 处理 |
|---|---|---|
| `Comparator.comparing(Review::getCreatedAt).reversed()` | `ORDER BY created_at DESC, id DESC` | `created_at` 为 `DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP`（`schema-review.sql:14`），无 NULL 位置问题 |

**id tie-breaker**：内存原 `reversed()` 只反转 `createdAt`，无 `thenComparing(id)`，同 `createdAt` 行顺序未定义。2B.4 加 `orderByDesc(id)` tie-breaker 保证分页确定性（CodeRabbit fix 原则，与 2B.1/2B.2/2B.3 一致）。加 id DESC 不改变可观察行为。

### 4.5 Service 改造

`ReviewApplicationServiceImpl.listUserReviews` 改为：

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

`ReviewResponse.from` 行为不变。

### 4.6 import 清理

删除（仅旧 `listUserReviews` 的 `sorted(Comparator.comparing(...))` 用，grep 确认 `Comparator` 在该文件无其他引用；`recalculateCreditScore:115` 也用 `Comparator.comparing(...)`，**保留**）：

> ⚠️ `recalculateCreditScore:115` 用 `Comparator.comparing(Review::getCreatedAt, nullsLast(...))`，故 `Comparator` import **保留**。2B.4 仅删 `listUserReviews` 的 `Comparator.comparing(...).reversed()`，但 `recalculateCreditScore` 仍用。

故 import 区**仅新增**（不删）：

```java
import com.campushub.backend.review.dto.ReviewQuery;
```

> `java.util.stream.Stream` 仅旧 `listUserReviews` 用 `Stream.concat`——删 `Stream.concat` 后，`Stream` import 失效，可删（编译通过为准绳，grep 确认无其他引用）。

需 grep 确认 `java.util.stream.Stream` 是否仅 `listUserReviews` 用。若是，删 `import java.util.stream.Stream;`。

### 4.7 `now` 一致性

review 查询无 `now`/时间边界依赖，`findPage`/`count` 无 `now` 取值，无毫秒窗口问题。

## 5. 影响面

### 改动文件
| 文件 | 操作 |
|---|---|
| `review/repository/ReviewRepository.java` | 加 `findPage(Long, ReviewQuery)` + `count(Long, ReviewQuery)` + import |
| `review/repository/MyBatisReviewRepository.java` | 实现 `findPage` + `count` + 私有 `buildWrapper` + import |
| `review/service/ReviewApplicationServiceImpl.java` | `listUserReviews` 改用 `findPage`+`count`；删 `Stream.concat` + 失效 import（Stream） |

### 测试文件
| 文件 | 操作 |
|---|---|
| `review/repository/MyBatisReviewRepositoryTest.java` | **新增** `findPage`/`count` 用例 |
| `review/service/ReviewApplicationServiceImplTest.java` | **不改**（`listUserReviews` 用例作回归护栏） |

### 不受影响
- `ReviewQuery`/`Review`/`ReviewEntity`/`ReviewResponse`/`ReviewMapper`/schema
- `ReviewController`/前端契约
- `submit`/`recalculateCreditScore`（不动）
- `findByTargetId`/`findByAuthorId`/`findByOrderIdAndAuthorId`/`findByOrderId`（保留）
- 索引（`idx_review_target`/`idx_review_author` 已覆盖下推查询；`OR` 查询优化留 DB）

## 6. 测试策略

### 6.1 回归护栏（不改，须全绿）
- `ReviewApplicationServiceImplTest` 现有 `listUserReviews` 用例（验证 received+given 合并 + createdAt DESC 排序 + 分页）。下推后 `WHERE (target_id = ? OR author_id = ?) ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?` 等价。

### 6.2 新增 `MyBatisReviewRepositoryTest` 用例（@MybatisPlusTest + @Sql schema-review.sql）

> 需读现有 `MyBatisReviewRepositoryTest` 确认切片配置与工厂方法（`newReview` 等）。

1. `findPage` 返回 target_id 或 author_id 命中的 review（插入 target 命中 + author 命中 + 都不命中，验证前两个返回）。
2. `findPage` 排序 `created_at DESC + id DESC`（插入不同 createdAt + 同 createdAt 验证 id 降序 tie-breaker）。
3. `findPage` 分页 `LIMIT/OFFSET`：插入 5 条，`PageQuery(1,2)`/`(2,2)`/`(3,2)` 返回 2/2/1。
4. `findPage` 无重复（插入 1 条 target_id==author_id==uid 的边界——若 schema 允许；验证 OR 不返回重复行。但 review 语义 targetId≠authorId，此用例可跳过或验证正常 case 无重复）。
5. `count` 与 `findPage`（同条件、不限分页）总数一致。
6. `findPage`/`count` null 防御：`targetUserId==null` 或 `query==null` 返回空/0。

> 现有 `MyBatisReviewRepositoryTest` 的 `save`/`findByOrderIdAndAuthorId`/`findByTargetId`/`findByAuthorId`/`findByOrderId` 用例保留不动。

## 7. 验收标准
1. `.\mvnw.cmd test`（`JAVA_HOME` 指向 JDK 21，workdir=backend）全绿，测试数 ≥ 189 + 新增（约 6 个 findPage/count 用例）。
2. `ReviewApplicationServiceImpl.listUserReviews` 不再出现 `reviewRepository.findByTargetId`（在 listUserReviews 体内）、`reviewRepository.findByAuthorId`、`Stream.concat`、`Comparator`（在 listUserReviews 体内）、`subList`。
3. `ReviewRepository` 暴露 `findPage(Long, ReviewQuery)` + `count(Long, ReviewQuery)`；`MyBatisReviewRepository` 实现含 `buildWrapper`（`eq(target_id) OR eq(author_id)`）。
4. `PageResponse`/`ReviewResponse` 字段不变；现有 `listUserReviews` 用例断言不变且全绿。
5. `submit`/`recalculateCreditScore` 行为不变（未触动；`recalculateCreditScore` 仍用 `findByTargetId` + `Comparator`）。
6. `findByTargetId`/`findByAuthorId` 保留。
7. Controller 及前端契约未触动。

## 8. 风险与对策
| 风险 | 对策 |
|---|---|
| `OR` 查询在 `target_id`/`author_id` 两索引间无法高效用索引（需 index merge） | MySQL/H2 支持 index merge；数据量小可接受全表扫描；2D 索引已评估（`idx_review_target`/`idx_review_author`）；2B.4 不动 schema |
| 两路 concat 与 OR 单次查询结果顺序不一致 | `Stream.concat(received, given)` 原顺序被 `sorted(createdAt DESC)` 重排；OR + `ORDER BY created_at DESC, id DESC` 等价（无重复，排序后顺序确定） |
| 加 `id DESC` tie-breaker 改变行为 | 内存原无 `thenComparing(id)`，同 createdAt 顺序未定义；加 id DESC 仅消除不确定性（4.4） |
| `Comparator` import 误删 | grep 确认 `recalculateCreditScore:115` 仍用，保留（4.6） |
| `Stream` import 失效 | grep 确认仅旧 `listUserReviews` 用 `Stream.concat`；删后编译通过为准绳（4.6） |
| `last("LIMIT ... OFFSET ...")` 拼接 | 入参 `int`/`long`（`PageQuery` 已校验范围），无注入风险 |

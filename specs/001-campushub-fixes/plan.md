# 实现计划：CampusHub 审查问题修复（F1-F26）

**Plan ID**: `001-campushub-fixes`
**关联 Spec**: `specs/001-campushub-fixes/spec.md`
**创建日期**: 2026-10-08
**状态**: Ready for Implementation
**作者**: 基于 spec.md 澄清结论编排

---

## 1. 技术方案概述

本次修复 **以前端为主、后端最小调整**，覆盖审查发现的 26 项功能性问题（严重 4 / 中等 12 / 轻微 10）。

- **前端栈**：Vue 3.5 + TypeScript 6 + Vite 8 + Pinia 3，纯手写 CSS（无第三方 UI 库）
- **后端栈**：Spring Boot 3.5 + Java 21 + MyBatis-Plus + MySQL
- **策略**：复用现有组件（`ImageUploader`、`useConfirm`/`useAlert`、`formatDateTime`/`formatRelativeTime`、`handleError`），不引入新依赖；前端单点修复优先于后端增改；后端仅 5 处受控调整（F1/F2/F4/F6/F15），其中 F4/F6 经澄清改为纯前端修复，实际后端代码改动落在 **F1、F2、F15** 三处
- **验证**：前端用 Playwright 实跑；后端用既有单元测试 + H2 集成测试覆盖

---

## 2. 架构约束（不可破坏）

1. **基线保护**：spec 第 7 节已验证的后端业务流转（状态机、并发锁、信用分计算、仲裁、通知、AI 草稿、四种互动模式）行为不得改变
2. **最小改动原则**：每个 F 项只动 spec 列明的文件，不扩散修改；不改 API 契约（除 F1/F2/F15 列明的后端字段/查询调整）
3. **复用现有组件**：
   - 凭证图片上传 → `frontend/src/components/ImageUploader.vue`
   - 确认/提示对话框 → `frontend/src/composables/useDialog.ts`（`useConfirm(title, message, options?) → Promise<boolean>`、`useAlert(title, message, options?) → Promise<void>`）
   - 日期格式化 → `frontend/src/utils/format.ts`（`formatDateTime`、`formatRelativeTime`、`formatDate`）
   - 错误翻译 → `frontend/src/utils/errorHandler.ts`（`handleError`）
4. **业务代码位置**：所有业务代码改动落在现有 `backend/`、`frontend/` 目录，**不**在 `specs/` 或 `specs/001-campushub-fixes/` 下生成业务代码（spec/plan 文档除外）
5. **不引入新表**（除 F1 在 `ord_order` 表内增列）与不做线上数据迁移（F15 仅调预置账号初始余额）

---

## 3. 分阶段实现计划

按 **严重 → 中等 → 轻微** 分三批，阶段一内部按依赖排序（F1 横跨前后端，最先做并单独验证）。

### 阶段一：严重问题（F1-F4）
| 顺序 | 编号 | 主题 | 跨端 | 验收重点 |
|------|------|------|------|----------|
| 1 | F1 | 凭证图片上传 + 后端字段持久化 | 是 | 提交流转、发布者可见凭证 |
| 2 | F2 | 双向评价查询 | 是（后端） | 刷新后"我发出的评价"非空 |
| 3 | F4 | 删除 fetchBalance | 否（前端） | 余额校验无 404 |
| 4 | F3 | 订单列表操作错误处理 | 否（前端） | loading + 成功/失败反馈 |

**检查点**：阶段一完成后跑一次完整 Playwright 主流程（发布→接单→完成凭证→确认→评价），确认基线未破坏。

### 阶段二：中等问题（F5-F16）
按模块聚成子批次以减少上下文切换：
- **批次 2a · 校园中心 Store 改造**：F14（并行化）、F8（401 登出）、F12（409 翻译）、F21（去冗余 GET）、F19（submitReview 去重）——均落在 `stores/campusHub.ts`
- **批次 2b · 详情页交互**：F5（AI 回填）、F10（超员前置）、F11（防重复点击）、F24（等待文案）——`DemandDetailView.vue`/`OrderDetailView.vue`
- **批次 2c · 字段映射与脱敏**：F6（anonymous 映射）、F23（acceptStatusHint 映射）——`mapOrderRecord`/`mapDemandRecord`
- **批次 2d · 管理后台与登录**：F7（封禁确认）、F16（登录 label）
- **批次 2e · 列表与通知**：F9（通知跳转）、F13（搜索 debounce）
- **批次 2f · 后端数据一致**：F15（预置账号余额）

### 阶段三：轻微问题（F17-F26）
纯前端局部优化，可并行处理：
- F17（首页高亮/死代码）、F18（仲裁日期格式化）、F20（全部已读 loading）、F22（资料表单响应式）、F25（头像失败提示）、F26（对话框错误就近）、F11 收尾复用 submitting 模式（与阶段二 F11 同源，一并处理）

> 说明：F11 在阶段二详情页批次中已覆盖 `DemandDetailView`/`OrderDetailView`，阶段三不重复。

---

## 4. 修复项技术实现要点（F1-F26）

### F1 · 凭证图片上传 UI 与后端字段持久化【严重·跨端】

**后端改动**（5 处允许之内）：

1. `backend/src/main/resources/init_schema.sql`：`ord_order` 表在 `proof_image_count` 后新增列
   ```sql
   `proof_image_urls` json DEFAULT NULL COMMENT '完成凭证图片URL列表(1-3)',
   ```
2. `backend/.../order/repository/entity/OrderEntity.java`：新增字段
   ```java
   @TableField(value = "proof_image_urls", typeHandler = JacksonTypeHandler.class)
   private List<String> proofImageUrls;
   ```
   （MyBatis-Plus `JacksonTypeHandler` 实现 `List<String>` ↔ JSON 互转；确认 `@TableName(autoResultMap = true)` 已开启以使 typeHandler 生效）
3. `backend/.../order/domain/Order.java`：新增 `private List<String> proofImageUrls;` 字段 + getter/setter + 构造函数参数（向后兼容，加在 `proofImageCount` 之后）
4. `backend/.../order/dto/UpdateOrderStatusCommand.java`：record 增参
   ```java
   public record UpdateOrderStatusCommand(
       String targetStatus, String note, Integer proofImageCount, List<String> proofImageUrls) {}
   ```
5. `backend/.../order/service/OrderApplicationServiceImpl.java`（`validateProviderProof` 及完成流转处）：
   - 校验 `proofImageUrls` 非空且 size ∈ [1,3]，且与 `proofImageCount` 一致（保留 count 作冗余校验，二者不一致时以 urls 为准并修正 count）
   - 将 `proofImageUrls` 写入 `OrderEntity` 持久化
   - **不改变** 双重确认完成、并发锁、状态机流转逻辑
6. `backend/.../api/view/OrderView.java`：record 在 `proofImageCount` 后新增 `List<String> proofImageUrls`
7. `backend/.../api/view/OrderViewAssembler`（或对应组装器）：从 `Order` 映射 `proofImageUrls` 到 `OrderView`

**前端改动**：

1. `frontend/src/stores/campusHub.ts`（`completeOrder` 约 879-890 行）：去掉硬编码 `{targetStatus:'COMPLETED', proofImageCount:1}`，改为接收 `proofImageUrls: string[]` 参数并传入 `proofImageCount: proofImageUrls.length`
2. `frontend/src/views/OrderDetailView.vue`（231-248 行）与 `DemandDetailView.vue`（342-358 行）：
   - 引入 `ImageUploader` 组件，在"提交完成确认"动作前增加凭证上传步骤
   - 校验必须 1-3 张，不足则 `useAlert` 提示且不发起请求
   - 复用 `submitting` 模式禁用按钮
3. `frontend/src/types/campushub.ts`：`Order` 类型新增 `proofImageUrls?: string[]`
4. `frontend/.../stores/campusHub.ts`（`mapOrderRecord` 192-239 行）：映射 `proof_image_urls` → `proofImageUrls`
5. 发布者确认完成时在订单详情渲染凭证图片列表（可点击放大/新窗查看）

### F2 · fetchUserReviews 返回双向评价【严重·后端】

**后端**（`ReviewApplicationServiceImpl.listUserReviews`）：查询条件由 `targetId = userId` 改为 `targetId = userId OR reviewerId = userId`，单次请求返回双向评价。
- 前端 `reviewsGiven` 过滤逻辑（`reviewerId === currentUserId`）保持不变，刷新后因后端返回数据已含发出评价而正常显示。
- `hasReviewedResponse` 判断同步生效（SELECT_MANY 组队评价场景）。

### F3 · 订单列表操作错误处理【严重·前端】

`frontend/src/views/OrdersView.vue`（186-197 行）：
- 参照 `OrderDetailView` 同名函数补齐 `try/catch`
- 成功调 `message`（项目既有 message 机制），失败调 `handleError` 统一翻译
- 增加 `submittingOrderId` 状态（按订单 ID 隔离，避免一个订单操作禁用全部按钮），按钮 `:disabled="submittingOrderId===row.id"` + "处理中"文案
- 操作完成后刷新列表（`refreshOrders`）

### F4 · 移除 fetchBalance 404 调用【严重·前端】

`frontend/src/stores/campusHub.ts`（1262-1269 行）：
- 删除 `fetchBalance` 方法及其调用点
- 余额校验改用 `this.currentUser?.balance - frozenBalance`（注意 `currentUser` 未加载时 balance 为 undefined，需 `?? 0` 兜底）
- 不在后端补 `/users/me/balance` 接口（澄清结论 D6）

### F5 · AI 草稿回填 startTime【中等·前端】

`frontend/src/views/DemandPublishView.vue`（AI 草稿回填逻辑）：
- 检查回填映射分支，`endTime` 已正确回填而 `startTime` 漏填，定位缺失的赋值语句补上
- 边界：`startTime` 缺失/格式异常时不报错，保持空值由用户手选；与 `endTime` 倒序时不强制纠正（交由提交校验）

### F6 · isDemandAnonymous 字段映射【中等·前端】

`frontend/src/stores/campusHub.ts`（`mapOrderRecord` 192-239 行）：
- 显式映射 `anonymous` 字段（从 `record.demand?.anonymous` 或扁平字段取值）到 `order.demand.anonymous`（或顶层 `order.anonymous`）
- `OrderDetailView.vue`（167-170 行）改用映射后的 `order.anonymous`，移除 `requesterName.includes('匿名')` 兜底判断
- 不改后端（澄清结论 D7）

### F7 · 封禁/解禁确认对话框【中等·前端】

`frontend/src/views/AdminView.vue`（179-192 行 `toggleUserStatus`）：
- 调用 `useConfirm`，标题"确认封禁"/"确认解禁"，`danger: true`（封禁时）
- 确认返回 `true` 才执行 `banUser`/`unbanUser`
- 参照同文件需求拒绝弹窗写法保持风格一致

### F8 · 401 彻底登出【中等·前端】

`frontend/src/stores/campusHub.ts`（1153-1164 行 401 处理）：
- 将 `currentProfile = null` 改为调用 `this.logout()`（清 token + localStorage + 用户态）
- 防并发：可加 `isLoggingOut` 标志避免多个 401 并发触发多次 logout
- `main.ts` 启动流程同步：token 存在但 `fetchProfile` 失败时走 logout，避免带过期 token 重试

### F9 · 通知跳转分支补齐【中等·前端】

`frontend/src/views/NotificationsView.vue`（44-104 行 `openNotification`）：
- 新增分支：
  - `RESPONSE_REVIEW_RECEIVED` → 跳转关联 response/评价页
  - `PENDING_REVIEW` → 跳转待审核/待评价页
- 从 `payload` 取关联 ID（缺失时降级到 `/notifications`，不报错）

### F10 · SELECT_MANY 超员前置校验【中等·前端】

`frontend/src/views/DemandDetailView.vue`（488-503 行）：
- 勾选报名时实时判断 `selectedCount > targetParticipantCount`，超员即 `useAlert` 提示并禁止勾选/提交
- 边界：`targetParticipantCount` 为 0/1 时按实际处理

### F11 · 关键操作 loading 与防重复点击【中等·前端】

`DemandDetailView.vue`（287-308、716-724 行）、`OrderDetailView.vue`（227-229 行）：
- 复用 `submitting` 标志，按钮 `:disabled="submitting"` + "处理中"文案
- `acceptCurrentDemand`/`startOrder`/`submitCompletion` 等 async 动作在 `try` 前置 `submitting=true`，`finally` 复位

### F12 · 409 错误动态翻译【中等·前端】

`frontend/src/stores/campusHub.ts`（768-788 行 409 处理）：
- 不再统一返回"该需求已过期"，改为根据 `payload.message` 匹配映射（参考 `formatAcceptDisabledReason` 的 reasonMap 思路）：已被接单/已过期/状态冲突等
- `payload.message` 缺失或未知时走通用翻译兜底

### F13 · 搜索 debounce【中等·前端】

`frontend/src/views/DemandListView.vue`（197-209 行 watch `filters.q`）：
- 加 300ms debounce（可用项目内既有 debounce 工具或 `setTimeout` + 清理实现，不引入 lodash）
- 边界：粘贴长文本、清空、与筛选组合时仅触发一次请求

### F14 · hydrateAuthenticatedState 并行化【中等·前端】

`frontend/src/stores/campusHub.ts`（415-425 行）：
- `fetchProfile` 完成（拿到 `currentUserId`）后，将 `fetchCurrentUserReviews`/`fetchDemands`/`fetchOrders`/`fetchNotifications` 改为 `Promise.all` 并行
- 单个失败用 `Promise.allSettled` 或 try/catch 隔离，避免一个失败拖垮全部
- `fetchProfile` 失败时短路（无 currentUserId 则不发后续）

### F15 · 预置账号余额一致【中等·后端】

**后端改动**（5 处允许之内）：

1. `backend/src/main/resources/init_schema.sql`：3 个 `sys_user` INSERT（admin/TEST001/TEST002）在列清单中显式增加 `balance` 并设值
   ```sql
   INSERT INTO `sys_user` (`email`, `student_id`, `password_hash`, `nickname`, `role`, `balance`)
   VALUES ('admin@edu.cn', 'ADMIN001', '...', '超级管理员', 'ADMIN', 100.00)
   ON DUPLICATE KEY UPDATE id=id;
   ```
   TEST001/TEST002 同理（balance=100.00）
2. `backend/.../api/DemoDataInitializer.java`（第 35 行）：admin 的 balance 由 `BigDecimal.ZERO` 改为 `new BigDecimal("100.00")`。**注意**：第 34 行的 `100` 是 creditScore（信用分），保持不动，不要误改

> 已有测试数据库若余额为 0：本地重建 H2/MySQL 即可，不做线上迁移。

### F16 · 登录 label 兼容邮箱【中等·前端】

`frontend/src/views/AuthView.vue`：
- label 与 placeholder 由"学号"改为"学号/邮箱"
- 后端 `AuthApplicationServiceImpl` loginId 已支持学号或邮箱，无需改动

### F17 · 移除 HomeView 死代码并修复首页高亮【轻微·前端】

`frontend/src/views/HomeView.vue`（1-7 行）、`router/index.ts`（5-8 行）、`App.vue` 导航高亮逻辑：
- 推荐方案：删除空 `HomeView.vue`，路由 `/` 直接指向 `DemandListView`（`redirect: '/demands'` 或 component 复用）
- 备选方案（若 `/` 需独立渲染）：在 `App.vue` 高亮判断中兼容 `route.path === '/'`
- 二选一，优先删死代码

### F18 · 仲裁订单 createdAt 格式化【轻微·前端】

`frontend/src/views/AdminView.vue`（416 行）：
- `order.createdAt` 原始 ISO 字符串 → `formatDateTime(order.createdAt)`
- 复用 `frontend/src/utils/format.ts` 的 `formatDateTime`（已实现，输出 `MM-DD HH:mm` 中文格式）

### F19 · submitReview 去重【轻微·前端】

`frontend/src/stores/campusHub.ts`（959-977 行 `submitReview`）：
- 参照 `submitReviewForResponse` 的去重判断（按 orderId + reviewerId 或唯一键），已存在则不 unshift
- 保证两函数行为一致

### F20 · 全部标记已读 loading + await【轻微·前端】

`frontend/src/views/NotificationsView.vue`（122 行）：
- 增加 `markingAllRead` 状态，按钮 `:disabled="markingAllRead"` + loading 文案
- `await markAllAsRead()` 完成后再恢复

### F21 · updateProfile 去重复请求【轻微·前端】

`frontend/src/stores/campusHub.ts`（547-565 行）：
- PUT `/users/me` 响应已写入 `currentProfile`，删除紧随其后的冗余 GET `/users/me`
- 边界：PUT 响应体缺字段时降级为发一次 GET（用响应体字段缺失判断）

### F22 · ProfileEditView 表单响应式同步【轻微·前端】

`frontend/src/views/ProfileEditView.vue`（19-22 行）：
- `profileForm` 不再初始化时快照，改为 `watch(() => store.currentUser, ...)` 响应式回填，或 `onMounted` 的 `fetchProfile` 完成后显式回填
- 边界：`fetchProfile` 失败时表单保持空/上次值，不阻塞

### F23 · acceptStatusHint 字段映射【轻微·前端】

`frontend/src/types/campushub.ts`（80 行已有类型）、`stores/campusHub.ts`（`mapDemandRecord` 121-161 行）：
- `mapDemandRecord` 显式映射 `acceptStatusHint`（从后端响应字段取值）
- `DemandDetailView` 中该字段不再恒为 null，UI 提示与实际接单状态一致

### F24 · 等待提示文案修正【轻微·前端】

`frontend/src/views/DemandDetailView.vue`（742 行）：
- `currentUserConfirmedCompletion || providerConfirmed` 时按角色（requester/provider）分支显示不同文案
- 处理边界：双方均未确认 / 仅一方确认 / 双方均确认

### F25 · 头像更新失败提示统一【轻微·前端】

`frontend/src/views/ProfileView.vue`（29 行）：
- 原生 `alert` → `useAlert('头像更新失败', err.message)`（统一风格）
- 参照项目其他失败提示写法

### F26 · 对话框错误就近显示【轻微·前端】

`frontend/src/views/AdminView.vue`（447-448、457-481 行 拒绝/仲裁/删除对话框）：
- 对话框内增加错误显示区域（局部 `dialogError` 状态），提交失败时就近渲染
- 对话框关闭时清空 `dialogError`，避免残留
- 处理多个对话框同时打开场景（按对话框 ID/key 隔离错误状态）

---

## 5. F1 凭证图片持久化表结构方案（推荐）

### 方案对比

| 维度 | 方案 A：`ord_order` 表新增 JSON 列 | 方案 B：新建关联表 `ord_order_proof_image` |
|------|-----------------------------------|---------------------------------------------|
| 改动面 | 1 列 + 实体字段 + typeHandler | 新表 + Mapper + 关联查询 |
| 读写复杂度 | 单行读写，JSON 直转 `List<String>` | 需 join 或独立查询 |
| 扩展性 | 仅适合固定少量图片 | 适合可编辑/可追加图片 |
| 一致性 | 与订单同行，天然一致 | 需事务保证 |
| 适配 F1 需求（1-3 张、提交即定） | ✅ 完美匹配 | 过度设计 |

### 推荐：方案 A

```sql
ALTER TABLE `ord_order`
  ADD COLUMN `proof_image_urls` json DEFAULT NULL
  COMMENT '完成凭证图片URL列表(1-3)'
  AFTER `proof_image_count`;
```

- **实体**：`OrderEntity` 新增 `@TableField(value="proof_image_urls", typeHandler=JacksonTypeHandler.class) private List<String> proofImageUrls;`，并确认类上 `@TableName(value="ord_order", autoResultMap=true)`（typeHandler 在 `autoResultMap=true` 时才对查询生效）
- **领域对象**：`Order` 新增 `proofImageUrls` 字段与构造参数
- **DTO**：`UpdateOrderStatusCommand` 新增 `proofImageUrls` 参数
- **View**：`OrderView` 新增 `proofImageUrls`，组装器映射
- **校验**：`OrderApplicationServiceImpl.validateProviderProof` 校验 `proofImageUrls` size ∈ [1,3]；`proofImageCount` 保留作冗余，不一致时以 urls 为准并修正 count（不改变双重确认/锁/状态机）
- **不建关联表**理由：凭证图片 1-3 张、提交后不可编辑、与订单强 1:1 归属，JSON 列最简且一致；未来若支持凭证追加/删除再拆表

---

## 6. 风险与取舍

| 风险 | 影响 | 缓解 |
|------|------|------|
| F1 改 `ord_order` 表结构，MyBatis-Plus typeHandler 配置不当导致查询不回填 | 凭证图片发布者看不到 | `autoResultMap=true` 必须开启；H2 集成测试覆盖完成→查询→View 映射链路 |
| F2 双向查询可能放大结果集（用户评价很多） | 列表性能 | 当前量级无分页可接受；如需后续加分页，本次不做 |
| F14 并行化后单个失败处理不当拖垮全部 | 登录后部分模块空白 | 用 `Promise.allSettled` 或逐个 try/catch 隔离；`fetchProfile` 失败短路 |
| F8 并发 401 触发多次 logout | 重复跳登录页 | `isLoggingOut` 标志幂等化 |
| F15 调余额与"预置账号 0 余额是设计意图"冲突 | 演示/测试预期不一致 | 已在 spec 标为需团队确认二选一；默认按 README 一致（100.00），团队确认后可改 README |
| F17 删 HomeView 可能影响路由守卫/嵌套路由 | 访问 `/` 404 | 路由 `/` 改 redirect 到 `/demands`，保留守卫逻辑 |
| F1/F2/F15 后端改动触及已验证基线 | 状态机/锁/信用分被误改 | 严格限定改动文件；改后重跑后端全量测试 + Playwright 主流程 |

**取舍**：
- F1 选 JSON 列而非关联表，牺牲"未来可编辑性"换"最小改动与一致性"
- F2 选后端双向查询（方案 A）而非前端双接口，牺牲单次查询语义纯净换"接口数少、状态不分裂"
- F4/F6 选纯前端修复，牺牲"后端字段显式"换"不动后端"
- F15 默认调余额而非改 README，优先满足演示可用性

---

## 7. 测试策略

### 7.1 后端（F1/F2/F15）

- **单元测试**：
  - F1：`OrderApplicationServiceImplTest` 覆盖 `validateProviderProof`（0/1/3/4 张、urls 与 count 不一致）；完成流转写入 `proofImageUrls` 后查询回读
  - F2：`ReviewApplicationServiceImplTest` 覆盖 `listUserReviews`（仅收到的、仅发出的、双向都有、都没有）
  - F15：`DemoDataInitializerTest` 断言 admin 余额为 100.00
- **H2 集成测试**：F1 完整链路——提交完成（带图片）→ 状态流转 → `OrderView` 返回 `proofImageUrls`；F2 评价查询端到端
- **基线回归**：跑全量 `./mvnw test`，确认状态机/并发锁/信用分/仲裁/通知测试全绿（未破坏）

### 7.2 前端（全量 Playwright 实跑）

- **F1**：接单方上传 1-3 张凭证 → 提交完成 → 发布者详情可见凭证图
- **F2**：用户 A 评价 B 后刷新主页 → "我发出的评价"非空；SELECT_MANY 组队评价重复点击禁用
- **F3/F11**：订单列表/详情操作 → 按钮 disabled + loading + 成功/失败反馈
- **F4**：发布带悬赏需求 → 网络面板无 `/user/balance` 404
- **F5**：AI 草稿生成 → 开始/结束时间均回填
- **F6/F23**：匿名需求详情脱敏生效；`acceptStatusHint` 提示正确
- **F7**：封禁/解禁弹确认框
- **F8**：token 过期 → 彻底登出，无中间态
- **F9**：`RESPONSE_REVIEW_RECEIVED`/`PENDING_REVIEW` 通知跳转正确
- **F10**：SELECT_MANY 超员前置提示
- **F12**：接单 409 提示动态翻译
- **F13**：搜索快速输入 → 300ms 后单次请求
- **F14**：登录后并行加载（网络面板并发请求）
- **F15**：预置账号余额 100.00，可发布带悬赏需求
- **F16**：登录表单 label "学号/邮箱"
- **F17-F26**：逐项按 AC 验收（首页高亮、仲裁日期格式、去重、全部已读 loading、去冗余 GET、表单响应式、字段映射、等待文案、头像失败提示、对话框错误就近）

### 7.3 构建

- 后端：`cd backend && ./mvnw clean package`（构建+测试全绿）
- 前端：`cd frontend && npm run build`（vue-tsc 类型检查 + vite 构建通过，因 F1/F6/F23 类型定义有改动）

---

## 8. 自主决策记录

| # | 决策项 | 选择 | 理由 |
|---|--------|------|------|
| P1 | plan 文件位置 | `specs/001-campushub-fixes/plan.md` | 与 spec 同目录，便于追溯；业务代码不落 specs/ |
| P2 | spec 脚本/模板加载 | 跳过 | `.zhanlu/spec/` 子目录不存在（仅有 feature.json/editor.json 等），按轻量化模式跳过 |
| P3 | 实现分批策略 | 严重→中等→轻微，阶段二按模块聚子批次 | 阻断性问题优先；同模块文件聚集减少上下文切换 |
| P4 | F1 表结构 | 方案 A：`ord_order` 新增 `proof_image_urls` JSON 列 | 1-3 张图片强 1:1 归属，JSON 列最简、一致、改动小；`proofImageCount` 保留冗余校验 |
| P5 | F1 typeHandler | MyBatis-Plus `JacksonTypeHandler` + `autoResultMap=true` | 项目已用 MyBatis-Plus，原生支持 List↔JSON，无需自写 |
| P6 | F2 实现 | 方案 A（后端 `listUserReviews` 双向查询） | 遵 spec 澄清 D5，单请求拿全，前端不分裂状态 |
| P7 | F4 实现 | 删除 `fetchBalance`，用 `currentUser.balance ?? 0` | 遵 spec 澄清 D6，后端无该接口，不补 |
| P8 | F6 实现 | 方案 A（`mapOrderRecord` 显式映射 anonymous） | 遵 spec 澄清 D7，前端单点修复 |
| P9 | F15 实现 | 调预置账号余额为 100.00（init_schema.sql + DemoDataInitializer） | 遵 spec 澄清 D9，与 README 一致；标注团队确认二选一 |
| P10 | F3 submitting 状态粒度 | 按 `submittingOrderId` 隔离 | 避免一个订单操作禁用整列按钮 |
| P11 | F14 并行失败处理 | `Promise.allSettled` 或逐个 try/catch 隔离 | 单个失败不拖垮全部；fetchProfile 失败短路 |
| P12 | F13 debounce 实现 | 300ms，不引入 lodash | 遵 spec 澄清 D8；用项目内工具或原生 setTimeout |
| P13 | F17 首页处理 | 删 HomeView 死代码，`/` redirect 到 `/demands` | 优先删死代码，路由守卫不变 |
| P14 | F18 日期工具 | 复用 `formatDateTime` | 已实现，输出中文格式，无需新建 |
| P15 | F25 提示组件 | `useAlert` | 与项目统一风格，替代原生 alert |
| P16 | 后端基线保护 | F1/F2/F15 改后重跑全量 `./mvnw test` | 确认状态机/锁/信用分/仲裁/通知未破坏 |
| P17 | 文档语言 | 中文 | 遵 AGENTS.md 语言要求，技术标识符保留英文 |

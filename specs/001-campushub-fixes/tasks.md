# 任务清单：CampusHub 审查问题修复（F1-F26）

**关联 Plan**: `specs/001-campushub-fixes/plan.md`
**关联 Spec**: `specs/001-campushub-fixes/spec.md`
**创建日期**: 2026-10-08
**状态**: Ready for Implementation
**总任务数**: 41

---

## 实现策略

- **三阶段分批**：阶段一（严重 F1-F4）→ 阶段二（中等 F5-F16，按模块聚子批次 2a-2f）→ 阶段三（轻微 F17-F26）
- **F1 最先做**：跨前后端，后端 schema/entity/dto 先行，前端依赖后端字段，单独验证后再进入 F2
- **阶段二同批次内任务标记 [P]**：逻辑独立可并行开发；同文件（如 `campusHub.ts`）改动建议在不同分支并行开发后顺序合并，避免冲突
- **业务代码位置**：所有改动落在 `backend/`、`frontend/`，不在 `specs/` 下生成业务代码
- **基线保护**：F1/F2/F15 后端改完后重跑 `./mvnw test` 全量回归，确认状态机/并发锁/信用分/仲裁/通知未破坏
- **MVP 范围**：阶段一（F1-F4）为 MVP，完成后跑一次完整 Playwright 主流程确认基线未破坏

---

## 阶段一：严重问题（F1-F4）

> 检查点：阶段一全部完成后，跑一次完整 Playwright 主流程（发布→接单→完成凭证→确认→评价），确认基线未破坏，再进入阶段二。

### F1 · 凭证图片上传 UI 与后端字段持久化（跨前后端，最先做）

后端链路有依赖顺序：schema → entity → domain → dto → service → view。entity/domain/dto 三者逻辑独立可并行开发，但应用顺序需按依赖。

- [X] T001 [F1] 后端：`ord_order` 表新增 `proof_image_urls` JSON 列
  - 文件：`backend/src/main/resources/init_schema.sql`
  - 依赖：无
  - 要点：在 `proof_image_count` 后新增 `proof_image_urls json DEFAULT NULL COMMENT '完成凭证图片URL列表(1-3)'`
  - 验收：H2 初始化建表成功，列存在；AC-1 后端字段持久化前提

- [X] T002 [F1] [P] 后端：`OrderEntity` 新增 `proofImageUrls` 字段 + 确认 `autoResultMap=true`
  - 文件：`backend/.../order/repository/entity/OrderEntity.java`
  - 依赖：T001
  - 要点：`@TableField(value="proof_image_urls", typeHandler=JacksonTypeHandler.class) private List<String> proofImageUrls;`；确认类上 `@TableName(value="ord_order", autoResultMap=true)` 已开启（typeHandler 仅在 autoResultMap=true 时对查询生效）
  - 验收：H2 查询能回填 `proofImageUrls`；AC-1

- [X] T003 [F1] [P] 后端：`Order` 领域对象新增 `proofImageUrls` 字段
  - 文件：`backend/.../order/domain/Order.java`
  - 依赖：T001
  - 要点：新增 `private List<String> proofImageUrls;` + getter/setter + 构造函数参数（加在 `proofImageCount` 之后，向后兼容）
  - 验收：领域对象与实体字段对齐；AC-1

- [X] T004 [F1] [P] 后端：`UpdateOrderStatusCommand` record 增参 `proofImageUrls`
  - 文件：`backend/.../order/dto/UpdateOrderStatusCommand.java`
  - 依赖：无
  - 要点：`public record UpdateOrderStatusCommand(String targetStatus, String note, Integer proofImageCount, List<String> proofImageUrls) {}`
  - 验收：DTO 接收凭证 URL 列表；AC-1

- [X] T005 [F1] 后端：`OrderApplicationServiceImpl` 校验与持久化 `proofImageUrls`
  - 文件：`backend/.../order/service/OrderApplicationServiceImpl.java`
  - 依赖：T002, T003, T004
  - 要点：`validateProviderProof` 校验 `proofImageUrls` 非空且 size ∈ [1,3]，与 `proofImageCount` 不一致时以 urls 为准并修正 count；完成流转处将 `proofImageUrls` 写入 `OrderEntity` 持久化；**不改变**双重确认完成、并发锁、状态机流转逻辑
  - 验收：0/1/3/4 张、urls 与 count 不一致场景单测通过；AC-1

- [X] T006 [F1] 后端：`OrderView` + 组装器映射 `proofImageUrls`
  - 文件：`backend/.../api/view/OrderView.java`、`backend/.../api/view/OrderViewAssembler`（或对应组装器）
  - 依赖：T003
  - 要点：`OrderView` record 在 `proofImageCount` 后新增 `List<String> proofImageUrls`；组装器从 `Order` 映射
  - 验收：`OrderView` 返回凭证 URL 列表；AC-1 发布者可见凭证

- [X] T007 [F1] 后端：单元测试 + H2 集成测试
  - 文件：`backend/.../order/service/OrderApplicationServiceImplTest.java`（或对应测试类）
  - 依赖：T005, T006
  - 要点：覆盖 `validateProviderProof`（0/1/3/4 张、urls 与 count 不一致）；完成流转写入 `proofImageUrls` 后查询回读；H2 集成测试覆盖 提交完成（带图片）→ 状态流转 → `OrderView` 返回 `proofImageUrls`
  - 验收：`./mvnw test` 相关用例全绿；AC-1

- [X] T008 [F1] [P] 前端：`campushub.ts` 类型新增 `proofImageUrls`
  - 文件：`frontend/src/types/campushub.ts`
  - 依赖：无
  - 要点：`Order` 类型新增 `proofImageUrls?: string[]`
  - 验收：类型检查通过；AC-1

- [X] T009 [F1] [P] 前端：`mapOrderRecord` 映射 `proof_image_urls` → `proofImageUrls`
  - 文件：`frontend/src/stores/campusHub.ts`（`mapOrderRecord` 约 192-239 行）
  - 依赖：T008
  - 要点：从后端响应映射 `proof_image_urls` 到 `proofImageUrls`
  - 验收：订单记录含凭证 URL；AC-1

- [X] T010 [F1] 前端：`completeOrder` 传 `proofImageUrls` 参数
  - 文件：`frontend/src/stores/campusHub.ts`（`completeOrder` 约 879-890 行）
  - 依赖：T009
  - 要点：去掉硬编码 `{targetStatus:'COMPLETED', proofImageCount:1}`，改为接收 `proofImageUrls: string[]` 参数并传入 `proofImageCount: proofImageUrls.length`
  - 验收：提交完成携带真实凭证 URL；AC-1

- [X] T011 [F1] [P] 前端：`OrderDetailView` 引入 `ImageUploader` 上传步骤
  - 文件：`frontend/src/views/OrderDetailView.vue`（231-248 行）
  - 依赖：T010
  - 要点：在"提交完成确认"动作前增加凭证上传步骤；校验必须 1-3 张，不足则 `useAlert` 提示且不发起请求；复用 `submitting` 模式禁用按钮
  - 验收：AC-1（接单方上传 1-3 张凭证 → 提交完成 → 流转至 COMPLETED）

- [X] T012 [F1] [P] 前端：`DemandDetailView` 引入 `ImageUploader` 上传步骤
  - 文件：`frontend/src/views/DemandDetailView.vue`（342-358 行）
  - 依赖：T010
  - 要点：同 T011，在需求详情页的提交完成动作前增加凭证上传步骤
  - 验收：AC-1

- [X] T013 [F1] 前端：发布者确认完成时渲染凭证图片列表
  - 文件：`frontend/src/views/OrderDetailView.vue`、`frontend/src/views/DemandDetailView.vue`
  - 依赖：T009, T011
  - 要点：发布者角色进入订单详情时渲染凭证图片列表（可点击放大/新窗查看）
  - 验收：AC-1（发布者进入订单详情可看到凭证图片）

### F2 · fetchUserReviews 返回双向评价（后端）

- [X] T014 [F2] 后端：`listUserReviews` 改为双向查询
  - 文件：`backend/.../review/service/ReviewApplicationServiceImpl.java`（`listUserReviews`）
  - 依赖：无（与 F1 后端无冲突，可并行）
  - 要点：查询条件由 `targetId = userId` 改为 `targetId = userId OR reviewerId = userId`，单次请求返回双向评价；前端 `reviewsGiven` 过滤逻辑（`reviewerId === currentUserId`）保持不变；`hasReviewedResponse` 判断同步生效（SELECT_MANY 组队评价场景）
  - 验收：AC-2（刷新后"我发出的评价"非空；SELECT_MANY 组队评价重复点击禁用）

- [X] T015 [F2] 后端：单元测试
  - 文件：`backend/.../review/service/ReviewApplicationServiceImplTest.java`
  - 依赖：T014
  - 要点：覆盖 仅收到的、仅发出的、双向都有、都没有 四种场景
  - 验收：`./mvnw test` 相关用例全绿；AC-2

### F4 · 移除 fetchBalance 404 调用（前端）

- [X] T016 [F4] [P] 前端：删除 `fetchBalance`，余额校验改用 `currentUser.balance`
  - 文件：`frontend/src/stores/campusHub.ts`（1262-1269 行）
  - 依赖：无
  - 要点：删除 `fetchBalance` 方法及其调用点；余额校验改用 `this.currentUser?.balance - frozenBalance`，`currentUser` 未加载时 balance 为 undefined 需 `?? 0` 兜底；不在后端补 `/users/me/balance` 接口
  - 验收：AC-4（发布带悬赏需求时网络面板无 `/user/balance` 404；余额计算基于 `currentUser.balance - frozenBalance`）

### F3 · 订单列表操作错误处理（前端）

- [X] T017 [F3] [P] 前端：`OrdersView` 补齐 try/catch + `submittingOrderId`
  - 文件：`frontend/src/views/OrdersView.vue`（186-197 行）
  - 依赖：无
  - 要点：参照 `OrderDetailView` 同名函数补齐 `try/catch`；成功调 `message`，失败调 `handleError` 统一翻译；增加 `submittingOrderId` 状态（按订单 ID 隔离，避免一个订单操作禁用全部按钮），按钮 `:disabled="submittingOrderId===row.id"` + "处理中"文案；操作完成后刷新列表（`refreshOrders`）
  - 验收：AC-3（操作中按钮 disabled + loading；成功有 message；失败有 error 且调用 `handleError` 翻译）

---

## 阶段二：中等问题（F5-F16）

> 按模块聚子批次以减少上下文切换。同批次内任务标记 [P] 可并行开发；同文件改动建议顺序合并。

### 批次 2a · 校园中心 Store 改造（`stores/campusHub.ts`）

> 均落在 `frontend/src/stores/campusHub.ts`，逻辑独立可并行开发，合并时注意顺序。

- [X] T018 [F14] [P] `hydrateAuthenticatedState` 并行化
  - 文件：`frontend/src/stores/campusHub.ts`（415-425 行）
  - 依赖：无
  - 要点：`fetchProfile` 完成（拿到 `currentUserId`）后，将 `fetchCurrentUserReviews`/`fetchDemands`/`fetchOrders`/`fetchNotifications` 改为 `Promise.all` 并行；单个失败用 `Promise.allSettled` 或逐个 try/catch 隔离；`fetchProfile` 失败时短路（无 currentUserId 则不发后续）
  - 验收：AC-14（登录后并行加载，网络面板并发请求；总耗时显著低于串行）

- [X] T019 [F8] [P] 401 调用 `logout` + `isLoggingOut` 防并发
  - 文件：`frontend/src/stores/campusHub.ts`（1153-1164 行）、`frontend/src/main.ts`
  - 依赖：无
  - 要点：401 处理处将 `currentProfile = null` 改为调用 `this.logout()`（清 token + localStorage + 用户态）；加 `isLoggingOut` 标志避免多个 401 并发触发多次 logout；`main.ts` 启动流程同步：token 存在但 `fetchProfile` 失败时走 logout
  - 验收：AC-8（token 过期彻底登出，后续请求不再带过期 token，无中间态）

- [X] T020 [F12] [P] 409 错误动态翻译
  - 文件：`frontend/src/stores/campusHub.ts`（768-788 行）
  - 依赖：无
  - 要点：不再统一返回"该需求已过期"，改为根据 `payload.message` 匹配映射（参考 `formatAcceptDisabledReason` 的 reasonMap 思路）：已被接单/已过期/状态冲突等；`payload.message` 缺失或未知时走通用翻译兜底
  - 验收：AC-12（接单 409 提示根据 `payload.message` 动态翻译，区分场景）

- [X] T021 [F21] [P] `updateProfile` 去冗余 GET
  - 文件：`frontend/src/stores/campusHub.ts`（547-565 行）
  - 依赖：无
  - 要点：PUT `/users/me` 响应已写入 `currentProfile`，删除紧随其后的冗余 GET `/users/me`；边界：PUT 响应体缺字段时降级为发一次 GET（用响应体字段缺失判断）
  - 验收：AC-21（更新资料后不再发冗余 GET，`currentProfile` 由 PUT 响应写入）

- [X] T022 [F19] [P] `submitReview` 去重
  - 文件：`frontend/src/stores/campusHub.ts`（959-977 行）
  - 依赖：无
  - 要点：参照 `submitReviewForResponse` 的去重判断（按 orderId + reviewerId 或唯一键），已存在则不 unshift；保证两函数行为一致
  - 验收：AC-19（重复触发 `submitReview` 不重复 unshift，行为与 `submitReviewForResponse` 一致）

### 批次 2b · 详情页交互（`DemandDetailView.vue` / `OrderDetailView.vue`）

- [X] T023 [F5] [P] AI 草稿回填 `startTime`
  - 文件：`frontend/src/views/DemandPublishView.vue`（AI 草稿回填逻辑）
  - 依赖：无
  - 要点：检查回填映射分支，`endTime` 已正确回填而 `startTime` 漏填，定位缺失的赋值语句补上；边界：`startTime` 缺失/格式异常时不报错，保持空值由用户手选；与 `endTime` 倒序时不强制纠正
  - 验收：AC-5（AI 草稿生成后开始/结束时间均回填）

- [X] T024 [F10] [P] SELECT_MANY 超员前置校验
  - 文件：`frontend/src/views/DemandDetailView.vue`（488-503 行）
  - 依赖：无
  - 要点：勾选报名时实时判断 `selectedCount > targetParticipantCount`，超员即 `useAlert` 提示并禁止勾选/提交；边界：`targetParticipantCount` 为 0/1 时按实际处理
  - 验收：AC-10（超员前置提示，禁止超额提交）

- [X] T025 [F11] [P] 关键操作 loading 与防重复点击
  - 文件：`frontend/src/views/DemandDetailView.vue`（287-308、716-724 行）、`frontend/src/views/OrderDetailView.vue`（227-229 行）
  - 依赖：无
  - 要点：复用 `submitting` 标志，按钮 `:disabled="submitting"` + "处理中"文案；`acceptCurrentDemand`/`startOrder`/`submitCompletion` 等 async 动作在 `try` 前置 `submitting=true`，`finally` 复位
  - 验收：AC-11（按钮 disabled + "处理中"，请求完成后恢复，防止重复点击）

- [X] T026 [F24] [P] 等待提示文案按角色修正
  - 文件：`frontend/src/views/DemandDetailView.vue`（742 行）
  - 依赖：无
  - 要点：`currentUserConfirmedCompletion || providerConfirmed` 时按角色（requester/provider）分支显示不同文案；处理边界：双方均未确认 / 仅一方确认 / 双方均确认
  - 验收：AC-24（requester 角色查看等待提示文案正确）

### 批次 2c · 字段映射与脱敏（`mapOrderRecord` / `mapDemandRecord`）

- [X] T027 [F6] [P] `mapOrderRecord` 映射 `anonymous` + `OrderDetailView` 使用
  - 文件：`frontend/src/stores/campusHub.ts`（`mapOrderRecord` 192-239 行）、`frontend/src/views/OrderDetailView.vue`（167-170 行）
  - 依赖：无
  - 要点：显式映射 `anonymous` 字段（从 `record.demand?.anonymous` 或扁平字段取值）到 `order.demand.anonymous`（或顶层 `order.anonymous`）；`OrderDetailView.vue` 改用映射后的 `order.anonymous`，移除 `requesterName.includes('匿名')` 兜底判断；不改后端
  - 验收：AC-6（匿名需求详情脱敏生效，不依赖兜底判断）

- [X] T028 [F23] [P] `mapDemandRecord` 映射 `acceptStatusHint`
  - 文件：`frontend/src/types/campushub.ts`（80 行已有类型）、`frontend/src/stores/campusHub.ts`（`mapDemandRecord` 121-161 行）
  - 依赖：无
  - 要点：`mapDemandRecord` 显式映射 `acceptStatusHint`（从后端响应字段取值）；`DemandDetailView` 中该字段不再恒为 null
  - 验收：AC-23（`acceptStatusHint` 非恒 null，UI 提示与实际接单状态一致）

### 批次 2d · 管理后台与登录

- [X] T029 [F7] [P] 封禁/解禁确认对话框
  - 文件：`frontend/src/views/AdminView.vue`（179-192 行 `toggleUserStatus`）
  - 依赖：无
  - 要点：调用 `useConfirm`，标题"确认封禁"/"确认解禁"，`danger: true`（封禁时）；确认返回 `true` 才执行 `banUser`/`unbanUser`；参照同文件需求拒绝弹窗写法保持风格一致
  - 验收：AC-7（封禁/解禁弹确认框，确认后才执行，取消不执行）

- [X] T030 [F16] [P] 登录 label 兼容邮箱
  - 文件：`frontend/src/views/AuthView.vue`
  - 依赖：无
  - 要点：label 与 placeholder 由"学号"改为"学号/邮箱"；后端 `AuthApplicationServiceImpl` loginId 已支持学号或邮箱，无需改动
  - 验收：AC-16（登录表单 label 显示"学号/邮箱"，placeholder 同步）

### 批次 2e · 列表与通知

- [X] T031 [F9] [P] 通知跳转分支补齐
  - 文件：`frontend/src/views/NotificationsView.vue`（44-104 行 `openNotification`）
  - 依赖：无
  - 要点：新增分支 `RESPONSE_REVIEW_RECEIVED` → 跳转关联 response/评价页；`PENDING_REVIEW` → 跳转待审核/待评价页；从 `payload` 取关联 ID（缺失时降级到 `/notifications`，不报错）
  - 验收：AC-9（点击两类通知跳转关联页面，不停留原页）

- [X] T032 [F13] [P] 搜索 debounce
  - 文件：`frontend/src/views/DemandListView.vue`（197-209 行 watch `filters.q`）
  - 依赖：无
  - 要点：加 300ms debounce（用项目内既有 debounce 工具或 `setTimeout` + 清理实现，不引入 lodash）；边界：粘贴长文本、清空、与筛选组合时仅触发一次请求
  - 验收：AC-13（快速输入不会每个 keystroke 触发请求，停止 300ms 后单次请求）

### 批次 2f · 后端数据一致（F15）

- [X] T033 [F15] 后端：`init_schema.sql` 预置账号设余额 100.00
  - 文件：`backend/src/main/resources/init_schema.sql`
  - 依赖：无
  - 要点：3 个 `sys_user` INSERT（admin/TEST001/TEST002）在列清单中显式增加 `balance` 并设值 100.00；使用 `ON DUPLICATE KEY UPDATE id=id` 保持幂等
  - 验收：AC-15（预置账号余额 100.00）

- [X] T034 [F15] 后端：`DemoDataInitializer` admin 余额改 100.00
  - 文件：`backend/.../api/DemoDataInitializer.java`（第 35 行）
  - 依赖：T033
  - 要点：admin 的 balance 由 `BigDecimal.ZERO` 改为 `new BigDecimal("100.00")`；**注意**：第 34 行的 `100` 是 creditScore（信用分），保持不动，不要误改
  - 验收：AC-15（演示 admin 余额 100.00，可发布带悬赏需求）

- [X] T035 [F15] 后端：单元测试
  - 文件：`backend/.../api/DemoDataInitializerTest.java`
  - 依赖：T034
  - 要点：断言 admin 余额为 100.00；TEST001/TEST002 同理
  - 验收：`./mvnw test` 相关用例全绿；AC-15

---

## 阶段三：轻微问题（F17-F26）

> 纯前端局部优化，可并行处理。F11 已在阶段二批次 2b 覆盖，此处不重复。

- [X] T036 [F17] [P] 删除 `HomeView` 死代码 + 路由 redirect
  - 文件：`frontend/src/views/HomeView.vue`、`frontend/src/router/index.ts`（5-8 行）、`frontend/src/App.vue`（导航高亮逻辑）
  - 依赖：无
  - 要点：推荐方案——删除空 `HomeView.vue`，路由 `/` 直接 `redirect: '/demands'`（或 component 复用 `DemandListView`）；保留路由守卫逻辑不变
  - 验收：AC-17（访问 `/` 导航"首页"正确高亮，无空 HomeView 死代码）

- [X] T037 [F18] [P] 仲裁订单 `createdAt` 格式化
  - 文件：`frontend/src/views/AdminView.vue`（416 行）
  - 依赖：无
  - 要点：`order.createdAt` 原始 ISO 字符串 → `formatDateTime(order.createdAt)`；复用 `frontend/src/utils/format.ts` 的 `formatDateTime`（已实现，输出 `MM-DD HH:mm` 中文格式）；边界：`createdAt` 为 null 时不报错
  - 验收：AC-18（`createdAt` 显示为可读格式，非原始 ISO）

- [X] T038 [F20] [P] 全部标记已读 loading + await
  - 文件：`frontend/src/views/NotificationsView.vue`（122 行）
  - 依赖：无
  - 要点：增加 `markingAllRead` 状态，按钮 `:disabled="markingAllRead"` + loading 文案；`await markAllAsRead()` 完成后再恢复；边界：无未读通知、全部已读期间新通知到达
  - 验收：AC-20（按钮 disabled + loading，await 完成后恢复）

- [X] T039 [F22] [P] `ProfileEditView` 表单响应式同步
  - 文件：`frontend/src/views/ProfileEditView.vue`（19-22 行）
  - 依赖：无
  - 要点：`profileForm` 不再初始化时快照，改为 `watch(() => store.currentUser, ...)` 响应式回填，或 `onMounted` 的 `fetchProfile` 完成后显式回填；边界：`fetchProfile` 失败时表单保持空/上次值，不阻塞
  - 验收：AC-22（`fetchProfile` 更新 `store.currentUser` 后表单响应式回填）

- [X] T040 [F25] [P] 头像更新失败提示统一
  - 文件：`frontend/src/views/ProfileView.vue`（29 行）
  - 依赖：无
  - 要点：原生 `alert` → `useAlert('头像更新失败', err.message)`（统一风格）；参照项目其他失败提示写法
  - 验收：AC-25（头像更新失败用 `useAlert`，非原生 alert）

- [X] T041 [F26] [P] 对话框错误就近显示
  - 文件：`frontend/src/views/AdminView.vue`（447-448、457-481 行 拒绝/仲裁/删除对话框）
  - 依赖：无
  - 要点：对话框内增加错误显示区域（局部 `dialogError` 状态），提交失败时就近渲染；对话框关闭时清空 `dialogError`，避免残留；处理多个对话框同时打开场景（按对话框 ID/key 隔离错误状态）
  - 验收：AC-26（对话框提交失败错误就近显示在对话框内，无需滚到页面底部）

---

## 依赖关系与执行顺序

### 用户故事完成顺序

```
阶段一（MVP，严重）:
  F1 (T001-T013) → F2 (T014-T015) → F4 (T016) → F3 (T017)
  ├─ F1 后端链路: T001 → {T002,T003,T004 并行} → T005 → T006 → T007
  ├─ F1 前端链路: {T008,T011,T012 并行} → T009 → T010 → T013
  ├─ F2 后端: T014 → T015（与 F1 并行）
  ├─ F4 前端: T016（与 F1/F2 并行）
  └─ F3 前端: T017（与 F1/F2/F4 并行）

阶段二（中等，按子批次）:
  2a (T018-T022) | 2b (T023-T026) | 2c (T027-T028) | 2d (T029-T030) | 2e (T031-T032) | 2f (T033-T035)
  └─ 各子批次内部任务可并行 [P]；子批次之间无强依赖，可整体并行推进

阶段三（轻微）:
  T036-T041 全部可并行 [P]
```

### 关键依赖链

- **F1 后端**：T001（schema）→ T002/T003/T004（entity/domain/dto，可并行）→ T005（service）→ T006（view）→ T007（测试）
- **F1 前端**：T008（类型）→ T009（mapOrderRecord）→ T010（completeOrder）→ T011/T012（UI）→ T013（渲染凭证）
- **F15 后端**：T033（schema）→ T034（DemoDataInitializer）→ T035（测试）
- **跨阶段**：阶段一完成后跑完整 Playwright 主流程确认基线未破坏，再进入阶段二

### 并行机会汇总

| 批次 | 可并行任务 | 说明 |
|------|-----------|------|
| F1 后端 | T002, T003, T004 | entity/domain/dto 逻辑独立 |
| F1 前端 | T008, T011, T012 | 类型/UI 改动独立（T011/T012 依赖 T010，但可与 T008 并行） |
| 阶段一整体 | F1 / F2 / F3 / F4 | 跨 F 项可并行（不同文件） |
| 批次 2a | T018-T022 | 同文件逻辑独立，建议分支并行后顺序合并 |
| 批次 2b | T023-T026 | 不同函数/文件，可并行 |
| 批次 2c | T027, T028 | 不同映射函数，可并行 |
| 批次 2d | T029, T030 | 不同文件，可并行 |
| 批次 2e | T031, T032 | 不同文件，可并行 |
| 阶段三 | T036-T041 | 全部不同文件，可并行 |

---

## 独立测试标准（按用户故事）

| 用户故事 | F 编号 | 独立测试标准 | 验收场景 |
|---------|--------|-------------|---------|
| US-1 | F1 | 接单方上传 1-3 张凭证 → 提交完成 → 发布者详情可见凭证 | AC-1 |
| US-2 | F2 | 用户 A 评价 B 后刷新主页 → "我发出的评价"非空；SELECT_MANY 组队评价重复点击禁用 | AC-2 |
| US-3 | F3 | 订单列表操作 → 按钮 disabled + loading + 成功/失败反馈 | AC-3 |
| US-4 | F4 | 发布带悬赏需求 → 网络面板无 `/user/balance` 404 | AC-4 |
| US-5 | F5 | AI 草稿生成 → 开始/结束时间均回填 | AC-5 |
| US-6 | F6 | 匿名需求详情脱敏生效 | AC-6 |
| US-7 | F7 | 封禁/解禁弹确认框 | AC-7 |
| US-8 | F8 | token 过期 → 彻底登出，无中间态 | AC-8 |
| US-9 | F9 | `RESPONSE_REVIEW_RECEIVED`/`PENDING_REVIEW` 通知跳转正确 | AC-9 |
| US-10 | F10 | SELECT_MANY 超员前置提示 | AC-10 |
| US-11 | F11 | 接单/开始执行/提交完成按钮 loading + 禁用 | AC-11 |
| US-12 | F12 | 接单 409 提示动态翻译 | AC-12 |
| US-13 | F13 | 搜索快速输入 → 300ms 后单次请求 | AC-13 |
| US-14 | F14 | 登录后并行加载（网络面板并发请求） | AC-14 |
| US-15 | F15 | 预置账号余额 100.00，可发布带悬赏需求 | AC-15 |
| US-16 | F16 | 登录表单 label "学号/邮箱" | AC-16 |
| US-17 | F17 | 首页高亮正确，无死代码 | AC-17 |
| US-18 | F18 | 仲裁日期格式可读 | AC-18 |
| US-19 | F19 | `submitReview` 去重，行为一致 | AC-19 |
| US-20 | F20 | 全部已读 loading + await | AC-20 |
| US-21 | F21 | 更新资料不重复 GET | AC-21 |
| US-22 | F22 | 资料表单响应式回填 | AC-22 |
| US-23 | F23 | `acceptStatusHint` 映射正确 | AC-23 |
| US-24 | F24 | 等待提示文案按角色正确 | AC-24 |
| US-25 | F25 | 头像失败提示统一 `useAlert` | AC-25 |
| US-26 | F26 | 对话框错误就近显示 | AC-26 |

---

## 任务统计

| 阶段 | 批次 | 任务范围 | 任务数 | 可并行任务数 |
|------|------|---------|--------|------------|
| 阶段一 | F1 后端 | T001-T007 | 7 | 3（T002/T003/T004） |
| 阶段一 | F1 前端 | T008-T013 | 6 | 3（T008/T011/T012） |
| 阶段一 | F2 | T014-T015 | 2 | 1（T014 与 F1 并行） |
| 阶段一 | F4 | T016 | 1 | 1 |
| 阶段一 | F3 | T017 | 1 | 1 |
| 阶段二 | 2a | T018-T022 | 5 | 5 |
| 阶段二 | 2b | T023-T026 | 4 | 4 |
| 阶段二 | 2c | T027-T028 | 2 | 2 |
| 阶段二 | 2d | T029-T030 | 2 | 2 |
| 阶段二 | 2e | T031-T032 | 2 | 2 |
| 阶段二 | 2f | T033-T035 | 3 | 2（T033/T034 串行） |
| 阶段三 | — | T036-T041 | 6 | 6 |
| **合计** | | | **41** | **32** |

---

## 验证方式

### 后端验证（F1/F2/F15）

- 单元测试：`cd backend && ./mvnw test`（F1/F2/F15 相关用例全绿）
- 基线回归：`./mvnw clean package`（全量测试，确认状态机/并发锁/信用分/仲裁/通知未破坏）
- H2 集成测试：F1 完整链路（提交完成带图片 → 状态流转 → `OrderView` 返回 `proofImageUrls`）；F2 评价查询端到端

### 前端验证（全量 Playwright 实跑）

- 类型检查 + 构建：`cd frontend && npm run build`（vue-tsc 类型检查 + vite 构建通过，因 F1/F6/F23 类型定义有改动）
- Playwright 主流程：发布 → 接单 → 完成凭证 → 确认 → 评价（阶段一完成后跑一次确认基线未破坏）
- 逐项按 AC 验收场景（AC-1 ~ AC-26）实跑验证

---

## 自主决策记录

| # | 决策项 | 选择 | 理由 |
|---|--------|------|------|
| T-D1 | tasks 文件位置 | `specs/001-campushub-fixes/tasks.md` | 与 spec/plan 同目录，便于追溯；业务代码不落 specs/ |
| T-D2 | spec 脚本/模板加载 | 跳过 | `.zhanlu/spec/` 下 extensions.yml、tasks-template.md、check-prerequisites 脚本均不存在，按轻量化模式跳过 |
| T-D3 | 任务编号策略 | T001-T041 顺序编号 | 跨阶段全局唯一，便于追踪 |
| T-D4 | F1 任务拆分粒度 | 后端 7 + 前端 6 = 13 任务 | 跨前后端链路长，按 schema/entity/domain/dto/service/view/test + 前端类型/映射/store/UI/渲染 拆分，确保每步可独立验证 |
| T-D5 | [P] 并行标记原则 | 同批次内不同文件/不同函数标 [P]；同文件改动注明"建议顺序合并" | 尊重用户要求"同批次内任务可并行标记[P]"，同时提示合并风险 |
| T-D6 | F1 后端依赖排序 | schema → {entity/domain/dto 并行} → service → view → test | typeHandler 需 autoResultMap，entity 依赖 schema；service 依赖 entity/domain/dto |
| T-D7 | 阶段一跨 F 项并行 | F1/F2/F3/F4 标 [P]（不同文件） | 严重问题之间无文件冲突，可并行推进，缩短 MVP 交付 |
| T-D8 | F11 归属 | 阶段二批次 2b（不在阶段三重复） | plan.md 明确 F11 在详情页批次已覆盖 |
| T-D9 | 验收点标注 | 每任务注明对应 AC 编号 | 便于实跑验证时逐项核对 |
| T-D10 | 文档语言 | 中文 | 遵 AGENTS.md 语言要求，技术标识符保留英文 |

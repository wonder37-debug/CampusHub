# Specification: CampusHub 审查问题修复（F1-F26）

**Spec ID**: `001-campushub-fixes`
**创建日期**: 2026-10-08
**作者**: 代码审查与实跑验证（后端 API + 前端 Playwright + AI 功能实调）
**状态**: Ready for Planning

---

## 1. 项目背景

CampusHub 是南京大学校园互助平台（Monorepo）：
- **后端**：Spring Boot 3.5 + Java 21 + MyBatis-Plus + MySQL，位于 `backend/`
- **前端**：Vue 3.5 + TypeScript 6 + Vite 8 + Pinia 3，位于 `frontend/`
- **核心业务**：需求发布 → 审核 → 接单 → 执行 → 确认完成 → 评价；四种互动模式（`DIRECT_ACCEPT` / `SELECT_ONE` / `SELECT_MANY` / `HELP`）；AI 草稿生成；通知系统；管理后台

本次审查已通过实跑验证确认后端业务流转、状态机、并发锁、信用分计算、仲裁流程、通知推送、管理后台逻辑、AI 草稿生成均正确，**本次范围聚焦审查中发现的前端可用性、功能完整性、人机交互友好性问题及少量数据/配置一致性问题，共 26 项（F1-F26）**。

---

## 2. 需求范围

### 2.1 In Scope（在范围内）

修复审查发现的 26 项功能性问题，按严重程度分三档：

| 等级 | 编号 | 数量 | 主题 |
|------|------|------|------|
| 严重 | F1, F2, F3, F4 | 4 | 凭证图片缺失、评价丢失、列表无错误处理、余额接口 404 |
| 中等 | F5–F16 | 12 | AI 回填、匿名脱敏、确认对话框、token 清理、通知跳转、防重复点击、debounce、并行化等 |
| 轻微 | F17–F26 | 10 | 死代码、日期格式化、去重、loading、重复请求、响应式同步、字段映射、文案、风格统一 |

### 2.2 Out of Scope（不在范围内）

- **非功能性需求**：性能基准压测、安全渗透、容量规划、可观测性埋点
- **后端业务逻辑重构**：状态机、并发锁、仲裁、信用分计算已验证正确，不修改
- **新增业务功能**：不引入需求发布/接单/评价流程之外的全新能力
- **数据迁移**：除 F15 预置账号余额与 init 脚本调整外，不做线上数据迁移

### 2.3 严重程度判定原则

- **严重**：阻断核心业务流转或导致功能完整性缺失（用户无法完成关键动作）
- **中等**：影响体验、边界处理、错误反馈，但不阻断主流程
- **轻微**：优化项、风格一致性、死代码、显示瑕疵

---

## 3. 用户故事

### 3.1 严重问题用户故事

#### US-1（对应 F1）—— 接单方提交完成凭证
> 作为接单方，我希望在"提交完成确认"前能上传 1-3 张凭证图片，以便发布者能看到我完成的工作证据并确认完成，与 HelpCenter 文档承诺一致。

#### US-2（对应 F2）—— 查看"我发出的评价"
> 作为用户，我希望在个人主页刷新后仍能看到"我对别人的评价"列表，并在 SELECT_MANY 组队评价中正确判断是否已评价，避免重复评价被后端拒绝。

#### US-3（对应 F3）—— 订单列表操作反馈
> 作为用户，我希望在订单列表页点击"开始执行"/"提交完成确认"/"取消订单"时，成功/失败都有明确反馈，操作中按钮禁用，避免误以为操作成功。

#### US-4（对应 F4）—— 余额校验稳定
> 作为用户，我希望发布带悬赏需求时余额校验稳定可靠，不因调用不存在的 `/user/balance` 接口而出现额外失败请求或校验不稳定。

### 3.2 中等问题用户故事

#### US-5（对应 F5）—— AI 草稿完整回填
> 作为发布者，我希望用 AI 生成草稿后开始时间和结束时间都能正确回填到时间选择器，无需手动补选开始时间。

#### US-6（对应 F6）—— 匿名身份保护
> 作为匿名发布需求的用户，我希望订单详情页对我的学号和评价名称正确脱敏，不因字段映射缺失而泄漏身份。

#### US-7（对应 F7）—— 封禁/解禁确认
> 作为管理员，我希望封禁/解禁用户前有确认对话框，避免误点导致用户被误封。

#### US-8（对应 F8）—— 401 彻底登出
> 作为用户，我希望 token 过期时彻底清除登录态，不出现"看似登录态丢失但 token 仍带过期值"的中间态。

#### US-9（对应 F9）—— 通知精准跳转
> 作为用户，我希望点击 `RESPONSE_REVIEW_RECEIVED` 与 `PENDING_REVIEW` 通知能跳转到关联页面，而非停留在原页。

#### US-10（对应 F10）—— SELECT_MANY 超员前置提示
> 作为发布者，我希望在 SELECT_MANY 多选报名时，超过目标人数会被前置提示，而非提交后才由后端报错。

#### US-11（对应 F11）—— 关键操作防重复点击
> 作为用户，我希望接单/开始执行/提交完成按钮在请求中有 loading 与禁用，避免网络慢时重复点击触发多次请求。

#### US-12（对应 F12）—— 409 错误精准翻译
> 作为接单方，我希望接单失败时看到与实际原因（已被接单/已过期/状态冲突）匹配的提示，而非统一显示"该需求已过期"。

#### US-13（对应 F13）—— 搜索 debounce
> 作为用户，我希望在需求列表搜索时输入流畅，不因每个按键触发请求导致卡顿。

#### US-14（对应 F14）—— 登录后并行加载
> 作为用户，我希望登录后页面加载更快，不因 5 个请求串行而长时间等待。

#### US-15（对应 F15）—— 预置账号可用
> 作为测试/演示用户，我希望使用预置账号（TEST001/TEST002/admin）能正常发布带悬赏的需求，余额与 README 声明一致。

#### US-16（对应 F16）—— 登录 label 兼容邮箱
> 作为使用邮箱登录的用户，我希望登录表单 label 与 placeholder 体现"学号/邮箱"均可，避免困惑。

### 3.3 轻微问题用户故事

#### US-17（对应 F17）—— 首页导航高亮
> 作为用户，我希望访问 `/` 时"首页"导航正确高亮，且无空组件死代码。

#### US-18（对应 F18）—— 仲裁日期格式化
> 作为管理员，我希望仲裁订单卡片的创建时间显示为可读格式，而非原始 ISO 字符串。

#### US-19（对应 F19）—— 评价去重一致
> 作为用户，我希望 `submitReview` 与 `submitReviewForResponse` 行为一致，均对已存在评价去重。

#### US-20（对应 F20）—— 全部已读 loading
> 作为用户，我希望点击"全部标记已读"时有 loading 并 await 完成，避免连点。

#### US-21（对应 F21）—— 更新资料不重复请求
> 作为用户，我希望更新个人资料后不重复 GET 一次 profile，减少不必要的请求。

#### US-22（对应 F22）—— 资料编辑表单响应式
> 作为用户，我希望进入资料编辑页后，若 `currentProfile` 异步刷新，表单能响应式回填最新值。

#### US-23（对应 F23）—— acceptStatusHint 字段映射
> 作为用户，我希望 `acceptStatusHint` 字段被正确映射，UI 提示与实际接单状态一致。

#### US-24（对应 F24）—— 等待提示文案正确
> 作为发布者，我希望等待提示文案在 `currentUserConfirmedCompletion || providerConfirmed` 时对我的角色正确显示。

#### US-25（对应 F25）—— 头像更新失败提示统一
> 作为用户，我希望头像更新失败提示与项目统一风格（useConfirm/useAlert）一致，而非原生 alert。

#### US-26（对应 F26）—— 对话框错误就近显示
> 作为管理员，我希望拒绝/仲裁/删除对话框提交失败的错误就近显示在对话框内，而非页面底部。

---

## 4. 功能需求

### 4.1 严重问题

#### FR-1（F1）凭证图片上传 UI 与后端字段
- **位置**：`frontend/src/stores/campusHub.ts:879-890`、`views/OrderDetailView.vue:231-248`、`views/DemandDetailView.vue:342-358`；后端 `OrderApplicationServiceImpl.validateProviderProof`、`UpdateOrderStatusCommand`
- **现状**：`completeOrder` 直接传 `{targetStatus:'COMPLETED', proofImageCount:1}`，无图片上传 UI；后端要求 `proofImageCount` 在 1-3 之间，HelpCenter 承诺"提交凭证图片，由发布者确认"
- **需求**：
  1. 接单方"提交完成确认"前必须可上传 1-3 张凭证图片（复用 `ImageUploader`）
  2. `completeOrder` 传入真实凭证图片 URL 列表与数量
  3. 后端 `UpdateOrderStatusCommand` 增加 `proofImageUrls` 字段保存凭证 URL（当前仅 count）
  4. 发布者确认完成时可在订单详情看到凭证图片
- **验收**：见 AC-1

#### FR-2（F2）fetchUserReviews 返回双向评价
- **位置**：`frontend/src/stores/campusHub.ts:979-1004`、`views/ProfileView.vue:50-52`；后端 `ReviewApplicationServiceImpl.listUserReviews`
- **现状**：后端只查 `targetId=userId`；前端 `reviewsGiven` 过滤 `reviewerId===currentUserId`，刷新后空
- **需求**：后端 `listUserReviews` 改为返回 `targetId=userId OR reviewerId=userId`（推荐方案 A），或前端新增 `fetchGivenReviews` 接口分别存储（方案 B）
- **验收**：见 AC-2

#### FR-3（F3）订单列表操作错误处理
- **位置**：`frontend/src/views/OrdersView.vue:186-197`
- **现状**：`startOrder/completeOrder/cancelOrder` 无 try-catch、无 message/error、无 loading，对比 `OrderDetailView` 同名函数齐全
- **需求**：
  1. 补齐 try-catch 与页面级 error 提示
  2. 操作期间按钮 disabled + loading 文案
  3. 失败时调用 `handleError` 统一翻译
- **验收**：见 AC-3

#### FR-4（F4）移除 fetchBalance 404 调用
- **位置**：`frontend/src/stores/campusHub.ts:1262-1269`；后端 `UserController`（路径 `/api/v1/users`，无 balance GetMapping）
- **现状**：`fetchBalance` 调 `/user/balance` 永远 404，被 catch 吞掉恒返回 0
- **需求**：删除 `fetchBalance`，余额校验直接使用 `store.currentUser.balance - frozenBalance`；或后端补 `/users/me/balance` 接口
- **验收**：见 AC-4

### 4.2 中等问题

#### FR-5（F5）AI 草稿回填 startTime
- **位置**：`frontend/src/views/DemandPublishView.vue`（AI 草稿回填逻辑）
- **现状**：AI 返回草稿含 `startTime`，结束时间正常回填（`2026-10-08T17:00`），开始时间未填充显示"请选择开始时间"
- **需求**：检查回填映射，确保 `startTime` 正确映射到时间选择器
- **验收**：见 AC-5

#### FR-6（F6）isDemandAnonymous 字段映射
- **位置**：`frontend/src/views/OrderDetailView.vue:167-170`、`stores/campusHub.ts:192-239`（mapOrderRecord）
- **现状**：`(order.value as any).demand` 恒为 null（mapOrderRecord 只提取扁平字段未保留 demand 对象），`demand?.anonymous` 恒 undefined，只能靠 `requesterName.includes('匿名')` 兜底
- **需求**：`mapOrderRecord` 显式映射 `anonymous` 字段（推荐方案 A），或后端 `OrderView` 返回 `demandAnonymous`（方案 B）
- **验收**：见 AC-6

#### FR-7（F7）封禁/解禁确认对话框
- **位置**：`frontend/src/views/AdminView.vue:179-192`
- **现状**：`toggleUserStatus` 直接调 `banUser/unbanUser`，无 `useConfirm`，对比同文件需求拒绝有完整弹窗
- **需求**：封禁/解禁前加 `useConfirm` 确认
- **验收**：见 AC-7

#### FR-8（F8）401 彻底登出
- **位置**：`frontend/src/stores/campusHub.ts:1153-1164`
- **现状**：401 时 `currentProfile=null` 但 token 保留过期值，后续请求持续 401；`main.ts` 启动用旧 token 触发 `fetchProfile` 再 401
- **需求**：401 时调用 `this.logout()` 清掉 token 与 localStorage
- **验收**：见 AC-8

#### FR-9（F9）通知跳转分支补齐
- **位置**：`frontend/src/views/NotificationsView.vue:44-104`
- **现状**：`openNotification` 分支链无 `RESPONSE_REVIEW_RECEIVED`（SELECT_MANY 组队评价）与 `PENDING_REVIEW`，落到 fallback `router.push('/notifications')`
- **需求**：显式增加这两类通知的跳转分支，跳转到关联页面
- **验收**：见 AC-9

#### FR-10（F10）SELECT_MANY 超员前置校验
- **位置**：`frontend/src/views/DemandDetailView.vue:488-503`
- **现状**：用户可勾选超过 `targetParticipantCount` 的报名数，提交后才由后端报错
- **需求**：确认选择前先判断超员并提示，禁止超额提交
- **验收**：见 AC-10

#### FR-11（F11）关键操作 loading 与防重复点击
- **位置**：`frontend/src/views/DemandDetailView.vue:287-308&716-724`、`OrderDetailView.vue:227-229`
- **现状**：`acceptCurrentDemand/startOrder` 是 async，按钮无 `:disabled` 无"处理中"文案
- **需求**：复用 `submitting` 模式，按钮 `:disabled="submitting"`，请求中显示"处理中"
- **验收**：见 AC-11

#### FR-12（F12）409 错误动态翻译
- **位置**：`frontend/src/stores/campusHub.ts:768-788`
- **现状**：409 统一翻译为"该需求已过期，无法接单"
- **需求**：根据 `payload.message` 动态翻译，或直接走通用翻译，区分"已被接单/已过期/状态冲突"
- **验收**：见 AC-12

#### FR-13（F13）搜索 debounce
- **位置**：`frontend/src/views/DemandListView.vue:197-209`
- **现状**：watch 对 `filters.q` 每个 keystroke 触发 `refreshList`
- **需求**：对 `filters.q` 加 300ms debounce
- **验收**：见 AC-13

#### FR-14（F14）hydrateAuthenticatedState 并行化
- **位置**：`frontend/src/stores/campusHub.ts:415-425`
- **现状**：`fetchProfile→fetchCurrentUserReviews→fetchDemands→fetchOrders→fetchNotifications` 全部 await 串行
- **需求**：`fetchProfile` 后用 `Promise.all` 并行其余（`fetchCurrentUserReviews` 依赖 `currentUserId`，可在 `fetchProfile` 拿到后并入并行组）
- **验收**：见 AC-14

#### FR-15（F15）预置账号余额一致
- **位置**：后端 `init_schema.sql`（TEST001/TEST002）、`DemoDataInitializer.java`（admin）
- **现状**：README 声明"新用户注册后初始余额为 100.00"，但预置账号余额为 0（实跑 `balance=0.0`），`DemoDataInitializer` 写死 `BigDecimal.ZERO`
- **需求**：建表脚本给 TEST001/TEST002 设初始余额 100.00；`DemoDataInitializer` 演示账号也设 100；或确认设计意图后在 README 更正说明
- **验收**：见 AC-15

#### FR-16（F16）登录 label 兼容邮箱
- **位置**：`frontend/src/views/AuthView.vue`；后端 `AuthApplicationServiceImpl`（loginId 支持学号或邮箱）
- **现状**：前端 label 与 placeholder 写"学号"
- **需求**：label 改为"学号/邮箱"，placeholder 同步调整
- **验收**：见 AC-16

### 4.3 轻微问题

#### FR-17（F17）移除 HomeView 死代码并修复首页高亮
- **位置**：`frontend/src/views/HomeView.vue:1-7`、`router/index.ts:5-8`
- **现状**：HomeView 空组件，路由 `/` 实际指向 `DemandListView`；访问 `/` 时 App.vue 导航高亮判断 `route.path==='/demands'` 为 false
- **需求**：删除 HomeView 死代码，或在 App.vue 导航高亮逻辑中兼容 `/`
- **验收**：见 AC-17

#### FR-18（F18）仲裁订单 createdAt 格式化
- **位置**：`frontend/src/views/AdminView.vue:416`
- **现状**：直接显示 `order.createdAt` 原始 ISO 字符串
- **需求**：复用项目日期格式化工具显示可读格式
- **验收**：见 AC-18

#### FR-19（F19）submitReview 去重
- **位置**：`frontend/src/stores/campusHub.ts:959-977`
- **现状**：对比 `submitReviewForResponse` 有去重判断，`submitReview` 直接 unshift
- **需求**：补齐去重判断，行为与 `submitReviewForResponse` 一致
- **验收**：见 AC-19

#### FR-20（F20）全部标记已读 loading + await
- **位置**：`frontend/src/views/NotificationsView.vue:122`
- **现状**：无 loading 且未 await
- **需求**：补 loading 禁用按钮，await 完成
- **验收**：见 AC-20

#### FR-21（F21）updateProfile 去重复请求
- **位置**：`frontend/src/stores/campusHub.ts:547-565`
- **现状**：PUT `/users/me` 已返回更新后数据并写入 `currentProfile`，紧接着又 GET 一次
- **需求**：删除冗余 GET
- **验收**：见 AC-21

#### FR-22（F22）ProfileEditView 表单响应式同步
- **位置**：`frontend/src/views/ProfileEditView.vue:19-22`
- **现状**：`profileForm` 初始化时快照 `currentUser`，`onMounted` 的 `fetchProfile` 更新 `store.currentUser` 后表单不回填
- **需求**：表单响应式监听 `currentUser` 变化或 `fetchProfile` 完成后回填
- **验收**：见 AC-22

#### FR-23（F23）acceptStatusHint 字段映射
- **位置**：`frontend/src/types/campushub.ts:80`、`stores/campusHub.ts:121-161`
- **现状**：类型定义有 `acceptStatusHint`，`mapDemandRecord` 未映射，`DemandDetailView` 恒为 null
- **需求**：`mapDemandRecord` 显式映射 `acceptStatusHint`
- **验收**：见 AC-23

#### FR-24（F24）等待提示文案修正
- **位置**：`frontend/src/views/DemandDetailView.vue:742`
- **现状**：`currentUserConfirmedCompletion || providerConfirmed` 时文案对 requester 角色不正确
- **需求**：按角色（requester/provider）正确显示文案
- **验收**：见 AC-24

#### FR-25（F25）头像更新失败提示统一
- **位置**：`frontend/src/views/ProfileView.vue:29`
- **现状**：用原生 `alert`
- **需求**：改用项目统一的 `useConfirm`/`useAlert`
- **验收**：见 AC-25

#### FR-26（F26）对话框错误就近显示
- **位置**：`frontend/src/views/AdminView.vue:447-448&457-481`
- **现状**：对话框提交失败后错误显示在页面底部，需滚到底才能看到
- **需求**：错误就近显示在对话框内
- **验收**：见 AC-26

---

## 5. 验收场景

### AC-1（FR-1 / F1）
**Given** 接单方进入订单详情，订单处于 `IN_PROGRESS`
**When** 点击"提交完成确认"
**Then** 弹出凭证图片上传步骤，可上传 1-3 张图片
**And** 提交后后端 `UpdateOrderStatusCommand` 含 `proofImageUrls` 字段
**And** 订单流转至 `COMPLETED`（待发布者确认）
**And** 发布者进入订单详情可看到凭证图片

### AC-2（FR-2 / F2）
**Given** 用户 A 已对用户 B 提交过评价
**When** 用户 A 刷新个人主页
**Then** "我对别人的评价"列表显示该评价（非空）
**And** SELECT_MANY 组队评价中 `hasReviewedResponse` 正确为 true，重复点击"评价"按钮前端禁用或后端不报错

### AC-3（FR-3 / F3）
**Given** 用户在订单列表页
**When** 点击"开始执行"/"提交完成确认"/"取消订单"
**Then** 操作中按钮 disabled + loading 文案
**And** 成功有 message 提示
**And** 失败有 error 提示且调用 `handleError` 翻译

### AC-4（FR-4 / F4）
**Given** 用户发布带悬赏需求触发余额校验
**When** 校验执行
**Then** 不再调用 `/user/balance`（无 404 请求）
**And** 余额计算基于 `currentUser.balance - frozenBalance`
**And** 网络面板无 404 失败请求

### AC-5（FR-5 / F5）
**Given** 用户在发布页点击"AI 生成草稿"
**When** AI 返回含 `startTime` 与 `endTime` 的草稿
**Then** 开始时间选择器回填 `startTime`（如 `2026-10-08T17:00` 对应值）
**And** 结束时间选择器回填 `endTime`

### AC-6（FR-6 / F6）
**Given** 匿名发布的需求已生成订单
**When** 进入订单详情页
**Then** 发布者学号正确脱敏
**And** 评价名称匿名化生效
**And** 不依赖 `requesterName.includes('匿名')` 兜底判断

### AC-7（FR-7 / F7）
**Given** 管理员在用户管理列表
**When** 点击"封禁"/"解禁"
**Then** 弹出 `useConfirm` 确认对话框
**And** 确认后才执行封禁/解禁
**And** 取消则不执行

### AC-8（FR-8 / F8）
**Given** 用户 token 已过期
**When** 任意请求返回 401
**Then** 调用 `this.logout()` 清掉 token 与 localStorage
**And** 后续请求不再带过期 token
**And** 不出现"看似登录态丢失"中间态

### AC-9（FR-9 / F9）
**Given** 用户收到 `RESPONSE_REVIEW_RECEIVED` 或 `PENDING_REVIEW` 通知
**When** 点击通知
**Then** 跳转到关联页面（评价/待审核），而非停留原页或跳 `/notifications`

### AC-10（FR-10 / F10）
**Given** SELECT_MANY 需求，`targetParticipantCount=2`，已有 1 人报名
**When** 发布者勾选 2 人后再勾选第 3 人
**Then** 前置提示"超过目标人数"
**And** 禁止超额提交

### AC-11（FR-11 / F11）
**Given** 用户在需求/订单详情页
**When** 点击"接单"/"开始执行"/"提交完成"
**Then** 按钮 `:disabled="submitting"`，显示"处理中"
**And** 请求完成后恢复
**And** 防止重复点击触发多次请求

### AC-12（FR-12 / F12）
**Given** 接单方点击"接单"
**When** 后端返回 409
**Then** 提示根据 `payload.message` 动态翻译
**And** 区分"已被接单/已过期/状态冲突"等场景

### AC-13（FR-13 / F13）
**Given** 用户在需求列表搜索框快速输入"校园"
**When** 连续按键
**Then** 不会每个 keystroke 触发请求
**And** 停止输入 300ms 后触发一次请求

### AC-14（FR-14 / F14）
**Given** 用户登录成功
**When** `hydrateAuthenticatedState` 执行
**Then** `fetchProfile` 完成后其余请求（`fetchCurrentUserReviews`/`fetchDemands`/`fetchOrders`/`fetchNotifications`）并行执行
**And** 登录后总加载耗时显著低于串行

### AC-15（FR-15 / F15）
**Given** 使用预置账号 TEST001/TEST002 或演示 admin 登录
**When** 查看余额
**Then** 余额为 100.00（与 README 声明一致）
**And** 可正常发布带悬赏的需求（不触发 `reward must not exceed available balance`）

### AC-16（FR-16 / F16）
**Given** 用户使用邮箱登录
**When** 查看登录表单
**Then** label 显示"学号/邮箱"
**And** placeholder 同步体现两者均可

### AC-17（FR-17 / F17）
**Given** 用户访问 `/`
**When** 页面加载
**Then** 导航"首页"正确高亮
**And** 不存在空 HomeView 死代码（或高亮逻辑兼容 `/`）

### AC-18（FR-18 / F18）
**Given** 管理员查看仲裁订单卡片
**When** 卡片渲染
**Then** `createdAt` 显示为可读格式（如 `2026-10-08 15:43`），非原始 ISO

### AC-19（FR-19 / F19）
**Given** 用户重复触发 `submitReview`
**When** 评价已存在
**Then** 不重复 unshift，行为与 `submitReviewForResponse` 一致

### AC-20（FR-20 / F20）
**Given** 用户点击"全部标记已读"
**When** 请求中
**Then** 按钮 disabled + loading
**And** await 完成后再恢复

### AC-21（FR-21 / F21）
**Given** 用户更新个人资料
**When** PUT `/users/me` 成功
**Then** 不再发冗余 GET 请求
**And** `currentProfile` 由 PUT 响应写入

### AC-22（FR-22 / F22）
**Given** 用户进入资料编辑页
**When** `onMounted` 的 `fetchProfile` 更新 `store.currentUser`
**Then** 表单响应式回填最新值

### AC-23（FR-23 / F23）
**Given** 需求含 `acceptStatusHint`
**When** `mapDemandRecord` 映射
**Then** `DemandDetailView` 中 `acceptStatusHint` 非恒 null，UI 提示正确

### AC-24（FR-24 / F24）
**Given** 订单 `currentUserConfirmedCompletion || providerConfirmed`
**When** requester 角色查看等待提示
**Then** 文案对 requester 角色正确显示

### AC-25（FR-25 / F25）
**Given** 用户头像更新失败
**When** 错误提示
**Then** 使用 `useConfirm`/`useAlert` 统一风格，非原生 `alert`

### AC-26（FR-26 / F26）
**Given** 管理员在拒绝/仲裁/删除对话框提交失败
**When** 错误返回
**Then** 错误就近显示在对话框内，无需滚到页面底部

---

## 6. 边界条件

| 编号 | 边界条件 |
|------|----------|
| F1 | 凭证图片 0 张（不允许，后端已校验 1-3）、3 张（上限）、上传中断/失败、图片 URL 保存失败 |
| F2 | 用户既无收到的评价也无发出的评价、仅一方有评价、评价数据量大 |
| F3 | 操作期间网络断开、并发点击多按钮、订单状态已被他人改变 |
| F4 | `currentUser` 未加载（balance 为 undefined）、frozenBalance 为 null |
| F5 | AI 草稿 `startTime` 缺失、格式异常、与 `endTime` 倒序 |
| F6 | 订单无关联 demand、demand.anonymous 为 null、非匿名需求 |
| F7 | 批量封禁、封禁自己（admin）、解禁未封禁用户 |
| F8 | 并发多个 401、logout 期间新请求、token 在 localStorage 但 user 为 null |
| F9 | 通知 payload 缺关联 ID、关联需求/订单已删除 |
| F10 | `targetParticipantCount` 为 0/1、全部已选、取消选择 |
| F11 | submitting 期间切换页面、连续点击不同操作按钮 |
| F12 | payload.message 缺失、未知错误码、非 409 错误 |
| F13 | 搜索框粘贴长文本、清空、与筛选组合 |
| F14 | 任一并行请求失败、fetchProfile 失败导致无 currentUserId |
| F15 | 既有测试数据余额已为 0 的迁移、admin 与 TEST 账号余额不一致 |
| F16 | 输入既非学号也非邮箱、含空格 |
| F17 | 直接访问 `/`、从其他页跳转 `/` |
| F18 | `createdAt` 为 null、时区差异 |
| F19 | 同一评价被多次推送（WS 重复消息） |
| F20 | 无未读通知、全部已读期间新通知到达 |
| F21 | PUT 成功但响应体缺字段 |
| F22 | `fetchProfile` 失败、用户未编辑直接退出 |
| F23 | `acceptStatusHint` 为 null、字段类型不匹配 |
| F24 | 双方均未确认、双方均确认、仅一方确认 |
| F25 | 头像上传成功但更新失败、网络错误 |
| F26 | 对话框关闭后错误残留、多个对话框同时打开 |

---

## 7. Clarifications（已通过验证作为基线）

以下为本次审查已通过实跑验证的部分，作为**修复工作的基线**，不在本次修复范围内，修复时不得破坏：

### 7.1 后端业务流转基线（已验证正确）

- **四种互动模式业务流转**：`DIRECT_ACCEPT` / `SELECT_ONE` / `SELECT_MANY` / `HELP` 的 发布 → 审核 → 接单/留言/报名 → 执行 → 确认完成 → 评价 → 悬赏金冻结/转移/解冻 全部正确
- **仲裁流程**：发起 → admin 列表 → 裁决完成/取消 正确
- **通知系统**：10 种类型正常推送
- **管理后台**：仪表盘 / 用户管理 / 需求审核 / 仲裁处理 后端逻辑正确
- **驳回需求**：余额正确解冻
- **状态机 / 并发行锁 / 双重确认完成 / 信用分计算**：均正确

### 7.2 AI 草稿生成基线（已验证正确）

- API 端点：`tokenhub.tencentmaas.com` + `deepseek/deepseek-flash`
- 返回结构化草稿正常
- 前端回填大部分字段正常（除 F5 的 `startTime`）

### 7.3 修复约束

- 修复 F1-F26 时**不得改变上述基线行为**
- 涉及后端调整的仅限：F1（`UpdateOrderStatusCommand` 增字段）、F2（`listUserReviews` 双向查询）、F4（可选补 `/users/me/balance`）、F15（init 脚本与 DemoDataInitializer 余额）、F6（可选 `OrderView` 增 `demandAnonymous`）
- 后端业务逻辑、状态机、并发锁、信用分计算、仲裁、通知推送逻辑**保持不变**

### 7.4 Session 2026-10-08 澄清记录

本轮澄清由 Orchestrator 按"AI 优先澄清"原则自动推断，所有方案选择基于项目最佳实践，未向用户提问（无高影响且不可推断的歧义）：

- **Q: F2 评价查询方案选择？** → A: 采用方案 A（后端 `listUserReviews` 返回 `targetId=userId OR reviewerId=userId`），单次请求拿全双向评价，减少前端接口数量与状态分裂。
- **Q: F4 余额校验方案选择？** → A: 删除 `fetchBalance`，直接用 `store.currentUser.balance - frozenBalance`；后端无 `/users/me/balance` 接口，不为兼容旧调用新增。
- **Q: F6 匿名字段映射方案选择？** → A: 采用方案 A（`mapOrderRecord` 显式映射 `anonymous` 字段），前端单点修复，不依赖后端增字段。
- **Q: F15 预置账号余额处理？** → A: 将预置账号（TEST001/TEST002/演示 admin）余额调整为 100.00，与 README 声明一致，利于演示与测试。若团队后续确认"预置账号 0 余额"是设计意图，可改为在 README 更正说明。
- **Q: F1 凭证图片 URL 存储方案？** → A: 后端 `UpdateOrderStatusCommand` 增加 `proofImageUrls` 字段（List<String>）保存凭证 URL，`Order` 实体增加 `proofImageUrls` 持久化字段，`OrderView` 返回该字段供发布者查看。`proofImageCount` 保留作为冗余校验。
- **Q: F13 debounce 时长？** → A: 300ms（行业通用值）。

延后到 plan/analyze 的低风险事项：F1 凭证图片持久化的表结构变更细节（是否新建列或新表）由 plan 阶段定。

---

## 8. Assumptions（假设）

1. **修复环境**：在当前 Monorepo（backend/ + frontend/）上修复，不引入新仓库
2. **复用现有组件**：F1 凭证图片上传复用现有 `ImageUploader`；F7/F25 复用现有 `useConfirm`/`useAlert`；F18 复用项目现有日期格式化工具
3. **F2 方案选择**：默认采用方案 A（后端 `listUserReviews` 返回 `targetId OR reviewerId`），减少前端接口数量；若后端不便调整可采用方案 B
4. **F4 方案选择**：默认删除 `fetchBalance`，直接用 `currentUser.balance - frozenBalance`；不为兼容旧调用补后端接口
5. **F6 方案选择**：默认采用方案 A（`mapOrderRecord` 显式映射 `anonymous`），不依赖后端增字段
6. **F15 处理**：默认将预置账号余额调整为 100.00 与 README 一致；若团队确认"预置账号 0 余额"是设计意图，则改为在 README 更正说明（二选一，需团队确认）
7. **debounce 时长**：F13 默认 300ms（行业通用值）
8. **不影响 API 契约**：除 F1/F2/F4/F6/F15 列明的后端调整外，其余修复以前端为主，不改变现有 API 契约
9. **测试策略**：前端修复后通过 Playwright 实跑验证；后端调整通过现有单元测试 + H2 集成测试覆盖

---

## 9. 自主决策记录

| # | 决策项 | 选择 | 理由 |
|---|--------|------|------|
| D1 | spec 目录编号策略 | sequential（`001-`） | `.zhanlu/spec/init-options.json` 不存在，按默认 sequential；`specs/` 为空，首个编号 `001` |
| D2 | short name | `campushub-fixes` | 涵盖全栈（前端为主 + 少量后端），简明 |
| D3 | spec 模板来源 | 自主编排标准章节 | `.zhanlu/spec/templates/spec-template.md` 不存在，按轻量化模式跳过，采用通用 spec 结构 |
| D4 | extensions.yml 处理 | 跳过 | `.zhanlu/spec/config/extensions.yml` 不存在，无 Pre/After Hook |
| D5 | F2 实现方案 | 方案 A（后端双向查询） | 减少前端接口数量与状态分裂，单次请求拿全 |
| D6 | F4 实现方案 | 删除 `fetchBalance` | 后端无该接口，404 永远吞掉，直接复用 `currentUser.balance` |
| D7 | F6 实现方案 | 方案 A（mapOrderRecord 映射） | 前端单点修复，不依赖后端增字段，影响面小 |
| D8 | F13 debounce 时长 | 300ms | 行业通用值，兼顾流畅与请求量 |
| D9 | F15 默认处理 | 调整预置账号余额为 100 | 与 README 声明一致，利于演示/测试；标注为需团队确认的二选一项 |
| D10 | 严重程度分档 | 严重 4 / 中等 12 / 轻微 10 | 按是否阻断核心流转 + 影响范围 |
| D11 | 非功能性需求 | 排除 | 用户明确要求聚焦功能完整性、可用性、人机交互友好性 |
| D12 | Clarifications 区块 | 已通过验证作为基线 | 用户要求记录已通过验证作为基线，约束修复不得破坏 |

---

**下一步建议**：执行 `/spec.plan` 生成实现计划，或 `/spec.clarify` 对 F2/F4/F6/F15 的方案选择与团队确认。

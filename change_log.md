# 变更日志

说明：以下内容根据近期项目演进整理，按日期归纳主要功能、修复与联调变更，统一使用 UTF-8 编码维护。

## 2026-05-11

- `feat(ui)` 初步搭建展示层与前端路由骨架。
- `feat(service)` 初步搭建后端业务逻辑层。
- `feat(auth)` 调整注册流程中的基础校验与邮箱相关逻辑。

## 2026-05-12

- `feat(ui)` 清理非首页演示内容，优化页面文案与可读性。

## 2026-05-14

- `feat(ui)` 重构页面结构与视觉布局。

## 2026-05-15

- `fix(auth)` 修复后端 token 解析问题。
- `feat(integration)` 打通前后端基础联调，补齐邮箱相关逻辑与前端代理配置。
- `feat(frontend)` 前端移除预置假数据，切换为后端接口驱动。

## 2026-05-16

- `feat(auth)` 完成用户仓储的 MyBatis 实现与单测。
- `chore(db)` 新增本地 MySQL 初始化脚本、使用说明与敏感配置隔离方案。

## 2026-05-17

- `feat(notification)` 完成通知模块 DAO 实现与测试。
- `feat(review)` 完成评价模块 DAO 实现，并补充并发防重底线。
- `feat(order)` 完成订单持久化链路与并发防重处理。
- `feat(demand)` 完成需求持久化实现，并对齐实体与底层表结构。

## 2026-05-18

- `fix(dao)` 修复持久化层结构一致性问题，补齐 `updated_at` 等关键字段。
- `feat(admin)` 增强管理后台查询能力，并补充 `is_approved` 等审核字段。
- `feat(recommendation)` 新增推荐系统用户行为日志 DAO 与测试。
- `feat(admin-ui)` 增加后台统计数据与用户管理能力，优化刷新与部分文案。

## 2026-05-19

- `chore(repo)` 清理本地 AI 工具生成文件，避免误提交。
- `feat(storage)` 后端在存在数据库实现时优先使用数据库作为数据源。

## 2026-05-22

- `fix(db)` 修复初始化脚本中的重复建表问题。

## 2026-05-24

- `feat(auth-ui)` 优化注册流程，增加邮箱前缀检测与后缀选择能力。
- `chore(db-docs)` 更新数据库配置快速指引文档。

## 2026-05-25

- `feat(order)` 订单完成改为双确认流程，并优化用户反馈文案。
- `feat(profile)` 在订单、需求等页面补充用户信用分展示。
- `feat(notification)` 通知已读状态改为异步处理模式。
- `chore(cicd)` 构建 CI/CD 基础流水线环境。
- `test(integration)` 补充正常与异常流程的集成测试。
- `feat(auth)` 注册流程支持头像上传，优化验证码发送状态与错误提示。
- `feat(frontend)` 登录状态持久化到 `localStorage`。
- `feat(mail)` 完善服务层邮件发送与校验逻辑。

## 2026-05-26

- `fix(order)` 实现订单“双确认完成”流程，保留接单方凭证信息。
- `test(order)` 更新并修复受影响的单元测试与集成测试。
- `feat(api)` `submitReview` 返回视图补充作者信息，修复前端评价显示问题。
- `feat(frontend)` 统一订单状态中文展示、时间线文案与按钮交互。
- `feat(api)` 补齐第二轮后端联调用字段与聚合能力：
  - 推荐接口升级为返回 `rank / score / reasonTags / demand`
  - 需求详情补充 `canAccept / acceptDisabledReason / canStartExecution / canViewAcceptNote / canSubmitAcceptNote`
  - 订单详情补充 `reviews / currentUserReviewed / pendingReviewTarget / completionHint`
  - 通知接口补充 `targetType / targetId / targetTitle / actionHint`
- `feat(admin)` 完成第二轮审核闭环与后台增强：
  - 审核拒绝持久化 `reviewedBy / reviewedAt / reviewReason`
  - 后台用户列表支持 `searchField / role / status / sortBy / sortDirection`
  - `dailyActiveUsers` 改为基于真实业务动作聚合
- `feat(contract)` 补齐第二轮枚举、异常与兼容输出：
  - 新增需求分类 `ERRAND`
  - 兼容排序别名 `RECOMMENDED / TIME_RECENT / HIGHEST_REWARD`
  - 错误响应补充 `errorCode / details`，保留 `errors` 兼容字段
  - 用户资料与摘要同时输出 `creditScore` 与 `credit_score`
- `test(contract)` 补充推荐、通知、viewer-aware 字段、审核拒绝、分类兼容、后台查询与 DAU 统计相关测试。
- `fix(service)` 收紧服务层规则：
  - 公开需求列表仅返回 `PENDING` 且未过期的需求
  - 发布与编辑需求时校验 `reward <= balance - frozenBalance`
  - 管理员禁止发布需求与接单
  - 资料更新新增昵称唯一性校验
  - 信用分改为平滑更新算法
- `fix(notification)` 统一通知模板生成与审核提醒收口：
  - 将订单接单、订单状态更新、待确认完成、收到评价、需求待审核、需求驳回等通知，统一收口到 `NotificationApplicationServiceImpl` 内部模板构造
  - 需求发布后的管理员审核提醒改为通过通知服务统一创建
  - 驳回通知增加历史调用兼容处理，避免旧调用方传入整段文案时造成正文重复拼接
  - 本地在 `backend` 目录执行 `mvn test`，结果为 `108 tests, 0 failures`
- `docs(sync)` 同步联调与协作文档：
  - 更新 `P4-前后端对接修订规范.md` 的通知联调口径
  - 新增 `P4-数据库同学更新清单.md`

## 2026-05-27

- `feat(frontend)` 调整需求列表排序交互为三个按钮：`按时间排序`、`按报酬排序`、`推荐排序`，同一按钮再次点击时在正序与逆序之间切换。
- `feat(frontend)` 推荐排序改为完全沿用后端返回顺序，正序直接展示后端结果，逆序仅对后端结果整体反转，不再在前端做额外排序计算。
- `feat(frontend)` 完成管理员审核需求时拒绝理由的输入与展示闭环，并同步优化相关提示信息和错误文案，减少英文提示外露。
- `fix(ci)` 修复 Maven 连接/请求超时问题，调整 GitLab CI 中前后端任务的超时与执行配置，提升流水线稳定性。
- `test(frontend)` 重新执行前端构建验证，确认排序交互调整后仍可正常通过 `npm run build`。

## 2026-05-28

- `fix(ci)` 排查并修复流水线工作日志与工作产物不一致的问题，优化 CI 配置。

## 2026-05-29

- `fix(auth)` 修复本地测试注册账号时验证码日志模式配置与前端错误提示，改善本地开发体验。

## 2026-05-30 ~ 2026-05-31

- `fix(ci)` 多轮排查与修复集成测试在 CI 环境中卡死的问题，提升流水线成功率。

## 2026-06-01

- `docs` 提交 P4 任务所需文档，包括 Bug 修复日志、AI 调试对决实验报告、AI 代码信任度实验报告和 AI 协作反思日志。
- `fix(ci)` 修复流水线配置，合并 `dev_ai` 分支到 `dev`。

## 2026-06-02

- `fix(frontend)` 修复"委托代办（ERRAND）"分类前后端不一致的问题，统一分类枚举。
- `feat(frontend)` 全局 UI/UX 改善：
  - 去除"我的"界面中冗余的"功能入口"板块，将退出登录按钮移至页面底部并改为横向全宽样式。
  - 去除"编辑个人资料"界面中的本地上传头像功能，仅保留头像链接输入。
  - 在全局导航栏中将"管理后台"入口插入到"我的"之前，提升管理员用户的访问效率。

## 2026-06-04

- `feat(devops)` 新增一键启动脚本 `start.sh` 与根目录 `README.md` 项目说明文档，支持一个命令同时拉起前后端服务。

## 2026-06-05

- `feat(ui)` 优化用户登录与注册界面的 UI 设计，提升视觉体验与操作便捷性。

## 2026-06-06

- `feat(db)` 在初始化脚本 `init_schema.sql` 中新增测试用户账号 `TEST001`，密码 `Admin123!`，便于本地验证。

## 2026-06-07

- `fix(frontend)` 修复订单详情页状态变更后显示"未找到需求"的问题：`acceptDemand`、`startOrder`、`completeOrder`、`cancelOrder` 等操作改用 `fetchDemandDetail` 单独刷新当前需求，避免 `fetchDemands` 全量覆盖列表导致需求丢失。
- `feat(devops)` 优化启动脚本 `start.sh`：启动前自动检测并清理占用 8080/5173 端口的旧进程，修复 WSL 环境下 `netstat` 输出格式差异导致的清理失败问题，确保 Ctrl+C 后不留残余进程。
- `feat(db)` 在初始化脚本中补充测试用户账号 `TEST002`，密码 `Admin123!`；完善 README 项目说明。
- `docs` 加入核心演示路径视频。

## 2026-06-08

- `feat(order)` 完善订单完成确认流程并优化评价功能：
  - 订单完成备注支持"接单方确认完成，等待需求方确认"与"需求方确认完成，等待接单方确认"两种状态文案。
  - 新增顺序限制：接单方必须先确认完成，需求方才可确认。
  - `ReviewRepository` 扩展按评价作者查询评价列表的能力。
  - `ReviewApplicationServiceImpl` 合并接收和发出评价列表并按时间倒序排列。
  - 前端 `OrderDetailView` 显示个性化订单时间线备注，区分接单方和需求方确认状态。
  - 订单详情页新增需求描述、校园区域展示，优化时间线和评价列表展示效果。
  - 订单操作按钮逻辑：需求方仅在接单方完成确认后才可操作。
  - `ProfileView` 新增"别人对我评价"和"我对别人评价"两部分，分类展示用户评价并支持跳转订单详情。
  - 新增 CSS 样式优化评价列表及个人资料页布局。

## 2026-06-09

- `feat(frontend)` 页面切换增加淡入淡出过渡动画（`<Transition name="fade" mode="out-in">`），提升 SPA 流畅感。
- `fix(frontend)` 修复过渡动画导致的多根节点渲染异常：`AdminView`、`ProfileView`、`DemandDetailView`、`OrderDetailView`、`ProfileEditView` 加包装 `<div>` 确保单一根元素；将 `AdminView` 的 `<teleport>` 移入包装层内部解决页面切换锁死问题。
- `feat(frontend)` 危险操作全覆盖二次确认：
  - 取消订单（订单列表页 + 订单详情页）
  - 完成订单（需求详情页 + 订单详情页 + 订单列表页）
  - 接单（需求详情页）
  - 修改个人信息保存（资料编辑页）
  - 发布需求（需求发布页）
- `feat(frontend)` 接单方提交完成确认后，订单列表与详情页的"提交完成确认"/"确认完成"按钮自动隐藏，避免重复操作。
- `feat(frontend)` 需求发布页的报酬输入框下方增加当前可用余额提示。
- `feat(ui)` `NotFoundView` 与各页面空状态增加 emoji 图标渲染：需求列表 `📋`、订单列表 `📦`、消息列表 `🔔`、未找到需求 `🔍`，其余空状态默认 `📭`。
- `fix(ci)` 修复流水线配置错误问题。
- `fix(frontend)` 修复 CI 中委托代办分类前后端不一致问题，统一分类枚举。

## 2026-06-10

- `feat(demand-list)` 优化需求列表和用户展示细节，改善列表页信息密度与可读性。

## 2026-06-11

- `feat(demand-list)` 优化需求列表和用户展示细节（续）。

## 2026-06-12

- `feat(finance)` 实现需求报酬冻结与解冻及订单报酬转账功能：
  - 发布需求时冻结报酬金额
  - 订单完成时将冻结金额转账给接单方
  - 订单取消或需求撤回时自动解冻金额


## 2026-06-13

- `feat(notification)` 添加需求审核通过通知功能，审核通过时向发布方发送系统消息。

## 2026-06-14

- `feat(api)` 实现接单状态个性化提示及匿名评价视图映射，改善匿名用户评价展示。
- `docs` 补充核心类图 mmd 文件，完善数据库设计文档（ER 图与建表 SQL）。
- `feat(ui)` 优化多个页面添加刷新和返回功能，提升页面交互体验。
- `refactor(demand)` 合并需求的开始和结束时间为 `datetime-local` 输入组件，统一时间选择体验。

## 2026-06-15

- `fix(devops)` 强制 `start.sh` 使用 LF 换行，新增 `.gitattributes` 避免 Windows checkout 后报 `$'\r'` 错误。
- `feat(frontend)` 需求列表增加"加载更多"按钮替代分页，发布需求按钮改为悬浮 FAB，配合无底滚动体验。
- `fix(frontend)` 回退发布需求按钮为内嵌工具栏样式，新增右下角"回到顶部"悬浮按钮。
- `fix(frontend)` 修复需求发布页面可用金额显示误差：总余额减去冻结金额得到实际可用余额。

## 2026-06-16

- `feat(frontend)` 新增网站底部页脚信息（关于 CampusHub、联系方式、支持链接和版权信息）。
- `feat(order)` 增加评价提醒功能：订单完成后发布下一单时通过通知提醒用户评价。
- `feat(auth)` 增加忘记密码与密码修改功能：
  - 登录界面密码框下方新增"忘记密码？"链接，点击提示联系管理员
  - 个人中心新增"修改密码"按钮，弹窗输入当前密码和新密码后提交
- `feat(demand)` 增加撤回需求功能：
  - 审核中或开放中的需求允许发布者撤回
  - 撤回后冻结金额自动解冻
  - 详情页新增独立的"管理你的需求"卡片区域
- `fix(frontend)` 修复匿名发布在详情界面显示学号的问题。
- `feat(frontend)` 新增需求发布草稿功能：
  - 填写过程中自动保存到 `localStorage`
  - 离开页面时弹窗询问是否保留草稿
  - 发布成功后自动清除草稿
  - 再次进入发布页面时自动恢复未发布内容
- `fix(frontend)` 草稿保留逻辑增加 `draftDiscarded` 标记，确保用户选择"不保留"后草稿不会被重新写入。

## 2026-06-17

- `feat(auth)` 新增找回密码闭环：
  - 新增 `POST /api/v1/auth/password-reset/code`，向账号邮箱发送验证码
  - 新增 `POST /api/v1/auth/password-reset`，使用验证码与新密码完成重置
- `feat(order)` 新增订单仲裁流程：
  - 订单状态新增 `IN_ARBITRATION`
  - 新增 `POST /api/v1/orders/{orderId}/arbitration`，订单参与方可发起仲裁
  - 新增管理员裁决接口，支持按 `complete/cancel` 决定订单结果并通知双方
- `feat(admin)` 管理员新增订单管理能力：
  - 新增 `DELETE /api/v1/admin/orders/{orderId}` 删除订单
  - 新增 `POST /api/v1/admin/orders/{orderId}/arbitration/resolve` 处理仲裁
- `feat(notification)` 扩展通知类型，补齐仲裁申请与仲裁结果通知模板。
- `test(backend)` 补充找回密码、订单仲裁、管理员裁决与删单单测；在 `backend` 目录执行 `mvn test`，结果为 `112 tests, 0 failures`。
- `docs(sync)` 更新 `P4-前后端对接修订规范.md`，同步密码找回、仲裁与管理员订单接口口径。


## 补充说明

- 本日志偏向阶段性变更归纳，不等同于逐条 git commit 原文。
- 后续若继续迭代，建议保持“按日期分组 + 按主题归纳”的维护方式，避免再次出现编码与可读性问题。

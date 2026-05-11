# P3 - 任务 2：API 设计（接口规范）

说明：以下接口采用 RESTful 风格，基路径为 `/api/v1`。受保护的 API 采用 JWT Bearer Token（Authorization: Bearer <token>）。

核心接口一览（必须覆盖）
- 用户注册 / 登录
- 发布需求
- 浏览需求列表（含筛选）
- 接单（发布者的需求被他人接单，生成订单）
- 查看订单详情
- 提交评价
- 消息通知列表（站内消息）

通用响应结构
- 成功：
  {
  "code":0,
  "message":"OK",
  "data":...
  }
- 失败：
  {
  "code":<非0整数>,
  "message":"错误描述",
  "errors":{...}
  }

HTTP 状态码约定
- 200：成功
- 400：参数校验失败
- 401：未认证或认证失败
- 403：权限不足
- 404：资源未找到
- 409：业务冲突（例如重复注册、重复接单）
- 500：服务器内部错误

错误码（示例）
- 1001：认证失败（Token 无效/过期）
- 1002：参数校验失败
- 1003：资源未找到
- 1004：权限不足
- 1005：操作冲突（例如重复接单）

安全说明
- 密码必须使用强散列（如 bcrypt/argon2）和加盐存储。
- 所有写操作需鉴权并校验权限（例如只有服务方可接单，只有订单参与方可查看订单）。
- 对敏感字段（手机号、身份证）视需要进行脱敏或加密存储。
- 建议开启 HTTPS、登录限流和基础审计日志，避免暴力破解与接口滥用。

接口详细说明

1) 用户注册
- URL：POST /api/v1/auth/register
- 描述：新用户注册
- 请求体（JSON）：
  - studentId string 必填，长度 3-64，学号/账号标识
  - password string 必填，最少 8 位，建议包含大小写字母与数字
  - nickname string 可选，最长 64
  - avatarUrl string 可选，URI 格式
- 返回：
  - 成功：code=0，data：用户概要（不包含密码）
  - 失败：400 参数校验失败，409 学号已注册，500 服务器错误

2) 用户登录
- URL：POST /api/v1/auth/login
- 描述：帐号密码登录，返回 JWT
- 请求体（JSON）：
  - studentId string 必填，长度 3-64
  - password string 必填，最少 8 位
- 返回：
  - 成功：code=0，data：{"token":"<jwt>","expiresIn":3600,"user":{...}}
  - 失败：400 参数校验失败，401 认证失败，500 服务器错误

3) 发布需求
- URL：POST /api/v1/demands
- 权限：需登录（发布者）
- 描述：创建一条互助需求
- 请求体（JSON）：
  - title string 必填，长度 3-200
  - description string 可选，最长 2000
  - category string 必填（枚举："取快递","学习辅导","二手交易","活动组队","其他"）
  - location string 可选，最长 256
  - startTime string 可选，ISO8601
  - endTime string 可选，ISO8601
  - reward number 可选，最小值 0
  - tags string[] 可选，最多 20 项
- 返回：
  - 成功：code=0，data：新的需求对象（含 id）
  - 失败：400 参数校验失败，401 未认证，500 服务器错误

4) 浏览需求列表（含筛选、分页）
- URL：GET /api/v1/demands
- 描述：按条件查询需求、支持分页
- 查询参数：
  - q string 可选：全文/标题搜索
  - category string 可选
  - location string 可选
  - startTimeFrom ISO8601 可选
  - startTimeTo ISO8601 可选
  - sort string 可选（time, distance, reward），默认 time
  - page integer 可选，默认 1，最小值 1
  - size integer 可选，默认 20，范围 1-100
- 返回：
  - 成功：code=0，data：{"items":[],"page":1,"size":20,"total":123}
  - 失败：400 参数校验失败，500 服务器错误

5) 需求详情
- URL：GET /api/v1/demands/{demandId}
- 描述：查看单条需求详情
- 路径参数：demandId 必填
- 返回：
  - 成功：code=0，data：需求对象（含发布者概要）
  - 失败：404 未找到，500 服务器错误

6) 接单（创建订单）
- URL：POST /api/v1/demands/{demandId}/accept
- 权限：需登录（服务方）
- 描述：服务方接受某条需求，系统创建订单并通知双方；如已被接单返回冲突
- 路径参数：demandId
- 请求体（JSON）：
  - note string 可选，最长 500（给发布者的留言）
- 返回：
  - 成功：code=0，data：订单对象（含 orderId、status="accepted"）
  - 失败：400 参数校验失败，401 未认证，403 权限不足，409 操作冲突，500 服务器错误

7) 查看订单详情
- URL：GET /api/v1/orders/{orderId}
- 权限：需登录（订单参与方或管理员）
- 返回：
  - 成功：code=0，data：订单详情（含需求、双方用户概要、状态变更记录）
  - 失败：401 未认证，403 权限不足，404 未找到，500 服务器错误

8) 提交评价
- URL：POST /api/v1/orders/{orderId}/reviews
- 权限：需登录（只有订单双方在完成后可各自提交评价）
- 请求体（JSON）：
  - rating integer 必填，1-5
  - comment string 可选，最长 1000
- 返回：
  - 成功：code=0，data：评价记录
  - 失败：400 参数校验失败，401 未认证，403 权限不足，500 服务器错误

9) 消息通知列表
- URL：GET /api/v1/notifications
- 权限：需登录
- 描述：查询当前用户收到的站内消息，包括接单通知、状态变更通知、评价通知等
- 查询参数：
  - unreadOnly boolean 可选，默认 false，仅返回未读消息
  - page integer 可选，默认 1，最小值 1
  - size integer 可选，默认 20，范围 1-100
- 返回：
  - 成功：code=0，data：{"items":[],"page":1,"size":20,"total":123}
  - 失败：401 认证失败，500 服务器错误

附加建议
- 列表接口建议添加缓存与分页，避免全表扫描。
- 对可能并发修改的操作（接单、取消）使用乐观锁或行级锁保证一致性。
- 日志与审核：管理员需要操作日志与申诉流程。
- 如果后续要扩展大规模消息中心，可以增加 cursor 分页，但当前作业版本先保留 page/size 简洁模式。

10) 管理后台
- 基础说明：管理后台接口需管理员角色（JWT 内包含 `role: admin` 或使用 RBAC 授权），所有 admin 接口返回 403 表示权限不足。
- URL 示例与说明：
  - GET /api/v1/admin/demands/pending — 查询待审核需求（分页、筛选）
  - POST /api/v1/admin/demands/{demandId}/review — 审核需求，body: {action: "approve"|"reject", reason?: string}
  - GET /api/v1/admin/users — 查询用户列表（分页、关键字 q）
  - POST /api/v1/admin/users/{userId}/ban — 封禁用户，body 可选 reason
  - POST /api/v1/admin/users/{userId}/unban — 解封用户
  - GET /api/v1/admin/stats — 仪表盘统计（日活、订单数、分类别统计）
- 返回与错误：成功返回通用结构 code=0，data 包含对应对象；错误返回 401/403/500 与 ErrorResponse。建议在 admin 操作记录操作人（operatorId）与原因。

11) 简单推荐算法
- 基础说明：当前仅实现简单规则推荐，不做在线反馈、候选匹配或模型重训练。推荐结果基于当前用户的历史发布/接单/收藏、需求分类、位置与时间等规则计算。推荐结果包含 `score`（0.0-1.0）、`rank` 与解释标签 `reasonTags`。
- URL 示例与说明：
  - GET /api/v1/recommendations?page=&size= — 为当前登录用户返回推荐需求列表（分页，size <= 50）
- 推荐响应示例：
  {
    "code":0,
    "message":"OK",
    "data":{
      "items":[{"demandId":"...","score":0.92,"rank":1,"reasonTags":["同分类","距离近"]}],
      "page":1,"size":10,"total":123
    }
  }
- 建议：
  - `score` 采用 0.0-1.0 标准化，便于阈值设定；`rank` 从 1 开始。
  - 规则优先级建议为：同分类 > 历史偏好 > 距离 > 时间匹配。
  - 对于位置信息，需明确用户授权与脱敏策略。

结束。

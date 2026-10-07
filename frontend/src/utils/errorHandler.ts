export function translateApiError(payload: any): string {
  const code = Number(payload?.code ?? 0)
  const rawMessage = String(payload?.message ?? payload?.error ?? '').trim()

  const directMap: Record<string, string> = {
    'verification code is invalid': '验证码无效',
    'verification code does not match studentId': '验证码与学号不匹配',
    'verification code sent too frequently': '验证码发送过于频繁，请稍后再试',
    'failed to send verification email': '验证码邮件发送失败，请检查邮箱配置后重试',
    'email already registered': '该邮箱已被注册',
    'studentId already registered': '该学号已被注册',
    'user has been banned': '账号已被封禁',
    'loginId or password is incorrect': '学号或密码错误',
    'missing bearer token': '缺少登录令牌，请重新登录',
    'invalid token format': '登录令牌格式无效，请重新登录',
    'token has expired': '登录已过期，请重新登录',
    'invalid token': '登录令牌无效，请重新登录',
    "cannot update another user's profile": '不能修改其他用户的资料',
    'only participants can review this order': '只有订单参与者才能评价',
    'only completed orders can be reviewed': '只有已完成的订单才能评价',
    'review already submitted for this order': '该订单已经提交过评价',
    'completion already confirmed by this user': '你已经提交过完成确认，请等待对方确认',
    'admin cannot ban self': '不能封禁自己',
    'user is already banned': '该用户已经被封禁',
    'user is already active': '该用户已经是正常状态',
    'only reviewing demands can be reviewed': '只有待审核的需求才能处理',
    'review action must not be blank': '审核操作不能为空',
    'reject reason must not be blank': '拒绝理由不能为空',
    'unsupported review action': '不支持的审核操作',
    'admin role is required': '需要管理员权限',
    'publisher cannot accept own demand': '发布者不能接自己的需求',
    'demand is not available for acceptance': '该需求当前不可接单',
    'demand has already been accepted': '该需求已经被接单',
    'only order participants can update order status': '只有订单参与者才能更新订单状态',
    'only participants or admins can view order': '只有订单参与者或管理员才能查看订单',
    'order is already in target status': '订单已经处于目标状态',
    'only accepted orders can move to in progress': '只有已接单的订单才能进入进行中',
    'only accepter can start the order': '只有接单人才能开始订单',
    'only in progress orders can be completed': '只有进行中的订单才能完成',
    'only accepter can complete the order': '只有接单人才能完成订单',
    'only accepted orders can be cancelled': '只有已接单的订单才能取消',
    'only participants can cancel order': '只有订单参与者才能取消订单',
    'cannot transition back to accepted': '不能回退到已接单状态',
    'banned user cannot operate orders': '已封禁用户不能操作订单',
    'banned user cannot publish demands': '已封禁用户不能发布需求',
    'banned user cannot respond': '账号已被封禁，无法留言或报名',
    'demand contains forbidden words': '需求内容包含敏感词',
    'verification code service is not available': '验证码服务不可用',
    'request too frequent': '请求过于频繁，请稍后再试',
    'nickname already in use': '该昵称已被使用，请更换',
    'reward must not exceed available balance': '报酬不能超过当前可用余额',
    'admin cannot change own role': '不能修改自己的角色',
    'user is already in role': '该用户已是该角色',
    'unsupported role': '不支持的角色类型',
    'demand has expired': '该需求已过期，无法接单',
    'admin cannot publish demands': '管理员账号不能发布需求',
    'admin cannot accept demands': '管理员账号不能接单',
    // ===== 业务模型 2.0：Demand Response 相关 =====
    'active response already exists for this demand': '你已经提交过了，无需重复提交。',
    'demand is not open for responses': '该需求当前已关闭，暂时无法继续留言或报名。',
    'publisher cannot respond to own demand': '不能对自己发布的需求留言或报名。',
    'only pending response can be selected': '这条响应已经被处理，无法重复选择。',
    'only pending response can be accepted': '该回答已经被处理，无法重复采纳。',
    'only pending response can be withdrawn': '该响应已被处理，无法撤回。',
    'only author can withdraw response': '只能撤回自己提交的响应。',
    'only publisher can perform this action': '只有需求发布者才能执行该操作。',
    'response does not belong to this demand': '该响应不属于当前需求。',
    'response content must not be blank': '内容不能为空。',
    'response not found': '响应不存在或已被删除。',
    'user not found': '用户不存在。',
    'demand not found': '需求不存在或已被删除。',
    'DIRECT_ACCEPT demand does not accept responses, use accept endpoint': '该需求为直接接单模式，请使用接单按钮。',
    'responseIds must not be empty': '请至少选择一个报名。',
    'TEAM_UP demand missing targetParticipantCount': '该组队需求缺少目标人数，请重新发布或联系管理员。',
    'SELECT_MANY demand requires targetParticipantCount >= 1': '目标人数必须为不小于 1 的正整数。',
    'targetParticipantCount must not exceed 100': '目标人数不能超过 100。',
    'targetParticipantCount only allowed for SELECT_MANY, current mode: DIRECT_ACCEPT': '只有组队需求才能设置目标人数。',
    'OTHER category interactionMode must be one of DIRECT_ACCEPT/SELECT_ONE/SELECT_MANY': '其他分类的互动模式只能为直接接单、选择一人或组队选择。',
    'cannot change category/interactionMode after responses exist': '已有同学留言或报名，暂时不能修改需求类型。',
    'cannot change interactionMode after responses exist': '已有同学留言或报名，暂时不能修改互动模式。',
    'cannot change targetParticipantCount after responses exist': '已有同学报名，暂时不能修改目标人数。',
    LOGIN_REQUIRED: '请先登录后再操作',
    ADMIN_FORBIDDEN: '管理员不能执行该操作',
    OWN_DEMAND: '不能接自己的需求',
    DEMAND_EXPIRED: '该需求已过期，无法接单',
    DEMAND_NOT_PENDING: '该需求当前不处于待接单状态',
    DEMAND_ALREADY_ACCEPTED: '该需求已被其他同学接单',
    DEMAND_ORDER_CLOSED: '该需求关联订单已关闭，无法接单'
  }

  const codeMap: Record<string, string> = {
    AUTH_FAILED: '登录失败，请检查账号和密码',
    VALIDATION_FAILED: '参数校验失败，请检查输入',
    RESOURCE_NOT_FOUND: '请求的资源不存在或已被删除',
    PERMISSION_DENIED: '没有权限执行该操作',
    BALANCE_INSUFFICIENT: '报酬不能超过当前可用余额',
    REVIEW_REQUIRED: '该内容需要先通过审核',
    REVIEW_ALREADY_SUBMITTED: '该订单已经提交过评价',
    NOT_FOUND: '请求的资源不存在或已被删除',
    CONFLICT: '当前操作与系统状态冲突，请刷新后重试',
    UNAUTHORIZED: '请先登录后再继续',
    FORBIDDEN: '没有权限执行该操作',
    // 业务冲突的兜底文案改为更友好、引导用户的具体提示，避免吓人的“系统状态冲突”
    BUSINESS_CONFLICT: '当前操作未能完成，请刷新页面后重试；若仍有问题，请稍后试。',
    RATE_LIMITED: '请求过于频繁，请稍后再试'
  }

  const errorCode = String(payload?.errorCode ?? payload?.codeName ?? '').trim().toUpperCase()

  // 优先匹配具体消息翻译，再回退到通用错误码
  if (rawMessage in directMap) {
    return directMap[rawMessage]
  }

  // BUSINESS_CONFLICT / code 1005 优先尝试根据 rawMessage 动态提取业务语义，
  // 提取不到再回退到通用兜底文案，避免所有冲突都显示为“系统状态冲突”
  if (errorCode === 'BUSINESS_CONFLICT' || code === 1005) {
    const dynamic = matchDynamicBusinessConflict(rawMessage)
    if (dynamic) {
      return dynamic
    }
    return codeMap.BUSINESS_CONFLICT
  }

  if (errorCode && codeMap[errorCode]) {
    return codeMap[errorCode]
  }

  if (code === 5000) {
    return '系统发生错误，请稍后重试'
  }

  if (code === 1001) {
    return '登录失败，请检查账号和密码'
  }

  if (code === 1004) {
    return '没有权限执行该操作'
  }

  const nullMatch = rawMessage.match(/^([a-zA-Z][a-zA-Z0-9]*) must not be null$/)
  if (nullMatch) {
    return `${translateFieldName(nullMatch[1])}不能为空`
  }

  const blankMatch = rawMessage.match(/^([a-zA-Z][a-zA-Z0-9]*) must not be blank$/)
  if (blankMatch) {
    return `${translateFieldName(blankMatch[1])}不能为空`
  }

  const minLengthMatch = rawMessage.match(/^([a-zA-Z][a-zA-Z0-9]*) must be at least (\d+) characters$/)
  if (minLengthMatch) {
    return `${translateFieldName(minLengthMatch[1])}至少需要 ${minLengthMatch[2]} 个字符`
  }

  const maxLengthMatch = rawMessage.match(/^([a-zA-Z][a-zA-Z0-9]*) length must not exceed (\d+)$/)
  if (maxLengthMatch) {
    return `${translateFieldName(maxLengthMatch[1])}长度不能超过 ${maxLengthMatch[2]} 个字符`
  }

  const betweenMatch = rawMessage.match(/^([a-zA-Z][a-zA-Z0-9]*) length must be between (\d+) and (\d+)$/)
  if (betweenMatch) {
    return `${translateFieldName(betweenMatch[1])}长度必须在 ${betweenMatch[2]} 到 ${betweenMatch[3]} 个字符之间`
  }

  const rangeMatch = rawMessage.match(/^([a-zA-Z][a-zA-Z0-9]*) must be between (\d+) and (\d+)$/)
  if (rangeMatch) {
    return `${translateFieldName(rangeMatch[1])}必须在 ${rangeMatch[2]} 到 ${rangeMatch[3]} 之间`
  }

  const greaterThanMatch = rawMessage.match(/^([a-zA-Z][a-zA-Z0-9]*) must be greater than or equal to (\d+)$/)
  if (greaterThanMatch) {
    return `${translateFieldName(greaterThanMatch[1])}必须大于或等于 ${greaterThanMatch[2]}`
  }

  const unsupportedMatch = rawMessage.match(/^unsupported ([a-zA-Z][a-zA-Z0-9]*)[: ]+(.+)$/)
  if (unsupportedMatch) {
    return `不支持的${translateFieldName(unsupportedMatch[1])}：${unsupportedMatch[2]}`
  }

  const onlyMatch = rawMessage.match(/^only (.+) can (.+)$/)
  if (onlyMatch) {
    return `只有${onlyMatch[1]}才能${onlyMatch[2]}`
  }

  if (rawMessage) {
    if (/[A-Za-z]/.test(rawMessage)) {
      return '请求失败，请检查输入后重试'
    }
    return rawMessage
  }

  return '请求失败，请稍后重试'

  function matchDynamicBusinessConflict(message: string): string | null {
    // selection exceeds targetParticipantCount (selected=0, new=2, target=1)
    const exceedMatch = message.match(/selection exceeds targetParticipantCount.*target=(\d+)/)
    if (exceedMatch) {
      return `选择人数已超过目标人数，最多只能选择 ${exceedMatch[1]} 人。`
    }

    // response 123 is not pending
    const respNotPending = message.match(/^response \d+ is not pending$/)
    if (respNotPending) {
      return '该报名已经被处理，无法继续操作。'
    }

    // demand interactionMode is X, expected Y
    const modeMismatch = message.match(/^demand interactionMode is (\w+), expected (\w+)$/)
    if (modeMismatch) {
      return `该需求的互动模式为“${translateInteractionMode(modeMismatch[1])}”，不支持当前操作。`
    }

    // only PENDING demand can be selected, current status: X
    const demandStatus = message.match(/^only PENDING demand can be selected, current status: (\w+)$/)
    if (demandStatus) {
      return `该需求当前不处于开放状态，无法继续选择。`
    }

    // targetParticipantCount only allowed for SELECT_MANY, current mode: X
    const targetMode = message.match(/^targetParticipantCount only allowed for SELECT_MANY, current mode: (\w+)$/)
    if (targetMode) {
      return `只有组队需求才能设置目标人数，当前模式为“${translateInteractionMode(targetMode[1])}”。`
    }

    return null
  }

  function translateInteractionMode(mode: string): string {
    switch (mode) {
      case 'DIRECT_ACCEPT': return '直接接单'
      case 'SELECT_ONE': return '选择一人'
      case 'SELECT_MANY': return '组队选择'
      case 'HELP': return '采纳回答'
      default: return mode
    }
  }

  function translateFieldName(field: string): string {
    const fieldMap: Record<string, string> = {
      email: '邮箱',
      verificationCode: '验证码',
      studentId: '学号',
      password: '密码',
      nickname: '昵称',
      avatarUrl: '头像链接',
      loginId: '登录标识',
      userId: '用户ID',
      orderId: '订单ID',
      demandId: '需求ID',
      title: '标题',
      description: '描述',
      location: '地点',
      startTime: '开始时间',
      endTime: '结束时间',
      category: '分类',
      campusZone: '校区',
      reward: '悬赏金额',
      tags: '标签',
      page: '页码',
      rating: '评分',
      comment: '评价内容',
      targetStatus: '目标状态',
      proofImageCount: '证明图片数量',
      action: '操作类型',
      reason: '原因',
      searchField: '搜索字段',
      sortBy: '排序字段',
      sortDirection: '排序方向',
      responseId: '响应ID',
      responseIds: '响应ID列表',
      content: '内容',
      targetParticipantCount: '目标人数',
      interactionMode: '互动模式'
    }

    return fieldMap[field] ?? field
  }
}

export function handleError(err: unknown, fallback = '操作失败'): string {
  try {
    if (err == null) return fallback
    if (err instanceof Error) {
      const msg = err.message || String(err)
      // common network error mapping
      if (/failed to fetch|networkerror|network error/i.test(msg)) {
        return '网络异常，请检查网络或后端服务'
      }
      // 已被 translateApiError 翻译过的中文消息直接返回
      if (!/[A-Za-z]/.test(msg)) {
        return msg || fallback
      }
      return fallback
    }

    // try parse structured payloads
    if (typeof err === 'object') {
      // common pattern from fetch wrapper: { status, message }
      const anyErr = err as any
      if (anyErr?.message) {
        const message = String(anyErr.message)
        return /[A-Za-z]/.test(message) ? fallback : message
      }
      if (anyErr?.error) {
        const message = String(anyErr.error)
        return /[A-Za-z]/.test(message) ? fallback : message
      }
      if (anyErr?.status === 0) return '网络异常，请检查网络或后端服务'
    }

    return String(err) || fallback
  } catch (e) {
    return fallback
  }
}

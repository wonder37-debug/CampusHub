import { defineStore } from 'pinia'

import type {
  AdminDashboardSummary,
  AccountRecord,
  AuthFormInput,
  CategoryStat,
  CampusZone,
  DashboardSummary,
  DemandCategory,
  DemandFormInput,
  DemandRecord,
  DemandStatus,
  EmailVerificationRecord,
  NotificationRecord,
  NotificationType,
  OrderRecord,
  OrderStatus,
  ProfilePatchInput,
  PublicUser,
  ReviewRecord
} from '@/types/campushub'
import {
  DEMAND_CATEGORY_OPTIONS,
  type DemandCategory as DemandCategoryCode
} from '@/types/campushub'

function buildAvatar(seed: string): string {
  return `https://api.dicebear.com/7.x/initials/svg?seed=${encodeURIComponent(seed)}`
}

function normalizeEmail(email: string): string {
  return email.trim().toLowerCase()
}

function now(offsetMinutes = 0): string {
  return new Date(Date.now() + offsetMinutes * 60_000).toISOString()
}

function cloneUser(account: AccountRecord): PublicUser {
  const { password: _, ...user } = account
  return { ...user }
}

const API_BASE = import.meta.env.VITE_API_BASE || '/api/v1'

function unwrapApiPayload<T>(payload: any): T {
  if (payload && typeof payload === 'object' && 'data' in payload) {
    return payload.data as T
  }

  return payload as T
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
    action: '操作类型'
  }

  return fieldMap[field] ?? field
}

function translateApiError(payload: any): string {
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
    'cannot update another user\'s profile': '不能修改其他用户的资料',
    'only participants can review this order': '只有订单参与者才能评价',
    'only completed orders can be reviewed': '只有已完成的订单才能评价',
    'review already submitted for this order': '该订单已经提交过评价',
    'admin cannot ban self': '不能封禁自己',
    'user is already banned': '该用户已经被封禁',
    'user is already active': '该用户已经是正常状态',
    'only reviewing demands can be reviewed': '只有待审核的需求才能处理',
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
    'demand contains forbidden words': '需求内容包含敏感词',
    'verification code service is not available': '验证码服务不可用',
    'request too frequent': '请求过于频繁，请稍后再试'
  }

  if (rawMessage in directMap) {
    return directMap[rawMessage]
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

  if (code === 1005 && rawMessage) {
    return directMap[rawMessage] ?? '业务冲突，请检查输入后重试'
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

  return rawMessage || '请求失败'
}

async function requestJson<T>(path: string, init: RequestInit = {}, token?: string): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init.headers ?? {})
    }
  })

  const payload = await response.json().catch(() => null)
  if (!response.ok) {
    throw new Error(translateApiError(payload))
  }

  return unwrapApiPayload<T>(payload)
}

function mapUserSummary(raw: any): PublicUser {
  return {
    id: String(raw.id ?? ''),
    email: String(raw.email ?? ''),
    studentId: String(raw.studentId ?? ''),
    nickname: String(raw.nickname ?? '匿名校友'),
    avatarUrl: String(raw.avatarUrl ?? buildAvatar(String(raw.nickname ?? raw.studentId ?? '用户'))),
    creditScore: Number(raw.creditScore ?? 0),
    role: String(raw.role ?? 'USER') as AccountRecord['role'],
    status: String(raw.status ?? 'ACTIVE') as AccountRecord['status']
  }
}

function mapDemandRecord(raw: any): DemandRecord {
  const publisherDisplayName = raw.publisherDisplayName ?? raw.publisherName ?? raw.creator?.nickname ?? '匿名'
  return {
    id: String(raw.id ?? raw.demandId ?? ''),
    title: String(raw.title ?? ''),
    description: String(raw.description ?? ''),
    category: String(raw.category ?? 'OTHER') as DemandCategoryCode,
    campusZone: String(raw.campusZone ?? 'GULOU') as CampusZone,
    location: String(raw.location ?? ''),
    startTime: String(raw.startTime ?? now()),
    endTime: String(raw.endTime ?? now()),
    reward: Number(raw.reward ?? 0),
    status: String(raw.status ?? 'PENDING') as DemandStatus,
    anonymous: Boolean(raw.anonymous ?? false),
    anonymousCode: raw.anonymousCode ?? null,
    publisherId: raw.publisherId == null ? '' : String(raw.publisherId),
    publisherName: String(publisherDisplayName),
    publisherAvatar: String(raw.publisherAvatar ?? buildAvatar(String(publisherDisplayName))),
    tags: Array.isArray(raw.tags) ? raw.tags.map((tag: any) => String(tag)) : [],
    createdAt: String(raw.createdAt ?? now()),
    updatedAt: String(raw.updatedAt ?? raw.createdAt ?? now()),
    distanceKm: Number(raw.distanceKm ?? 0)
  }
}

function mapOrderRecord(raw: any): OrderRecord {
  const demand = raw.demand ?? {}
  const requester = raw.requester ?? {}
  const provider = raw.provider ?? raw.accepter ?? {}

  return {
    id: String(raw.orderId ?? raw.id ?? ''),
    demandId: String(demand.id ?? raw.demandId ?? ''),
    demandTitle: String(demand.title ?? raw.demandTitle ?? ''),
    requesterId: String(requester.id ?? raw.publisherId ?? raw.requesterId ?? demand.publisherId ?? ''),
    requesterName: String(requester.nickname ?? raw.publisherDisplayName ?? raw.requesterName ?? demand.publisherDisplayName ?? ''),
    requesterAvatar: String(requester.avatarUrl ?? raw.requesterAvatar ?? raw.publisherAvatar ?? buildAvatar(String(requester.nickname ?? raw.publisherDisplayName ?? ''))),
    serviceProviderId: String(provider.id ?? raw.accepterId ?? raw.serviceProviderId ?? ''),
    serviceProviderName: String(provider.nickname ?? raw.accepterName ?? raw.serviceProviderName ?? ''),
    serviceProviderAvatar: String(provider.avatarUrl ?? raw.serviceProviderAvatar ?? buildAvatar(String(provider.nickname ?? raw.accepterName ?? ''))),
    status: String(raw.status ?? 'ACCEPTED') as OrderStatus,
    note: String(raw.acceptNote ?? raw.note ?? ''),
    proofSubmitted: Boolean(raw.proofSubmitted ?? false),
    proofImageCount: Number(raw.proofImageCount ?? 0),
    createdAt: String(raw.createdAt ?? now()),
    updatedAt: String(raw.updatedAt ?? raw.createdAt ?? now()),
    completedAt: String(raw.completedAt ?? ''),
    timeline: Array.isArray(raw.statusHistory)
      ? raw.statusHistory.map((entry: any) => ({
          at: String(entry.changedAt ?? entry.createdAt ?? now()),
          label: String(entry.note ?? entry.toStatus ?? '')
        }))
      : []
  }
}

function mapNotificationRecord(raw: any, fallbackReceiverId = ''): NotificationRecord {
  return {
    id: String(raw.id ?? ''),
    receiverId: String(raw.receiverId ?? fallbackReceiverId),
    type: String(raw.type ?? 'STATUS_CHANGED') as NotificationType,
    content: String(raw.content ?? raw.title ?? ''),
    isRead: Boolean(raw.read ?? raw.isRead ?? false),
    createdAt: String(raw.createdAt ?? now()),
    relatedId: String(raw.relatedId ?? '')
  }
}

function nextId(prefix: string): string {
  return `${prefix}-${Math.random().toString(36).slice(2, 9)}`
}

export const useCampusHubStore = defineStore('campusHub', {
  state: () => ({
    // 尝试从 localStorage 恢复登录态以持久化会话
    currentUserId: (typeof window !== 'undefined' && localStorage.getItem('campushub.userId')) || '',
    token: (typeof window !== 'undefined' && localStorage.getItem('campushub.token')) || '',
    currentProfile: ((): PublicUser | null => {
      if (typeof window === 'undefined') return null
      const raw = localStorage.getItem('campushub.profile')
      if (!raw) return null
      try {
        return JSON.parse(raw) as PublicUser
      } catch {
        return null
      }
    })(),
    accounts: [] as AccountRecord[],
    demands: [] as DemandRecord[],
    orders: [] as OrderRecord[],
    reviews: [] as ReviewRecord[],
    notifications: [] as NotificationRecord[],
    adminUsers: [] as PublicUser[],
    adminDashboard: null as AdminDashboardSummary | null,
    adminPendingDemands: [] as DemandRecord[],
    verificationCodes: {} as Record<string, EmailVerificationRecord>,
    appMessage: '校园互助平台已加载基础业务数据。'
  }),

  getters: {
    currentUser(state): PublicUser | null {
      return state.currentProfile
    },

    accountOptions(state): PublicUser[] {
      return state.adminUsers
    },

    unreadNotificationCount(state): number {
      return state.notifications.filter((notification) => notification.receiverId === state.currentUserId && !notification.isRead).length
    },

    currentUserNotifications(state): NotificationRecord[] {
      return state.notifications
        .filter((notification) => notification.receiverId === state.currentUserId)
        .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
    },

    currentUserDemands(state): DemandRecord[] {
      return state.demands
        .filter((demand) => demand.publisherId === state.currentUserId)
        .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
    },

    currentUserOrders(state): OrderRecord[] {
      return state.orders
        .filter((order) => order.requesterId === state.currentUserId || order.serviceProviderId === state.currentUserId)
        .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
    },

    currentUserReviews(state): ReviewRecord[] {
      return state.reviews
        .filter((review) => review.targetId === state.currentUserId || review.reviewerId === state.currentUserId)
        .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
    },

    pendingApprovals(state): DemandRecord[] {
      return state.demands
        .filter((demand) => demand.status === 'REVIEWING')
        .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
    },

    categoryStats(state): CategoryStat[] {
      return DEMAND_CATEGORY_OPTIONS.map((category) => ({
        category: category as DemandCategoryCode,
        total: state.demands.filter((demand) => demand.category === category).length
      }))
    },

    dashboardSummary(state): DashboardSummary {
      const averageCredit = Math.round(state.accounts.reduce((sum, account) => sum + account.creditScore, 0) / state.accounts.length)

      return {
        openDemands: state.demands.filter((demand) => demand.status === 'PENDING').length,
        activeOrders: state.orders.filter((order) => order.status === 'IN_PROGRESS' || order.status === 'ACCEPTED').length,
        unreadNotifications: state.notifications.filter((notification) => notification.receiverId === state.currentUserId && !notification.isRead).length,
        pendingApprovals: state.demands.filter((demand) => demand.status === 'REVIEWING').length,
        averageCredit
      }
    },

    popularCategories(state): DemandCategory[] {
      const ordered = [...state.demands].sort((left, right) => {
        const leftScore = left.status === 'PENDING' ? 1 : 0
        const rightScore = right.status === 'PENDING' ? 1 : 0
        return rightScore - leftScore || right.createdAt.localeCompare(left.createdAt)
      })

      return ordered.slice(0, 4).map((demand) => demand.category)
    }
  },

  actions: {
    async hydrateAuthenticatedState(): Promise<void> {
      if (!this.token) {
        return
      }

      await this.fetchProfile()
      await this.fetchDemands()
      await this.fetchOrders()
      await this.fetchNotifications()
    },

    async initializeFromStorage(): Promise<void> {
      if (typeof window === 'undefined') return
      const token = localStorage.getItem('campushub.token') || ''
      if (!token) return
      this.token = token
      const profileRaw = localStorage.getItem('campushub.profile')
      if (profileRaw) {
        try {
          this.currentProfile = JSON.parse(profileRaw)
          this.currentUserId = this.currentProfile?.id || ''
        } catch {
          // ignore
        }
      }

      try {
        await this.hydrateAuthenticatedState()
      } catch {
        // ignore hydrate errors on startup
      }
    },

    getDemandById(demandId: string): DemandRecord | undefined {
      return this.demands.find((demand) => demand.id === demandId)
    },

    getOrderById(orderId: string): OrderRecord | undefined {
      return this.orders.find((order) => order.id === orderId)
    },

    getUserById(userId: string): PublicUser | undefined {
      const account = this.accounts.find((item) => item.id === userId)
      return account ? cloneUser(account) : undefined
    },

    logout(): void {
      this.currentUserId = ''
      this.token = ''
      this.currentProfile = null
      try {
        localStorage.removeItem('campushub.token')
        localStorage.removeItem('campushub.userId')
        localStorage.removeItem('campushub.profile')
      } catch {
        // ignore
      }
    },

    async login(form: AuthFormInput): Promise<PublicUser> {
      const payload = await requestJson<any>('/auth/login', {
        method: 'POST',
        body: JSON.stringify({
          loginId: form.studentId.trim(),
          password: form.password
        })
      })

      const authData = unwrapApiPayload<any>(payload)
      const user = mapUserSummary(authData.user ?? authData)
      this.currentUserId = user.id
      this.currentProfile = user
      this.token = String(authData.token ?? '')
      try {
        localStorage.setItem('campushub.token', this.token)
        localStorage.setItem('campushub.userId', user.id)
        localStorage.setItem('campushub.profile', JSON.stringify(user))
      } catch {
        // ignore storage errors
      }
      await this.hydrateAuthenticatedState()
      return user
    },

    async sendRegistrationCode(email: string, studentId: string): Promise<string> {
      const normalizedEmail = normalizeEmail(email)
      const normalizedStudentId = studentId.trim()
      if (!normalizedEmail || !normalizedEmail.includes('@')) {
        throw new Error('请输入有效的邮箱地址')
      }
      if (!normalizedStudentId) {
        throw new Error('请先输入学号再获取邮箱验证码')
      }

      await requestJson<void>('/auth/email-code', {
        method: 'POST',
        body: JSON.stringify({
          email: normalizedEmail,
          studentId: normalizedStudentId
        })
      })
      return ''
    },

    async register(form: AuthFormInput): Promise<PublicUser> {
      const registeredUser = await requestJson<any>('/auth/register', {
        method: 'POST',
        body: JSON.stringify({
          email: normalizeEmail(form.email ?? ''),
          verificationCode: form.verificationCode?.trim() ?? '',
          studentId: form.studentId.trim(),
          password: form.password,
          nickname: form.nickname?.trim() || undefined,
          avatarUrl: form.avatarUrl?.trim() || undefined
        })
      })

      const profile = mapUserSummary(registeredUser)

      try {
        return await this.login({ studentId: form.studentId, password: form.password })
      } catch (error) {
        this.currentUserId = ''
        this.currentProfile = null
        this.token = ''
        throw error instanceof Error
          ? error
          : new Error(`注册成功，但账号 ${profile.studentId} 自动登录失败，请手动登录后继续`)
      }
    },

    async updateProfile(form: ProfilePatchInput): Promise<PublicUser> {
      if (!this.currentUserId) {
        throw new Error('请先登录后再编辑资料')
      }

      const profile = await requestJson<any>('/users/me', {
        method: 'PUT',
        body: JSON.stringify({
          nickname: form.nickname.trim(),
          avatarUrl: form.avatarUrl.trim()
        })
      }, this.token)

      const mapped = mapUserSummary(profile)
      this.currentProfile = mapped
      await this.fetchProfile()
      return mapped
    },

    async createDemand(form: DemandFormInput): Promise<DemandRecord> {
      if (!this.currentUserId) {
        throw new Error('请先登录后再发布需求')
      }

      const demand = await requestJson<any>('/demands', {
        method: 'POST',
        body: JSON.stringify({
          title: form.title.trim(),
          description: form.description.trim(),
          category: form.category || 'OTHER',
          campusZone: form.campusZone,
          location: form.location.trim() || '校园内',
          startTime: form.startTime || undefined,
          endTime: form.endTime || undefined,
          reward: Number(form.reward || 0),
          tags: form.tags
            .split(/[，,]/)
            .map((tag) => tag.trim())
            .filter(Boolean),
          anonymous: Boolean(form.anonymous)
        })
      }, this.token)

      const mapped = mapDemandRecord(demand)
      await this.fetchDemands()
      await this.fetchNotifications()
      return mapped
    },

    async approveDemand(demandId: string, approved: boolean): Promise<DemandRecord> {
      const demand = await requestJson<any>(`/admin/demands/${encodeURIComponent(demandId)}/review`, {
        method: 'POST',
        body: JSON.stringify({
          action: approved ? 'approve' : 'reject'
        })
      }, this.token)

      const mapped = mapDemandRecord(demand)
      await this.fetchAdminDashboard()
      await this.fetchAdminPendingDemands()
      await this.fetchDemands()
      await this.fetchNotifications()
      return mapped
    },

    async banUser(userId: string, reason = ''): Promise<PublicUser> {
      const payload = await requestJson<any>(`/admin/users/${encodeURIComponent(userId)}/ban`, {
        method: 'POST',
        body: JSON.stringify(reason ? { reason: reason.trim() } : {})
      }, this.token)

      const mapped = mapUserSummary(payload)
      await this.fetchAdminUsers()
      await this.fetchAdminDashboard()
      return mapped
    },

    async unbanUser(userId: string): Promise<PublicUser> {
      const payload = await requestJson<any>(`/admin/users/${encodeURIComponent(userId)}/unban`, {
        method: 'POST'
      }, this.token)

      const mapped = mapUserSummary(payload)
      await this.fetchAdminUsers()
      await this.fetchAdminDashboard()
      return mapped
    },

    async acceptDemand(demandId: string, note = ''): Promise<OrderRecord> {
      const order = await requestJson<any>(`/demands/${encodeURIComponent(demandId)}/accept`, {
        method: 'POST',
        body: JSON.stringify({ note: note.trim() })
      }, this.token)

      const mapped = mapOrderRecord(order)
      await this.fetchDemands()
      await this.fetchOrders()
      await this.fetchNotifications()
      return mapped
    },

    async startOrder(orderId: string): Promise<OrderRecord> {
      const order = await requestJson<any>(`/orders/${encodeURIComponent(orderId)}`, {
        method: 'PUT',
        body: JSON.stringify({ targetStatus: 'IN_PROGRESS' })
      }, this.token)

      const mapped = mapOrderRecord(order)
      await this.fetchDemands()
      await this.fetchOrders()
      await this.fetchNotifications()
      return mapped
    },

    async completeOrder(orderId: string): Promise<OrderRecord> {
      const order = await requestJson<any>(`/orders/${encodeURIComponent(orderId)}`, {
        method: 'PUT',
        body: JSON.stringify({ targetStatus: 'COMPLETED', proofImageCount: 1 })
      }, this.token)

      const mapped = mapOrderRecord(order)
      await this.fetchDemands()
      await this.fetchOrders()
      await this.fetchNotifications()
      return mapped
    },

    async cancelOrder(orderId: string): Promise<OrderRecord> {
      const order = await requestJson<any>(`/orders/${encodeURIComponent(orderId)}`, {
        method: 'PUT',
        body: JSON.stringify({ targetStatus: 'CANCELLED' })
      }, this.token)

      const mapped = mapOrderRecord(order)
      await this.fetchDemands()
      await this.fetchOrders()
      await this.fetchNotifications()
      return mapped
    },

    async submitReview(orderId: string, rating: number, comment: string): Promise<ReviewRecord> {
      const review = await requestJson<any>(`/orders/${encodeURIComponent(orderId)}/reviews`, {
        method: 'POST',
        body: JSON.stringify({ rating, comment: comment.trim() })
      }, this.token)

      const mapped: ReviewRecord = {
        id: String(review.id ?? nextId('r')),
        orderId: String(review.orderId ?? orderId),
        reviewerId: String(review.author?.id ?? this.currentUserId),
        reviewerName: String(review.author?.nickname ?? this.currentUser?.nickname ?? '匿名'),
        targetId: String(review.targetId ?? ''),
        targetName: String(review.targetName ?? review.author?.nickname ?? ''),
        rating: Number(review.rating ?? rating),
        comment: String(review.comment ?? comment.trim()),
        createdAt: String(review.createdAt ?? now())
      }

      this.reviews.unshift(mapped)
      await this.fetchOrders()
      await this.fetchNotifications()
      return mapped
    },

    markNotificationRead(notificationId: string): void {
      const notification = this.notifications.find((item) => item.id === notificationId)
      if (notification) {
        notification.isRead = true
      }
    },

    markAllNotificationsRead(): void {
      this.notifications
        .filter((notification) => notification.receiverId === this.currentUserId)
        .forEach((notification) => {
          notification.isRead = true
        })
    },

    async fetchDemands(): Promise<void> {
      try {
        const payload = await requestJson<any>('/demands?page=1&size=100', {}, this.token)
        const items = Array.isArray(payload?.items) ? payload.items : Array.isArray(payload) ? payload : payload?.data?.items ?? []
        this.demands = items.map((item: any) => mapDemandRecord(item))
      } catch {
        this.demands = []
      }
    },

    async fetchOrders(): Promise<void> {
      try {
        const payload = await requestJson<any>('/orders?page=1&size=100', {}, this.token)
        const items = Array.isArray(payload?.items) ? payload.items : Array.isArray(payload) ? payload : payload?.data?.items ?? []
        this.orders = items.map((item: any) => mapOrderRecord(item))
      } catch {
        this.orders = []
      }
    },

    async fetchNotifications(): Promise<void> {
      try {
        const payload = await requestJson<any>('/notifications?page=1&size=100', {}, this.token)
        const items = Array.isArray(payload?.items) ? payload.items : Array.isArray(payload) ? payload : payload?.data?.items ?? []
        this.notifications = items.map((item: any) => mapNotificationRecord(item, this.currentUserId))
      } catch {
        this.notifications = []
      }
    },

    async fetchProfile(): Promise<void> {
      try {
        const payload = await requestJson<any>('/users/me', {}, this.token)
        const profile = mapUserSummary(payload)
        this.currentProfile = profile
        this.currentUserId = profile.id || this.currentUserId
      } catch {
        // keep demo profile
      }
    },

    async fetchAdminUsers(query = ''): Promise<void> {
      try {
        const params = new URLSearchParams({ page: '1', size: '100' })
        if (query.trim()) {
          params.set('q', query.trim())
        }
        const payload = await requestJson<any>(`/admin/users?${params.toString()}`, {}, this.token)
        const items = Array.isArray(payload?.items) ? payload.items : Array.isArray(payload) ? payload : payload?.data?.items ?? []
        this.adminUsers = items.map((item: any) => mapUserSummary(item))
      } catch {
        this.adminUsers = []
      }
    },

    async fetchAdminPendingDemands(query = '', category = ''): Promise<void> {
      try {
        const params = new URLSearchParams({ page: '1', size: '100' })
        if (query.trim()) {
          params.set('q', query.trim())
        }
        if (category.trim()) {
          params.set('category', category.trim())
        }
        const payload = await requestJson<any>(`/admin/demands/pending?${params.toString()}`, {}, this.token)
        const items = Array.isArray(payload?.items) ? payload.items : Array.isArray(payload) ? payload : payload?.data?.items ?? []
        this.adminPendingDemands = items.map((item: any) => mapDemandRecord(item))
      } catch {
        this.adminPendingDemands = []
      }
    },

    async fetchAdminDashboard(): Promise<void> {
      try {
        const payload = await requestJson<any>('/admin/dashboard', {}, this.token)
        this.adminDashboard = {
          dailyActiveUsers: Number(payload?.dailyActiveUsers ?? 0),
          totalUsers: Number(payload?.totalUsers ?? 0),
          totalDemands: Number(payload?.totalDemands ?? 0),
          pendingReviewDemands: Number(payload?.pendingReviewDemands ?? 0),
          totalOrders: Number(payload?.totalOrders ?? 0),
          completedOrders: Number(payload?.completedOrders ?? 0),
          categoryDistribution: Array.isArray(payload?.categoryDistribution)
            ? payload.categoryDistribution.map((item: any) => ({
                category: String(item.category ?? ''),
                total: Number(item.total ?? 0)
              }))
            : []
        }
      } catch {
        this.adminDashboard = null
      }
    },

    async fetchRecommendations(page = 1, size = 20): Promise<DemandRecord[]> {
      if (!this.currentUserId) {
        return []
      }

      try {
        const payload = await requestJson<any>(`/recommendations?page=${page}&size=${size}`, {}, this.token)
        const items = Array.isArray(payload?.items) ? payload.items : Array.isArray(payload?.data?.items) ? payload.data.items : []

        return items.map((item: any) => mapDemandRecord(item.demand ?? item))
      } catch {
        return []
      }
    }
  }
})

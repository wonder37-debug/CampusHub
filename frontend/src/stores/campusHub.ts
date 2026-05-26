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
import { formatOrderStatus } from '@/utils/format'

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

// translateFieldName is defined in utils/errorHandler and intentionally not duplicated here

import { translateApiError } from '@/utils/errorHandler'

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
    const message = translateApiError(payload)
    const err = new Error(message) as Error & { status?: number }
    err.status = response.status
    throw err
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
    balance: Number(raw.balance ?? 0),
    frozenBalance: Number(raw.frozenBalance ?? 0),
    role: String(raw.role ?? 'USER') as AccountRecord['role'],
    status: String(raw.status ?? 'ACTIVE') as AccountRecord['status']
  }
}

function mapDemandRecord(raw: any): DemandRecord {
  const publisherDisplayName = raw.publisherDisplayName ?? raw.publisherName ?? raw.creator?.nickname ?? '匿名'
  const publisher = raw.publisher ? mapUserSummary(raw.publisher) : null
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
    publisher,
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
    demandLocation: String(demand.location ?? raw.location ?? ''),
    demandStartTime: String(demand.startTime ?? raw.startTime ?? ''),
    demandEndTime: String(demand.endTime ?? raw.endTime ?? ''),
    demandCategory: String(demand.category ?? raw.category ?? ''),
    demandCampusZone: String(demand.campusZone ?? raw.campusZone ?? ''),
    demandReward: Number(demand.reward ?? raw.reward ?? 0),
    requesterId: String(requester.id ?? raw.publisherId ?? raw.requesterId ?? demand.publisherId ?? ''),
    requesterName: String(requester.nickname ?? raw.publisherDisplayName ?? raw.requesterName ?? demand.publisherDisplayName ?? ''),
    requesterAvatar: String(requester.avatarUrl ?? raw.requesterAvatar ?? raw.publisherAvatar ?? buildAvatar(String(requester.nickname ?? raw.publisherDisplayName ?? ''))),
    requesterCreditScore: Number(requester.creditScore ?? raw.requesterCreditScore ?? 0),
    serviceProviderId: String(provider.id ?? raw.accepterId ?? raw.serviceProviderId ?? ''),
    serviceProviderName: String(provider.nickname ?? raw.accepterName ?? raw.serviceProviderName ?? ''),
    serviceProviderAvatar: String(provider.avatarUrl ?? raw.serviceProviderAvatar ?? buildAvatar(String(provider.nickname ?? raw.accepterName ?? ''))),
    serviceProviderCreditScore: Number(provider.creditScore ?? raw.serviceProviderCreditScore ?? 0),
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
          label: String(entry.note ?? formatOrderStatus(String(entry.toStatus ?? 'ACCEPTED') as OrderStatus))
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

function mapReviewRecord(raw: any): ReviewRecord {
  const author = raw.author ?? {}
  return {
    id: String(raw.id ?? nextId('r')),
    orderId: String(raw.orderId ?? ''),
    reviewerId: String(author.id ?? raw.authorId ?? ''),
    reviewerName: String(author.nickname ?? raw.reviewerName ?? '匿名'),
    targetId: String(raw.targetId ?? ''),
    targetName: String(raw.targetName ?? ''),
    rating: Number(raw.rating ?? 0),
    comment: String(raw.comment ?? ''),
    createdAt: String(raw.createdAt ?? now())
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
      await this.fetchCurrentUserReviews()
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
      await this.fetchCurrentUserReviews()
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

    async changeUserRole(userId: string, role: string): Promise<PublicUser> {
      // Try primary endpoint first, then fall back to promote/demote endpoints for compatibility
      const id = encodeURIComponent(userId)
      try {
        const payload = await requestJson<any>(`/admin/users/${id}/role`, {
          method: 'PUT',
          body: JSON.stringify({ role })
        }, this.token)
        const mapped = mapUserSummary(payload)
        await this.fetchAdminUsers()
        await this.fetchAdminDashboard()
        return mapped
      } catch (err) {
        // Try promote/demote style endpoints as fallback
        try {
          if (role === 'ADMIN') {
            const payload = await requestJson<any>(`/admin/users/${id}/promote`, { method: 'POST' }, this.token)
            const mapped = mapUserSummary(payload)
            await this.fetchAdminUsers()
            await this.fetchAdminDashboard()
            return mapped
          }

          if (role === 'USER') {
            const payload = await requestJson<any>(`/admin/users/${id}/demote`, { method: 'POST' }, this.token)
            const mapped = mapUserSummary(payload)
            await this.fetchAdminUsers()
            await this.fetchAdminDashboard()
            return mapped
          }
        } catch (err2) {
          // Log both errors for debugging, then rethrow the second (or first) error
          // eslint-disable-next-line no-console
          console.error('changeUserRole primary error:', err)
          // eslint-disable-next-line no-console
          console.error('changeUserRole fallback error:', err2)
          throw err2 instanceof Error ? err2 : err
        }
        // If role is neither ADMIN nor USER, rethrow original
        throw err
      }
    },

    async acceptDemand(demandId: string, note = ''): Promise<OrderRecord> {
      try {
        const order = await requestJson<any>(`/demands/${encodeURIComponent(demandId)}/accept`, {
          method: 'POST',
          body: JSON.stringify({ note: note.trim() })
        }, this.token)

        const mapped = mapOrderRecord(order)
        await this.fetchDemands()
        await this.fetchOrders()
        await this.fetchNotifications()
        return mapped
      } catch (err) {
        const e = err as Error & { status?: number }
        if (e.status === 409) {
          throw new Error('该需求已过期，无法接单')
        }
        throw err
      }
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

      const mapped: ReviewRecord = mapReviewRecord(review)

      this.reviews.unshift(mapped)
      await this.fetchOrders()
      await this.fetchNotifications()
      // 后端可能已经返回更新后的信用分，优先使用；否则主动刷新用户信息
      try {
        await this.fetchProfile()
      } catch {
        // ignore
      }
      return mapped
    },

    async fetchUserReviews(userId: string): Promise<void> {
      if (!userId || !this.token) {
        this.reviews = []
        return
      }

      try {
        const payload = await requestJson<any>(`/users/${encodeURIComponent(userId)}/reviews?page=1&size=100`, {}, this.token)
        const items = Array.isArray(payload?.items) ? payload.items : Array.isArray(payload) ? payload : payload?.data?.items ?? []
        const merged = new Map<string, ReviewRecord>()
        this.reviews.forEach((review) => merged.set(review.id, review))
        items.map((item: any) => mapReviewRecord(item)).forEach((review: ReviewRecord) => merged.set(review.id, review))
        this.reviews = Array.from(merged.values()).sort((left, right) => right.createdAt.localeCompare(left.createdAt))
      } catch {
        this.reviews = []
      }
    },

    async fetchCurrentUserReviews(): Promise<void> {
      if (!this.currentUserId) {
        this.reviews = []
        return
      }

      await this.fetchUserReviews(this.currentUserId)
    },

    async markNotificationRead(notificationId: string): Promise<void> {
      if (!notificationId || !this.token) {
        return
      }

      await requestJson<void>(`/notifications/${notificationId}/read`, {
        method: 'POST'
      }, this.token)

      const notification = this.notifications.find((item) => item.id === notificationId)
      if (notification) {
        notification.isRead = true
      }
    },

    async markAllNotificationsRead(): Promise<void> {
      const unreadNotifications = this.currentUserNotifications.filter((notification) => !notification.isRead)

      for (const notification of unreadNotifications) {
        try {
          await this.markNotificationRead(notification.id)
        } catch {
          continue
        }
      }

      await this.fetchNotifications()
    },

    /**
     * Fetch demands from backend. If status is provided, include it as a query param.
     */
    async fetchDemands(status?: string): Promise<void> {
      try {
        const params = new URLSearchParams({ page: '1', size: '100' })
        if (status) params.set('status', status)
        const payload = await requestJson<any>(`/demands?${params.toString()}`, {}, this.token)
        const items = Array.isArray(payload?.items) ? payload.items : Array.isArray(payload) ? payload : payload?.data?.items ?? []
        this.demands = items.map((item: any) => mapDemandRecord(item))
      } catch {
        this.demands = []
      }
    },

    // (recent demand-resolution helpers reverted)

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
        this.currentProfile = null
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
        // 兼容不同后端字段命名，优先使用常见字段
        this.adminDashboard = {
          dailyActiveUsers: Number(payload?.dailyActiveUsers ?? payload?.dau ?? payload?.daily_active_users ?? 0),
          totalUsers: Number(payload?.totalUsers ?? payload?.usersCount ?? 0),
          totalDemands: Number(payload?.totalDemands ?? payload?.demandsCount ?? payload?.total_demands ?? 0),
          pendingReviewDemands: Number(payload?.pendingReviewDemands ?? payload?.pendingReview ?? 0),
          totalOrders: Number(payload?.totalOrders ?? payload?.ordersCount ?? 0),
          completedOrders: Number(payload?.completedOrders ?? payload?.completed_orders ?? 0),
          categoryDistribution: Array.isArray(payload?.categoryDistribution) || Array.isArray(payload?.category_distribution)
            ? (payload?.categoryDistribution ?? payload?.category_distribution).map((item: any) => ({
                category: String(item.category ?? ''),
                total: Number(item.total ?? item.count ?? 0)
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

        // 按返回的 rank 排序，rank 值越小优先级越高
        items.sort((a: any, b: any) => (Number(a.rank ?? a.score ?? 0) - Number(b.rank ?? b.score ?? 0)))

        return items.map((item: any) => mapDemandRecord(item.demand ?? item))
      } catch {
        return []
      }
    }

    ,
    async fetchBalance(): Promise<number> {
      try {
        const payload = await requestJson<any>('/user/balance', {}, this.token)
        return Number(payload?.balance ?? payload?.available ?? payload ?? 0)
      } catch {
        return 0
      }
    }
  }
})

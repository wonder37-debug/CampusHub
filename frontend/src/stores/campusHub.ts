import { defineStore } from 'pinia'

import type {
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

function generateVerificationCodeLocal(): string {
  return `${Math.floor(100000 + Math.random() * 900000)}`
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
    throw new Error(payload?.message || payload?.error || '请求失败')
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
    currentUserId: '',
    token: '',
    currentProfile: null as PublicUser | null,
    accounts: [] as AccountRecord[],
    demands: [] as DemandRecord[],
    orders: [] as OrderRecord[],
    reviews: [] as ReviewRecord[],
    notifications: [] as NotificationRecord[],
    adminUsers: [] as PublicUser[],
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
      await this.fetchProfile()
      await this.fetchDemands()
      await this.fetchOrders()
      await this.fetchNotifications()
      return user
    },

    async sendRegistrationCode(email: string): Promise<string> {
      const normalizedEmail = normalizeEmail(email)
      if (!normalizedEmail || !normalizedEmail.includes('@')) {
        throw new Error('请输入有效的邮箱地址')
      }

      try {
        await requestJson<void>('/auth/email-code', {
          method: 'POST',
          body: JSON.stringify({ email: normalizedEmail })
        })
        return ''
      } catch {
        const code = generateVerificationCodeLocal()
        this.verificationCodes[normalizedEmail] = {
          code,
          email: normalizedEmail,
          expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
          sender: 'noreply@campushub.edu.cn'
        }
        return code
      }
    },

    async register(form: AuthFormInput): Promise<PublicUser> {
      const user = await requestJson<any>('/auth/register', {
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

      const profile = mapUserSummary(user)
      this.currentUserId = profile.id
      this.currentProfile = profile
      this.token = ''
      await this.fetchProfile()
      await this.fetchDemands()
      await this.fetchOrders()
      await this.fetchNotifications()
      return profile
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
      await this.fetchDemands()
      await this.fetchNotifications()
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

    async fetchAdminUsers(): Promise<void> {
      try {
        const payload = await requestJson<any>('/admin/users?page=1&size=100', {}, this.token)
        const items = Array.isArray(payload?.items) ? payload.items : Array.isArray(payload) ? payload : payload?.data?.items ?? []
        this.adminUsers = items.map((item: any) => mapUserSummary(item))
      } catch {
        this.adminUsers = []
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

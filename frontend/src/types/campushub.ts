export const DEMAND_CATEGORIES = ['取快递', '学习辅导', '二手交易', '活动组队', '其他'] as const
export const DEMAND_SORT_MODES = ['time', 'distance', 'reward', 'recommend'] as const

export type DemandCategory = (typeof DEMAND_CATEGORIES)[number]
export type DemandSortMode = (typeof DEMAND_SORT_MODES)[number]
export type UserRole = 'student' | 'admin'
export type DemandApprovalStatus = '待审核' | '已通过' | '已拒绝'
export type DemandStatus = '开放中' | '已接单' | '进行中' | '已完成' | '已关闭'
export type OrderStatus = '已接单' | '进行中' | '已完成' | '已取消'
export type NotificationType = '订单' | '需求' | '评价' | '系统'

export interface PublicUser {
  id: string
  studentId: string
  email: string
  nickname: string
  college: string
  phone: string
  creditScore: number
  role: UserRole
  avatarUrl: string
}

export interface AccountRecord extends PublicUser {
  password: string
}

export interface DemandRecord {
  id: string
  title: string
  description: string
  category: DemandCategory
  location: string
  startTime: string
  endTime: string
  reward: number
  status: DemandStatus
  approvalStatus: DemandApprovalStatus
  publisherId: string
  publisherName: string
  publisherAvatar: string
  tags: string[]
  createdAt: string
  distanceKm: number
}

export interface OrderTimelineEntry {
  at: string
  label: string
}

export interface OrderRecord {
  id: string
  demandId: string
  demandTitle: string
  requesterId: string
  requesterName: string
  requesterAvatar: string
  serviceProviderId: string
  serviceProviderName: string
  serviceProviderAvatar: string
  status: OrderStatus
  note: string
  createdAt: string
  completedAt: string
  timeline: OrderTimelineEntry[]
}

export interface ReviewRecord {
  id: string
  orderId: string
  reviewerId: string
  reviewerName: string
  targetId: string
  targetName: string
  rating: number
  comment: string
  createdAt: string
}

export interface NotificationRecord {
  id: string
  receiverId: string
  type: NotificationType
  content: string
  isRead: boolean
  createdAt: string
  relatedId: string
}

export interface AuthFormInput {
  studentId: string
  password: string
  email?: string
  verificationCode?: string
  nickname?: string
  college?: string
  phone?: string
  avatarUrl?: string
}

export interface EmailVerificationRecord {
  code: string
  email: string
  expiresAt: string
  sender: string
}

export interface DemandFormInput {
  title: string
  description: string
  category: DemandCategory | ''
  location: string
  startTime: string
  endTime: string
  reward: string
  tags: string
}

export interface ProfilePatchInput {
  nickname: string
  college: string
  phone: string
  avatarUrl: string
}

export interface DashboardSummary {
  openDemands: number
  activeOrders: number
  unreadNotifications: number
  pendingApprovals: number
  averageCredit: number
}

export interface CategoryStat {
  category: DemandCategory
  total: number
}

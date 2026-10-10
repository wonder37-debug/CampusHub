export const DEMAND_CATEGORY_OPTIONS = ['EXPRESS', 'ERRAND', 'STUDY_TUTORING', 'SECOND_HAND', 'TEAM_UP', 'HELP', 'OTHER'] as const
export const CAMPUS_ZONE_OPTIONS = ['GULOU', 'XIANLIN', 'SUZHOU'] as const
export const DEMAND_SORT_MODES = ['time', 'reward', 'recommend'] as const
export const USER_ROLE_OPTIONS = ['USER', 'ADMIN'] as const
export const USER_STATUS_OPTIONS = ['ACTIVE', 'BANNED'] as const
export const DEMAND_STATUS_OPTIONS = ['PENDING', 'REVIEWING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'EXPIRED'] as const
export const ORDER_STATUS_OPTIONS = ['ACCEPTED', 'IN_PROGRESS', 'IN_ARBITRATION', 'COMPLETED', 'CANCELLED'] as const
export const INTERACTION_MODE_OPTIONS = ['DIRECT_ACCEPT', 'SELECT_ONE', 'SELECT_MANY', 'HELP'] as const
export const RESPONSE_STATUS_OPTIONS = ['PENDING', 'SELECTED', 'REJECTED', 'WITHDRAWN'] as const
export const NOTIFICATION_TYPE_OPTIONS = [
  'ORDER_ACCEPTED',
  'STATUS_CHANGED',
  'REVIEW_RECEIVED',
  'REVIEW_REQUEST',
  'DEMAND_REJECTED',
  'DEMAND_APPROVED',
  'PENDING_REVIEW',
  'ORDER_ARBITRATION_REQUESTED',
  'ORDER_ARBITRATION_RESOLVED',
  'RESPONSE_REVIEW_RECEIVED',
  'DEMAND_RESPONSE_RECEIVED'
] as const

export type DemandCategory = (typeof DEMAND_CATEGORY_OPTIONS)[number]
export type CampusZone = (typeof CAMPUS_ZONE_OPTIONS)[number]
export type DemandSortMode = (typeof DEMAND_SORT_MODES)[number]
export type UserRole = (typeof USER_ROLE_OPTIONS)[number]
export type UserStatus = (typeof USER_STATUS_OPTIONS)[number]
export type DemandStatus = (typeof DEMAND_STATUS_OPTIONS)[number]
export type OrderStatus = (typeof ORDER_STATUS_OPTIONS)[number]
export type InteractionMode = (typeof INTERACTION_MODE_OPTIONS)[number]
export type ResponseStatus = (typeof RESPONSE_STATUS_OPTIONS)[number]
export type NotificationType = (typeof NOTIFICATION_TYPE_OPTIONS)[number]

export interface PublicUser {
  id: string
  // 以下字段仅在本人/管理员接口（/auth/login、/users/me、/admin/users 等）返回；
  // 公开接口（Demand/Order/Review 中的 publisher/requester/provider/author）不返回，使用时请做空值兜底
  studentId?: string
  email?: string
  balance?: number
  frozenBalance?: number
  nickname: string
  phone?: string
  creditScore: number
  role: UserRole
  status: UserStatus
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
  campusZone: CampusZone
  location: string
  startTime: string
  endTime: string
  reward: number
  interactionMode: InteractionMode
  targetParticipantCount?: number | null
  selectedParticipantCount?: number | null
  status: DemandStatus
  anonymous: boolean
  anonymousCode: string | null
  publisherId: string
  publisherName: string
  publisherAvatar: string
  publisher?: PublicUser | null
  tags: string[]
  createdAt: string
  updatedAt: string
  distanceKm: number
  canAccept?: boolean
  acceptDisabledReason?: string | null
  acceptStatusHint?: string | null
  canStartExecution?: boolean
  canViewAcceptNote?: boolean
  canSubmitAcceptNote?: boolean
  publisherStudentIdMasked?: string
  publisherIdentityVisible?: boolean
  reviewReason?: string | null
  images?: string[]
  contactInfo?: string | null
}

export interface OrderTimelineEntry {
  at: string
  label: string
  operatorId?: string
}

export interface OrderRecord {
  id: string
  demandId: string
  demandTitle: string
  demandDescription?: string
  demandLocation?: string
  demandStartTime?: string
  demandEndTime?: string
  demandCategory?: string
  demandCampusZone?: string
  demandReward?: number
  requesterId: string
  requesterName: string
  requesterAvatar: string
  requesterCreditScore: number
  serviceProviderId: string
  serviceProviderName: string
  serviceProviderAvatar: string
  serviceProviderCreditScore: number
  serviceProviderStudentId?: string
  status: OrderStatus
  note: string | null
  proofSubmitted: boolean
  proofImageCount: number
  proofImageUrls?: string[]
  createdAt: string
  updatedAt: string
  completedAt: string
  timeline: OrderTimelineEntry[]
  reviews?: ReviewRecord[]
  currentUserReviewed?: boolean
  pendingReviewTarget?: string | null
  completionHint?: string | null
  demandImages?: string[]
  demandContactInfo?: string | null
  arbitrationResult?: string | null
  // 需求是否匿名发布（映射自后端 demand.anonymous），用于订单详情脱敏判断
  anonymous?: boolean
}

export interface RecommendationRecord {
  rank: number
  score: number
  reasonTags: string[]
  demand: DemandRecord
}

export interface DemandResponseRecord {
  id: string
  demandId: string
  authorId: string
  authorName: string
  content: string
  status: ResponseStatus
  createdAt: string
  updatedAt: string
}

export interface ReviewRecord {
  id: string
  orderId: string | null
  responseId: string | null
  demandId: string | null
  demandTitle: string | null
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
  title?: string
  content: string
  isRead: boolean
  createdAt: string
  relatedId: string
  relatedName?: string
  targetType?: string
  targetId?: string
  targetTitle?: string
  actionHint?: string
}

export interface AuthFormInput {
  studentId: string
  password: string
  email?: string
  verificationCode?: string
  nickname?: string
  avatarUrl?: string
}

export interface EmailVerificationRecord {
  code: string
  email: string
  studentId: string
  expiresAt: string
  sender: string
}

export interface DemandFormInput {
  title: string
  description: string
  category: DemandCategory | ''
  campusZone: CampusZone | ''
  location: string
  startTime: string
  endTime: string
  reward: string
  tags: string
  images?: string[]
  contactInfo?: string
  anonymous: boolean
  targetParticipantCount?: number | null
  interactionMode?: string | null
}

export interface ProfilePatchInput {
  nickname: string
  avatarUrl: string
}

export interface DashboardSummary {
  openDemands: number
  activeOrders: number
  unreadNotifications: number
  pendingApprovals: number
  averageCredit: number
}

export interface AdminCategoryStat {
  category: string
  total: number
}

export interface AdminDashboardSummary {
  dailyActiveUsers: number
  totalUsers: number
  totalDemands: number
  pendingReviewDemands: number
  totalOrders: number
  completedOrders: number
  categoryDistribution: AdminCategoryStat[]
}

export interface CategoryStat {
  category: DemandCategory
  total: number
}

export interface LabelOption<T extends string = string> {
  value: T
  label: string
}

/**
 * AI 生成的需求草稿，仅作为前端回填的中间结构，不直接提交。
 * 与后端 com.campushub.backend.ai.dto.DemandDraft 对应。
 */
export interface AiDemandDraft {
  title: string | null
  description: string | null
  category: string | null
  campusZone: string | null
  location: string | null
  startTime: string | null
  endTime: string | null
  reward: number | null
  tags: string[]
  interactionMode: string | null
  targetParticipantCount: number | null
  contactInfo: string | null
  anonymous: boolean | null
  missingFields: string[]
}

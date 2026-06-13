export const DEMAND_CATEGORY_OPTIONS = ['EXPRESS', 'ERRAND', 'STUDY_TUTORING', 'SECOND_HAND', 'TEAM_UP', 'OTHER'] as const
export const CAMPUS_ZONE_OPTIONS = ['GULOU', 'XIANLIN', 'SUZHOU'] as const
export const DEMAND_SORT_MODES = ['time', 'reward', 'recommend'] as const
export const USER_ROLE_OPTIONS = ['USER', 'ADMIN'] as const
export const USER_STATUS_OPTIONS = ['ACTIVE', 'BANNED'] as const
export const DEMAND_STATUS_OPTIONS = ['PENDING', 'REVIEWING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'EXPIRED'] as const
export const ORDER_STATUS_OPTIONS = ['ACCEPTED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'] as const
export const NOTIFICATION_TYPE_OPTIONS = ['ORDER_ACCEPTED', 'STATUS_CHANGED', 'REVIEW_RECEIVED', 'REVIEW_REQUEST', 'DEMAND_REJECTED', 'DEMAND_APPROVED'] as const

export type DemandCategory = (typeof DEMAND_CATEGORY_OPTIONS)[number]
export type CampusZone = (typeof CAMPUS_ZONE_OPTIONS)[number]
export type DemandSortMode = (typeof DEMAND_SORT_MODES)[number]
export type UserRole = (typeof USER_ROLE_OPTIONS)[number]
export type UserStatus = (typeof USER_STATUS_OPTIONS)[number]
export type DemandStatus = (typeof DEMAND_STATUS_OPTIONS)[number]
export type OrderStatus = (typeof ORDER_STATUS_OPTIONS)[number]
export type NotificationType = (typeof NOTIFICATION_TYPE_OPTIONS)[number]

export interface PublicUser {
  id: string
  studentId: string
  email: string
  nickname: string
  phone?: string
  creditScore: number
  balance: number
  frozenBalance: number
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
  canStartExecution?: boolean
  canViewAcceptNote?: boolean
  canSubmitAcceptNote?: boolean
  publisherStudentIdMasked?: string
  publisherIdentityVisible?: boolean
  reviewReason?: string | null
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
  status: OrderStatus
  note: string
  proofSubmitted: boolean
  proofImageCount: number
  createdAt: string
  updatedAt: string
  completedAt: string
  timeline: OrderTimelineEntry[]
  reviews?: ReviewRecord[]
  currentUserReviewed?: boolean
  pendingReviewTarget?: string | null
  completionHint?: string | null
}

export interface RecommendationRecord {
  rank: number
  score: number
  reasonTags: string[]
  demand: DemandRecord
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
  anonymous: boolean
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

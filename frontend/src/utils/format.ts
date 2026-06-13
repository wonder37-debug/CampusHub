import {
  CAMPUS_ZONE_OPTIONS,
  DEMAND_CATEGORY_OPTIONS,
  NOTIFICATION_TYPE_OPTIONS,
  ORDER_STATUS_OPTIONS,
  USER_ROLE_OPTIONS,
  USER_STATUS_OPTIONS,
  type CampusZone,
  type DemandCategory,
  type DemandStatus,
  type NotificationType,
  type OrderStatus,
  type UserRole,
  type UserStatus
} from '@/types/campushub'

export function formatDateTime(value: string): string {
  if (!value) {
    return '未填写'
  }

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }

  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date)
}

export function formatDate(value: string): string {
  if (!value) {
    return '未填写'
  }

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }

  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).format(date)
}

export function formatMoney(value: number): string {
  return `¥${Number(value || 0).toFixed(0)}`
}

export function formatScore(value: number): string {
  return `${Number(value || 0).toFixed(1)} 分`
}

export function statusToneClass(value: string): string {
  const normalized = value.toUpperCase()

  if (
    normalized.includes('PENDING') ||
    normalized.includes('REVIEWING') ||
    normalized.includes('IN_PROGRESS') ||
    normalized.includes('ACTIVE')
  ) {
    return 'is-warning'
  }

  if (normalized.includes('COMPLETED') || normalized.includes('APPROVED')) {
    return 'is-success'
  }

  if (normalized.includes('CANCELLED') || normalized.includes('BANNED') || normalized.includes('REJECT') || normalized.includes('EXPIRED')) {
    return 'is-danger'
  }

  return 'is-neutral'
}

export function formatDemandCategory(category: DemandCategory): string {
  switch (category) {
    case 'EXPRESS':
      return '跑腿代取'
    case 'ERRAND':
      return '委托代办'
    case 'STUDY_TUTORING':
      return '学习辅导'
    case 'SECOND_HAND':
      return '二手交易'
    case 'TEAM_UP':
      return '活动组队'
    default:
      return '其他'
  }
}

export function formatCampusZone(zone: CampusZone): string {
  switch (zone) {
    case 'GULOU':
      return '鼓楼校区'
    case 'XIANLIN':
      return '仙林校区'
    case 'SUZHOU':
      return '苏州校区'
  }
}

export function formatDemandStatus(status: DemandStatus): string {
  switch (status) {
    case 'PENDING':
      return '开放中'
    case 'REVIEWING':
      return '审核中'
    case 'IN_PROGRESS':
      return '进行中'
    case 'EXPIRED':
      return '已过期'
    case 'COMPLETED':
      return '已完成'
    case 'CANCELLED':
      return '已取消'
  }
}

export function formatOrderStatus(status: OrderStatus): string {
  switch (status) {
    case 'ACCEPTED':
      return '已接单'
    case 'IN_PROGRESS':
      return '进行中'
    case 'COMPLETED':
      return '已完成'
    case 'CANCELLED':
      return '已取消'
  }
}

export function formatAcceptDisabledReason(reason: string): string {
  const normalized = String(reason || '').trim().toUpperCase()

  const reasonMap: Record<string, string> = {
    LOGIN_REQUIRED: '请先登录后再接单',
    ADMIN_FORBIDDEN: '管理员不能接单',
    OWN_DEMAND: '不能接自己的需求',
    DEMAND_EXPIRED: '该需求已过期，无法接单',
    DEMAND_NOT_PENDING: '该需求当前不处于待接单状态',
    DEMAND_ALREADY_ACCEPTED: '该需求已被其他同学接单',
    DEMAND_ORDER_CLOSED: '该需求关联订单已关闭，无法接单'
  }

  return reasonMap[normalized] ?? reason
}

export function formatUserRole(role: UserRole): string {
  switch (role) {
    case 'ADMIN':
      return '管理员'
    case 'USER':
      return '学生'
  }
}

export function formatUserStatus(status: UserStatus): string {
  switch (status) {
    case 'ACTIVE':
      return '正常'
    case 'BANNED':
      return '已封禁'
  }
}

export function formatNotificationType(type: NotificationType): string {
  switch (type) {
    case 'ORDER_ACCEPTED':
      return '订单接单'
    case 'STATUS_CHANGED':
      return '状态变更'
    case 'REVIEW_RECEIVED':
      return '收到评价'
    case 'REVIEW_REQUEST':
      return '待审核'
    case 'DEMAND_REJECTED':
      return '需求驳回'
    case 'DEMAND_APPROVED':
      return '审核通过'
    default:
      return String(type)
  }
}

export function categoryOptions(): Array<{ value: DemandCategory; label: string }> {
  return DEMAND_CATEGORY_OPTIONS.map((value) => ({ value, label: formatDemandCategory(value) }))
}

export function campusZoneOptions(): Array<{ value: CampusZone; label: string }> {
  return CAMPUS_ZONE_OPTIONS.map((value) => ({ value, label: formatCampusZone(value) }))
}

export function roleOptions(): Array<{ value: UserRole; label: string }> {
  return USER_ROLE_OPTIONS.map((value) => ({ value, label: formatUserRole(value) }))
}

export function statusOptions(): Array<{ value: UserStatus; label: string }> {
  return USER_STATUS_OPTIONS.map((value) => ({ value, label: formatUserStatus(value) }))
}

export function orderStatusOptions(): Array<{ value: OrderStatus; label: string }> {
  return ORDER_STATUS_OPTIONS.map((value) => ({ value, label: formatOrderStatus(value) }))
}

export function notificationTypeOptions(): Array<{ value: NotificationType; label: string }> {
  return NOTIFICATION_TYPE_OPTIONS.map((value) => ({ value, label: formatNotificationType(value) }))
}

export function truncateText(value: string, length: number): string {
  if (value.length <= length) {
    return value
  }

  return `${value.slice(0, length)}…`
}

export function formatRelativeTime(value: string): string {
  if (!value) {
    return '刚刚'
  }

  const timestamp = new Date(value).getTime()
  if (Number.isNaN(timestamp)) {
    return value
  }

  const diffMinutes = Math.floor((Date.now() - timestamp) / 60000)
  if (diffMinutes < 1) {
    return '刚刚'
  }
  if (diffMinutes < 60) {
    return `${diffMinutes}分钟前`
  }

  const diffHours = Math.floor(diffMinutes / 60)
  if (diffHours < 24) {
    return `${diffHours}小时前`
  }

  const diffDays = Math.floor(diffHours / 24)
  return `${diffDays}天前`
}

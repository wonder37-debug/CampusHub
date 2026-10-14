import type { OrderRecord, DemandRecord, ResponseStatus } from '@/types/campushub'
import { formatOrderStatus, formatDemandStatus, formatResponseStatus } from '@/utils/format'

export type TodoCategory = 'order_provider' | 'order_requester' | 'demand_publisher' | 'arbitration'

export interface TodoItem {
  id: string
  title: string
  description: string
  statusLabel: string
  actionLabel: string
  route: string
  category: TodoCategory
}

export type NotificationCategory = 'interaction' | 'order_fulfillment' | 'system'

export function categorizeNotification(type: string): NotificationCategory {
  if (type === 'DEMAND_RESPONSE_RECEIVED' || type === 'DEMAND_RESPONSE_SELECTED' || type === 'DEMAND_RESPONSE_REJECTED'
      || type === 'DEMAND_ANSWER_ACCEPTED' || type === 'TEAM_UP_COMPLETED' || type === 'DEMAND_CLOSED'
      || type === 'REVIEW_RECEIVED' || type === 'RESPONSE_REVIEW_RECEIVED') {
    return 'interaction'
  }
  if (type === 'ORDER_ACCEPTED' || type === 'STATUS_CHANGED' || type === 'ORDER_ARBITRATION_REQUESTED' || type === 'ORDER_ARBITRATION_RESOLVED' || type === 'PENDING_REVIEW') {
    return 'order_fulfillment'
  }
  return 'system'
}

export function providerConfirmed(order: OrderRecord): boolean {
  return order.timeline.some(
    (t) => String(t.operatorId) === String(order.serviceProviderId)
      && (t.label.includes('接单方确认完成') || t.label.includes('已确认完成，等待需求方确认'))
  )
}

export function requesterConfirmed(order: OrderRecord): boolean {
  return order.timeline.some(
    (t) => String(t.operatorId) === String(order.requesterId)
      && (t.label.includes('需求方确认完成') || t.label.includes('已确认完成，等待接单方确认'))
  )
}

export function generateOrderTodos(
  orders: OrderRecord[],
  currentUserId: string
): TodoItem[] {
  const todos: TodoItem[] = []

  for (const order of orders) {
    const isProvider = order.serviceProviderId === currentUserId
    const isRequester = order.requesterId === currentUserId
    if (!isProvider && !isRequester) continue

    if (order.status === 'ACCEPTED' && isProvider) {
      todos.push({
        id: `order-start-${order.id}`,
        title: order.demandTitle,
        description: '你已接单，需要开始执行',
        statusLabel: formatOrderStatus(order.status),
        actionLabel: '开始执行',
        route: `/orders/${order.id}`,
        category: 'order_provider'
      })
    }

    if (order.status === 'IN_PROGRESS' && isProvider && !order.proofSubmitted && !providerConfirmed(order)) {
      todos.push({
        id: `order-proof-${order.id}`,
        title: order.demandTitle,
        description: '执行中，待上传完成凭证',
        statusLabel: formatOrderStatus(order.status),
        actionLabel: '上传凭证并完成',
        route: `/orders/${order.id}`,
        category: 'order_provider'
      })
    }

    if (order.status === 'IN_PROGRESS' && isRequester && order.proofSubmitted && !requesterConfirmed(order)) {
      todos.push({
        id: `order-confirm-${order.id}`,
        title: order.demandTitle,
        description: '接单方已提交完成凭证，等待你确认',
        statusLabel: formatOrderStatus(order.status),
        actionLabel: '确认完成',
        route: `/orders/${order.id}`,
        category: 'order_requester'
      })
    }

    if (order.status === 'IN_ARBITRATION' && order.canSubmitArbitrationResponse && (isProvider || isRequester)) {
      todos.push({
        id: `order-arbitration-${order.id}`,
        title: order.demandTitle,
        description: '订单正在仲裁中，等待你补充说明',
        statusLabel: formatOrderStatus(order.status),
        actionLabel: '补充仲裁说明',
        route: `/orders/${order.id}`,
        category: 'arbitration'
      })
    }
  }

  return todos
}

export function generateDemandPublisherTodos(
  demands: DemandRecord[],
  currentUserId: string,
  pendingResponseCounts: Map<string, number>
): TodoItem[] {
  const todos: TodoItem[] = []

  for (const demand of demands) {
    if (demand.publisherId !== currentUserId) continue
    if (demand.status !== 'PENDING') continue

    const pendingCount = pendingResponseCounts.get(demand.id) ?? 0
    if (pendingCount === 0) continue

    if (demand.interactionMode === 'SELECT_ONE') {
      todos.push({
        id: `demand-select-one-${demand.id}`,
        title: demand.title,
        description: `有 ${pendingCount} 个响应待选择`,
        statusLabel: formatDemandStatus(demand.status),
        actionLabel: '选择响应者',
        route: `/demands/${demand.id}`,
        category: 'demand_publisher'
      })
    }

    if (demand.interactionMode === 'SELECT_MANY') {
      const selected = demand.selectedParticipantCount ?? 0
      const target = demand.targetParticipantCount ?? 0
      todos.push({
        id: `demand-select-many-${demand.id}`,
        title: demand.title,
        description: `有 ${pendingCount} 个报名待选择（已选 ${selected}/${target}）`,
        statusLabel: formatDemandStatus(demand.status),
        actionLabel: '选择组队成员',
        route: `/demands/${demand.id}`,
        category: 'demand_publisher'
      })
    }

    if (demand.interactionMode === 'HELP') {
      todos.push({
        id: `demand-accept-answer-${demand.id}`,
        title: demand.title,
        description: `有 ${pendingCount} 个回答待采纳`,
        statusLabel: formatDemandStatus(demand.status),
        actionLabel: '采纳回答',
        route: `/demands/${demand.id}`,
        category: 'demand_publisher'
      })
    }
  }

  return todos
}

export interface PublishedDemandItem {
  demand: DemandRecord
  statusLabel: string
  hint: string
}

export function buildPublishedDemandItems(
  demands: DemandRecord[],
  currentUserId: string
): PublishedDemandItem[] {
  return demands
    .filter((d) => d.publisherId === currentUserId)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    .map((d) => {
      let hint = ''
      if (d.status === 'REVIEWING') {
        hint = d.reviewReason ? `审核未通过：${d.reviewReason}` : '等待管理员审核'
      } else if (d.status === 'PENDING' && d.interactionMode === 'SELECT_MANY') {
        const sel = d.selectedParticipantCount ?? 0
        const tgt = d.targetParticipantCount ?? 0
        hint = `组队进度：${sel}/${tgt}`
      } else if (d.status === 'PENDING') {
        hint = '开放响应中'
      } else if (d.status === 'COMPLETED') {
        hint = '已完成'
      } else if (d.status === 'CANCELLED') {
        hint = d.reviewReason ? `已取消：${d.reviewReason}` : '已取消'
      } else if (d.status === 'EXPIRED') {
        hint = '已过期'
      }
      return {
        demand: d,
        statusLabel: formatDemandStatus(d.status),
        hint
      }
    })
}

export interface ParticipatedResponseItem {
  response: import('@/types/campushub').MyDemandResponseRecord
  statusLabel: string
  hint: string
}

export function buildParticipatedResponseItems(
  responses: import('@/types/campushub').MyDemandResponseRecord[]
): ParticipatedResponseItem[] {
  return responses.map((r) => {
    let hint = ''
    const demandStatus = r.demandStatus
    if (r.status === 'PENDING') {
      if (demandStatus === 'PENDING') {
        hint = '等待发布者处理'
      } else if (demandStatus === 'COMPLETED') {
        hint = '需求已完成'
      } else if (demandStatus === 'CANCELLED' || demandStatus === 'EXPIRED') {
        hint = '需求已关闭'
      } else if (demandStatus === 'REVIEWING') {
        hint = '需求审核中'
      } else {
        hint = '等待发布者处理'
      }
    } else if (r.status === 'SELECTED') {
      if (demandStatus === 'CANCELLED' || demandStatus === 'EXPIRED') {
        hint = r.demandInteractionMode === 'SELECT_MANY'
          ? '需求已关闭，组队未达目标人数，奖励未结算'
          : '需求已关闭，后续不再处理'
      } else if (demandStatus === 'COMPLETED' && r.demandInteractionMode === 'SELECT_MANY') {
        hint = '组队已完成，奖励已结算'
      } else if (demandStatus === 'COMPLETED' && r.demandInteractionMode === 'HELP') {
        hint = '回答已被采纳'
      } else {
        hint = '已被选中'
      }
    } else if (r.status === 'REJECTED') {
      hint = '未被选中'
    } else if (r.status === 'WITHDRAWN') {
      hint = '已撤回'
    }
    return {
      response: r,
      statusLabel: formatResponseStatus(r.status as ResponseStatus),
      hint
    }
  })
}

export interface OrderListItem {
  order: OrderRecord
  role: 'requester' | 'provider'
  statusLabel: string
  hint: string
}

export function buildOrderListItems(
  orders: OrderRecord[],
  currentUserId: string,
  filter: 'all' | 'requester' | 'provider' = 'all'
): OrderListItem[] {
  return orders
    .filter((o) => {
      const isRequester = o.requesterId === currentUserId
      const isProvider = o.serviceProviderId === currentUserId
      if (!isRequester && !isProvider) return false
      if (filter === 'requester') return isRequester
      if (filter === 'provider') return isProvider
      return true
    })
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    .map((o) => {
      const role: 'requester' | 'provider' = o.requesterId === currentUserId ? 'requester' : 'provider'
      let hint = o.completionHint || ''
      if (o.status === 'IN_ARBITRATION' && !hint) {
        hint = '当前订单正在等待管理员仲裁处理。'
      }
      return {
        order: o,
        role,
        statusLabel: formatOrderStatus(o.status),
        hint
      }
    })
}

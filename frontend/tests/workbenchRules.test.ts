import { describe, it, expect } from 'vitest'
import {
  generateOrderTodos,
  generateDemandPublisherTodos,
  buildPublishedDemandItems,
  buildParticipatedResponseItems,
  buildOrderListItems,
  categorizeNotification,
  providerConfirmed,
  requesterConfirmed,
  type TodoItem
} from '@/utils/workbenchRules'
import type { OrderRecord, DemandRecord, MyDemandResponseRecord } from '@/types/campushub'

function buildOrder(overrides: Partial<OrderRecord> = {}): OrderRecord {
  return {
    id: 'o-1',
    demandId: 'd-1',
    demandTitle: '测试订单',
    requesterId: 'u-1',
    requesterName: '需求方',
    requesterAvatar: '',
    requesterCreditScore: 80,
    serviceProviderId: 'u-2',
    serviceProviderName: '接单方',
    serviceProviderAvatar: '',
    serviceProviderCreditScore: 75,
    status: 'ACCEPTED',
    note: null,
    proofSubmitted: false,
    proofImageCount: 0,
    createdAt: '2026-10-10T10:00:00',
    updatedAt: '2026-10-10T10:00:00',
    completedAt: '',
    timeline: [],
    demandReward: 10,
    ...overrides
  } as OrderRecord
}

function buildDemand(overrides: Partial<DemandRecord> = {}): DemandRecord {
  return {
    id: 'd-1',
    title: '测试需求',
    description: '描述',
    category: 'EXPRESS',
    campusZone: 'GULOU',
    location: '图书馆',
    startTime: '2026-10-10T10:00:00',
    endTime: '2026-10-10T12:00:00',
    reward: 10,
    interactionMode: 'SELECT_ONE',
    status: 'PENDING',
    anonymous: false,
    anonymousCode: null,
    publisherId: 'u-1',
    publisherName: '发布者',
    publisherAvatar: '',
    tags: [],
    createdAt: '2026-10-10T09:00:00',
    updatedAt: '2026-10-10T09:00:00',
    distanceKm: 0,
    ...overrides
  } as DemandRecord
}

function buildMyResponse(overrides: Partial<MyDemandResponseRecord> = {}): MyDemandResponseRecord {
  return {
    id: 'r-1',
    demandId: 'd-1',
    demandTitle: '测试需求',
    demandStatus: 'PENDING',
    demandCategory: 'SECOND_HAND',
    demandInteractionMode: 'SELECT_ONE',
    authorId: 'u-2',
    authorName: '响应者',
    content: '我想要这个物品',
    status: 'PENDING',
    createdAt: '2026-10-10T10:00:00',
    updatedAt: '2026-10-10T10:00:00',
    ...overrides
  }
}

describe('workbenchRules - 待办生成', () => {
  describe('generateOrderTodos', () => {
    it('接单方有 ACCEPTED 订单 → 生成开始执行待办', () => {
      const orders = [buildOrder({ status: 'ACCEPTED', serviceProviderId: 'u-2' })]
      const todos = generateOrderTodos(orders, 'u-2')
      expect(todos).toHaveLength(1)
      expect(todos[0].actionLabel).toBe('开始执行')
      expect(todos[0].route).toBe('/orders/o-1')
      expect(todos[0].category).toBe('order_provider')
    })

    it('接单方有 IN_PROGRESS 且未提交凭证 → 生成上传凭证待办', () => {
      const orders = [buildOrder({ status: 'IN_PROGRESS', serviceProviderId: 'u-2', proofSubmitted: false })]
      const todos = generateOrderTodos(orders, 'u-2')
      expect(todos).toHaveLength(1)
      expect(todos[0].actionLabel).toBe('上传凭证并完成')
    })

    it('需求方在接单方已提交凭证后 → 生成确认完成待办', () => {
      const orders = [buildOrder({ status: 'IN_PROGRESS', requesterId: 'u-1', proofSubmitted: true, timeline: [] })]
      const todos = generateOrderTodos(orders, 'u-1')
      expect(todos).toHaveLength(1)
      expect(todos[0].actionLabel).toBe('确认完成')
      expect(todos[0].category).toBe('order_requester')
    })

    it('需求方已在时间线中确认完成 → 不再生成确认待办', () => {
      const orders = [buildOrder({
        status: 'IN_PROGRESS',
        requesterId: 'u-1',
        proofSubmitted: true,
        timeline: [{ at: '2026-10-10T11:00:00', label: '需求方确认完成', operatorId: 'u-1' }]
      })]
      const todos = generateOrderTodos(orders, 'u-1')
      expect(todos).toHaveLength(0)
    })

    it('已完成订单 → 不生成待办', () => {
      const orders = [buildOrder({ status: 'COMPLETED', serviceProviderId: 'u-2' })]
      const todos = generateOrderTodos(orders, 'u-2')
      expect(todos).toHaveLength(0)
    })

    it('已取消订单 → 不生成待办', () => {
      const orders = [buildOrder({ status: 'CANCELLED', serviceProviderId: 'u-2' })]
      const todos = generateOrderTodos(orders, 'u-2')
      expect(todos).toHaveLength(0)
    })

    it('第三方（非参与方）订单 → 不生成待办', () => {
      const orders = [buildOrder({ status: 'ACCEPTED', requesterId: 'u-1', serviceProviderId: 'u-2' })]
      const todos = generateOrderTodos(orders, 'u-3')
      expect(todos).toHaveLength(0)
    })

    it('接单方已确认但未提交凭证（IN_PROGRESS + proofSubmitted=false + 已确认） → 不生成凭证待办', () => {
      const orders = [buildOrder({
        status: 'IN_PROGRESS',
        serviceProviderId: 'u-2',
        proofSubmitted: false,
        timeline: [{ at: '2026-10-10T11:00:00', label: '接单方确认完成', operatorId: 'u-2' }]
      })]
      const todos = generateOrderTodos(orders, 'u-2')
      expect(todos).toHaveLength(0)
    })

    it('仲裁中且可提交 → 生成补充仲裁说明待办', () => {
      const orders = [buildOrder({ status: 'IN_ARBITRATION', serviceProviderId: 'u-2', canSubmitArbitrationResponse: true })]
      const todos = generateOrderTodos(orders, 'u-2')
      expect(todos).toHaveLength(1)
      expect(todos[0].actionLabel).toBe('补充仲裁说明')
      expect(todos[0].category).toBe('arbitration')
    })
  })

  describe('generateDemandPublisherTodos', () => {
    it('SELECT_ONE 需求有 PENDING 响应 → 生成选择响应者待办', () => {
      const demands = [buildDemand({ interactionMode: 'SELECT_ONE', publisherId: 'u-1' })]
      const counts = new Map([['d-1', 3]])
      const todos = generateDemandPublisherTodos(demands, 'u-1', counts)
      expect(todos).toHaveLength(1)
      expect(todos[0].actionLabel).toBe('选择响应者')
      expect(todos[0].description).toContain('3')
    })

    it('SELECT_MANY 需求有 PENDING 报名 → 生成选择组队成员待办', () => {
      const demands = [buildDemand({ interactionMode: 'SELECT_MANY', publisherId: 'u-1', targetParticipantCount: 5, selectedParticipantCount: 2 })]
      const counts = new Map([['d-1', 1]])
      const todos = generateDemandPublisherTodos(demands, 'u-1', counts)
      expect(todos).toHaveLength(1)
      expect(todos[0].actionLabel).toBe('选择组队成员')
      expect(todos[0].description).toContain('2/5')
    })

    it('HELP 需求有 PENDING 回答 → 生成采纳回答待办', () => {
      const demands = [buildDemand({ interactionMode: 'HELP', publisherId: 'u-1' })]
      const counts = new Map([['d-1', 2]])
      const todos = generateDemandPublisherTodos(demands, 'u-1', counts)
      expect(todos).toHaveLength(1)
      expect(todos[0].actionLabel).toBe('采纳回答')
    })

    it('无 PENDING 响应 → 不生成待办', () => {
      const demands = [buildDemand({ interactionMode: 'SELECT_ONE', publisherId: 'u-1' })]
      const counts = new Map([['d-1', 0]])
      const todos = generateDemandPublisherTodos(demands, 'u-1', counts)
      expect(todos).toHaveLength(0)
    })

    it('已完成需求 → 不生成待办', () => {
      const demands = [buildDemand({ interactionMode: 'SELECT_ONE', publisherId: 'u-1', status: 'COMPLETED' })]
      const counts = new Map([['d-1', 5]])
      const todos = generateDemandPublisherTodos(demands, 'u-1', counts)
      expect(todos).toHaveLength(0)
    })

    it('非当前用户发布的需求 → 不生成待办', () => {
      const demands = [buildDemand({ interactionMode: 'SELECT_ONE', publisherId: 'u-99' })]
      const counts = new Map([['d-1', 5]])
      const todos = generateDemandPublisherTodos(demands, 'u-1', counts)
      expect(todos).toHaveLength(0)
    })

    it('DIRECT_ACCEPT 需求 → 不生成发布者待办', () => {
      const demands = [buildDemand({ interactionMode: 'DIRECT_ACCEPT', publisherId: 'u-1' })]
      const counts = new Map([['d-1', 10]])
      const todos = generateDemandPublisherTodos(demands, 'u-1', counts)
      expect(todos).toHaveLength(0)
    })
  })
})

describe('workbenchRules - 记录分类', () => {
  describe('buildPublishedDemandItems', () => {
    it('只返回当前用户发布的需求', () => {
      const demands = [
        buildDemand({ id: 'd-1', publisherId: 'u-1', title: '我的需求' }),
        buildDemand({ id: 'd-2', publisherId: 'u-2', title: '别人的需求' })
      ]
      const items = buildPublishedDemandItems(demands, 'u-1')
      expect(items).toHaveLength(1)
      expect(items[0].demand.title).toBe('我的需求')
    })

    it('组队进度正确显示', () => {
      const demands = [buildDemand({ interactionMode: 'SELECT_MANY', status: 'PENDING', targetParticipantCount: 5, selectedParticipantCount: 3, publisherId: 'u-1' })]
      const items = buildPublishedDemandItems(demands, 'u-1')
      expect(items[0].hint).toContain('3/5')
    })
  })

  describe('buildParticipatedResponseItems', () => {
    it('PENDING 响应在 PENDING 需求上 → 等待发布者处理', () => {
      const responses = [buildMyResponse({ status: 'PENDING', demandStatus: 'PENDING' })]
      const items = buildParticipatedResponseItems(responses)
      expect(items[0].hint).toBe('等待发布者处理')
    })

    it('SELECTED 响应 → 已被选中', () => {
      const responses = [buildMyResponse({ status: 'SELECTED' })]
      const items = buildParticipatedResponseItems(responses)
      expect(items[0].hint).toBe('已被选中')
    })

    it('REJECTED 响应 → 未被选中', () => {
      const responses = [buildMyResponse({ status: 'REJECTED' })]
      const items = buildParticipatedResponseItems(responses)
      expect(items[0].hint).toBe('未被选中')
    })

    it('WITHDRAWN 响应 → 已撤回', () => {
      const responses = [buildMyResponse({ status: 'WITHDRAWN' })]
      const items = buildParticipatedResponseItems(responses)
      expect(items[0].hint).toBe('已撤回')
    })
  })

  describe('buildOrderListItems', () => {
    it('只返回当前用户参与的订单', () => {
      const orders = [
        buildOrder({ id: 'o-1', requesterId: 'u-1', serviceProviderId: 'u-2' }),
        buildOrder({ id: 'o-2', requesterId: 'u-3', serviceProviderId: 'u-4' })
      ]
      const items = buildOrderListItems(orders, 'u-1')
      expect(items).toHaveLength(1)
      expect(items[0].order.id).toBe('o-1')
      expect(items[0].role).toBe('requester')
    })

    it('provider 角色筛选', () => {
      const orders = [
        buildOrder({ id: 'o-1', requesterId: 'u-2', serviceProviderId: 'u-3' }),
        buildOrder({ id: 'o-2', requesterId: 'u-3', serviceProviderId: 'u-2' })
      ]
      const items = buildOrderListItems(orders, 'u-2', 'provider')
      expect(items).toHaveLength(1)
      expect(items[0].order.id).toBe('o-2')
      expect(items[0].role).toBe('provider')
    })

    it('正式订单列表不包含 DemandPlaceholderOrder', () => {
      const orders = [buildOrder({ id: 'o-1', requesterId: 'u-1', serviceProviderId: 'u-2' })]
      const items = buildOrderListItems(orders, 'u-1')
      expect(items.every((item) => 'order' in item && item.order.id.startsWith('o-'))).toBe(true)
      expect(items.some((item) => (item as any).isPlaceholder)).toBe(false)
    })
  })
})

describe('workbenchRules - 通知分类', () => {
  it('互动通知：新响应、收到评价、组队评价收到', () => {
    expect(categorizeNotification('DEMAND_RESPONSE_RECEIVED')).toBe('interaction')
    expect(categorizeNotification('REVIEW_RECEIVED')).toBe('interaction')
    expect(categorizeNotification('RESPONSE_REVIEW_RECEIVED')).toBe('interaction')
  })

  it('订单履约通知：接单、状态变更、仲裁、待评价', () => {
    expect(categorizeNotification('ORDER_ACCEPTED')).toBe('order_fulfillment')
    expect(categorizeNotification('STATUS_CHANGED')).toBe('order_fulfillment')
    expect(categorizeNotification('ORDER_ARBITRATION_REQUESTED')).toBe('order_fulfillment')
    expect(categorizeNotification('ORDER_ARBITRATION_RESOLVED')).toBe('order_fulfillment')
    expect(categorizeNotification('PENDING_REVIEW')).toBe('order_fulfillment')
  })

  it('系统通知：审核请求、审核通过、审核驳回', () => {
    expect(categorizeNotification('REVIEW_REQUEST')).toBe('system')
    expect(categorizeNotification('DEMAND_APPROVED')).toBe('system')
    expect(categorizeNotification('DEMAND_REJECTED')).toBe('system')
  })
})

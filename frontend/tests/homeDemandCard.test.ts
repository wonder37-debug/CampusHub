import { describe, expect, it } from 'vitest'
import { getHomeDemandCard, isVisibleOnDemandHome } from '@/lib/home-demand-card'
import type { DemandRecord } from '@/types/campushub'

function demand(overrides: Partial<DemandRecord> = {}): DemandRecord {
  return {
    id: 'd-1',
    title: '测试需求',
    description: '需求描述',
    category: 'ERRAND',
    campusZone: 'GULOU',
    location: '鼓楼校区',
    startTime: '2026-10-12T10:00:00',
    endTime: '2026-10-12T12:00:00',
    reward: 20,
    interactionMode: 'DIRECT_ACCEPT',
    targetParticipantCount: null,
    selectedParticipantCount: null,
    status: 'PENDING',
    anonymous: false,
    anonymousCode: null,
    publisherId: 'publisher-1',
    publisherName: '小林',
    publisherAvatar: '/avatar.png',
    tags: [],
    createdAt: '2026-10-11T10:00:00',
    updatedAt: '2026-10-11T10:00:00',
    distanceKm: 0,
    images: [],
    ...overrides
  }
}

describe('首页 Demand 卡片业务信息', () => {
  it('把审核中、取消、过期需求排除出公开首页', () => {
    expect(isVisibleOnDemandHome(demand({ status: 'REVIEWING' }))).toBe(false)
    expect(isVisibleOnDemandHome(demand({ status: 'CANCELLED' }))).toBe(false)
    expect(isVisibleOnDemandHome(demand({ status: 'EXPIRED' }))).toBe(false)
    expect(isVisibleOnDemandHome(demand({ status: 'IN_PROGRESS' }))).toBe(true)
    expect(isVisibleOnDemandHome(demand({ status: 'COMPLETED' }))).toBe(true)
  })

  it('任务委托突出奖励和履约时间地点', () => {
    const card = getHomeDemandCard(demand({ canAccept: true }), 'viewer-2')
    expect(card.rewardLabel).toBe('20 校邻币')
    expect(card.location).toBe('鼓楼校区')
    expect(card.timeLabel).toContain('10/12')
    expect(card.statusLabel).toBe('可接单')
    expect(card.isPublisher).toBe(false)
  })

  it('二手卡片不把校邻币奖励显示为商品售价', () => {
    const card = getHomeDemandCard(demand({ category: 'SECOND_HAND', reward: 99 }), 'viewer-2')
    expect(card.rewardLabel).toBeNull()
    expect(card.focusLabel).toBe('物品信息')
  })

  it('组队卡片只用已选人数和目标人数，不推断报名人数', () => {
    const card = getHomeDemandCard(demand({
      category: 'TEAM_UP',
      interactionMode: 'SELECT_MANY',
      targetParticipantCount: 8,
      selectedParticipantCount: 3
    }), 'viewer-2')
    expect(card.participantProgress).toBe('已选 3 / 8 人')
    expect(card.rewardLabel).toBeNull()
  })

  it('匿名卡片不暴露本人身份线索，发布者关系仅来自当前用户 ID', () => {
    const privateAnonymous = demand({ anonymous: true, anonymousCode: '匿名同学 08' })
    const card = getHomeDemandCard(privateAnonymous, 'publisher-1')
    expect(card.publisherName).toBe('匿名同学 08')
    expect(card.publisherAvatar).toBeNull()
    expect(card.isPublisher).toBe(true)
    expect(getHomeDemandCard(demand({ publisherId: '' }), 'other').isPublisher).toBe(false)
  })

  it('不支持响应数的模式不产生响应数量或个体响应状态', () => {
    const card = getHomeDemandCard(demand({ category: 'HELP', interactionMode: 'HELP' }), 'viewer-2')
    expect(card.responseSummary).toBeNull()
    expect(card.personalResponseStatus).toBeNull()
  })

  it('人数数据无效时不生成组队进度或名额已满状态', () => {
    const card = getHomeDemandCard(demand({
      category: 'TEAM_UP',
      interactionMode: 'SELECT_MANY',
      targetParticipantCount: 0,
      selectedParticipantCount: 4
    }), 'viewer-2')
    expect(card.participantProgress).toBeNull()
    expect(card.statusLabel).toBe('开放中')
  })
})

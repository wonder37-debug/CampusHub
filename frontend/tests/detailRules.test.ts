import { describe, expect, it } from 'vitest'
import { canCancelOrder, canRespondToDemand, canSelectResponse, canViewSelectedResponse, getDemandDetailMode, getOrderFlowKind } from '@/utils/detailRules'

const demand = {
  id: 'd1',
  category: 'SECOND_HAND',
  interactionMode: 'SELECT_ONE',
  status: 'PENDING',
  publisherId: 'publisher'
}

describe('Demand 与 Order 详情角色规则', () => {
  it('按后端 interactionMode 选择需求展示模式', () => {
    expect(getDemandDetailMode(demand)).toBe('select-one')
    expect(getDemandDetailMode({ ...demand, interactionMode: 'HELP', category: 'HELP' })).toBe('help')
    expect(getDemandDetailMode({ ...demand, interactionMode: 'SELECT_MANY', category: 'TEAM_UP' })).toBe('select-many')
    expect(getDemandDetailMode({ ...demand, interactionMode: 'DIRECT_ACCEPT', category: 'ERRAND' })).toBe('direct-accept')
  })

  it('禁止发布者、管理员和非开放需求执行普通响应', () => {
    expect(canRespondToDemand(demand, 'visitor', 'USER')).toBe(true)
    expect(canRespondToDemand(demand, 'publisher', 'USER')).toBe(false)
    expect(canRespondToDemand(demand, 'admin', 'ADMIN')).toBe(false)
    expect(canRespondToDemand({ ...demand, status: 'COMPLETED' }, 'visitor', 'USER')).toBe(false)
  })

  it('仅发布者可选择待处理响应', () => {
    expect(canSelectResponse(demand, 'publisher', 'USER', 'PENDING')).toBe(true)
    expect(canSelectResponse(demand, 'visitor', 'USER', 'PENDING')).toBe(false)
    expect(canSelectResponse(demand, 'publisher', 'USER', 'SELECTED')).toBe(false)
  })

  it('SELECT_ONE 已进入订单后，只允许发布者和所选响应者查看响应原文', () => {
    expect(canViewSelectedResponse(true, 'publisher', 'publisher', 'USER')).toBe(true)
    expect(canViewSelectedResponse(true, 'respondent', 'respondent', 'USER')).toBe(true)
    expect(canViewSelectedResponse(true, 'outsider', 'respondent', 'USER')).toBe(false)
    expect(canViewSelectedResponse(true, 'admin', 'respondent', 'ADMIN')).toBe(true)
    expect(canViewSelectedResponse(false, 'outsider', 'respondent', 'USER')).toBe(true)
  })

  it('仅接单方可在 ACCEPTED 阶段取消正式订单', () => {
    expect(canCancelOrder({ status: 'ACCEPTED', serviceProviderId: 'provider' }, 'provider', 'USER')).toBe(true)
    expect(canCancelOrder({ status: 'ACCEPTED', serviceProviderId: 'provider' }, 'publisher', 'USER')).toBe(false)
    expect(canCancelOrder({ status: 'IN_PROGRESS', serviceProviderId: 'provider' }, 'provider', 'USER')).toBe(false)
    expect(canCancelOrder({ status: 'ACCEPTED', serviceProviderId: 'provider' }, 'admin', 'ADMIN')).toBe(false)
  })

  it('区分直接接单和选择响应后建立的订单', () => {
    expect(getOrderFlowKind('DIRECT_ACCEPT')).toBe('direct-accept')
    expect(getOrderFlowKind('SELECT_ONE')).toBe('selected-response')
  })
})

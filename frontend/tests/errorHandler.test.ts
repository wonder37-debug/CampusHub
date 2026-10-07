import { describe, it, expect } from 'vitest'
import { translateApiError, handleError } from '@/utils/errorHandler'

describe('translateApiError - Demand Response 业务消息映射', () => {
  it('active response already exists → 友好中文提示，而不是通用 Conflict', () => {
    const msg = translateApiError({
      code: 1005,
      errorCode: 'BUSINESS_CONFLICT',
      message: 'active response already exists for this demand'
    })
    expect(msg).toBe('你已经提交过了，无需重复提交。')
    // 关键：不能是吓人的通用文案
    expect(msg).not.toContain('系统状态冲突')
  })

  it('demand is not open for responses', () => {
    expect(translateApiError({ message: 'demand is not open for responses' }))
      .toBe('该需求当前已关闭，暂时无法继续留言或报名。')
  })

  it('publisher cannot respond to own demand', () => {
    expect(translateApiError({ message: 'publisher cannot respond to own demand' }))
      .toBe('不能对自己发布的需求留言或报名。')
  })

  it('only pending response can be selected', () => {
    expect(translateApiError({ message: 'only pending response can be selected' }))
      .toBe('这条响应已经被处理，无法重复选择。')
  })

  it('TEAM_UP demand missing targetParticipantCount', () => {
    expect(translateApiError({ message: 'TEAM_UP demand missing targetParticipantCount' }))
      .toBe('该组队需求缺少目标人数，请重新发布或联系管理员。')
  })

  it('response {id} is not pending → 动态正则匹配', () => {
    expect(translateApiError({
      code: 1005,
      errorCode: 'BUSINESS_CONFLICT',
      message: 'response 123 is not pending'
    })).toBe('该报名已经被处理，无法继续操作。')
  })

  it('selection exceeds targetParticipantCount → 提取 target 数字', () => {
    expect(translateApiError({
      code: 1005,
      errorCode: 'BUSINESS_CONFLICT',
      message: 'selection exceeds targetParticipantCount (selected=0, new=2, target=1)'
    })).toBe('选择人数已超过目标人数，最多只能选择 1 人。')
  })

  it('cannot change category/interactionMode after responses exist', () => {
    expect(translateApiError({ message: 'cannot change category/interactionMode after responses exist' }))
      .toBe('已有同学留言或报名，暂时不能修改需求类型。')
  })

  it('DIRECT_ACCEPT demand does not accept responses', () => {
    expect(translateApiError({ message: 'DIRECT_ACCEPT demand does not accept responses, use accept endpoint' }))
      .toBe('该需求为直接接单模式，请使用接单按钮。')
  })

  it('未匹配的 BUSINESS_CONFLICT 不再回退到吓人的“系统状态冲突”', () => {
    const msg = translateApiError({
      code: 1005,
      errorCode: 'BUSINESS_CONFLICT',
      message: 'some unknown business conflict'
    })
    expect(msg).not.toContain('系统状态冲突')
    expect(msg).toContain('刷新')
  })
})

describe('translateApiError - 通用错误码映射', () => {
  it('VALIDATION_FAILED', () => {
    expect(translateApiError({ errorCode: 'VALIDATION_FAILED' }))
      .toBe('参数校验失败，请检查输入')
  })

  it('PERMISSION_DENIED', () => {
    expect(translateApiError({ errorCode: 'PERMISSION_DENIED' }))
      .toBe('没有权限执行该操作')
  })

  it('targetParticipantCount must not exceed 100', () => {
    expect(translateApiError({ message: 'targetParticipantCount must not exceed 100' }))
      .toBe('目标人数不能超过 100。')
  })

  it('SELECT_MANY demand requires targetParticipantCount >= 1', () => {
    expect(translateApiError({ message: 'SELECT_MANY demand requires targetParticipantCount >= 1' }))
      .toBe('目标人数必须为不小于 1 的正整数。')
  })
})

describe('translateApiError - RATE_LIMITED 场景', () => {
  it('全局并发满：保留后端“AI 服务繁忙”message', () => {
    expect(translateApiError({
      code: 1006,
      errorCode: 'RATE_LIMITED',
      message: 'AI 服务繁忙，请稍后重试'
    })).toBe('AI 服务繁忙，请稍后重试')
  })

  it('单用户超限：保留后端“请求过于频繁”message', () => {
    expect(translateApiError({
      code: 1006,
      errorCode: 'RATE_LIMITED',
      message: '请求过于频繁，请稍后再试'
    })).toBe('请求过于频繁，请稍后再试')
  })

  it('RATE_LIMITED 无中文 message 时回退通用文案', () => {
    expect(translateApiError({ code: 1006, errorCode: 'RATE_LIMITED' }))
      .toBe('请求过于频繁，请稍后再试')
  })

  it('RATE_LIMITED 英文 message 时回退通用文案（不泄露英文）', () => {
    const msg = translateApiError({
      code: 1006,
      errorCode: 'RATE_LIMITED',
      message: 'too many requests'
    })
    expect(msg).toBe('请求过于频繁，请稍后再试')
    expect(msg).not.toContain('too many')
  })
})

describe('handleError', () => {
  it('中文 Error 消息直接返回', () => {
    expect(handleError(new Error('你已经提交过了，无需重复提交。'))).toBe('你已经提交过了，无需重复提交。')
  })

  it('英文 Error 消息回退到 fallback', () => {
    expect(handleError(new Error('something went wrong'), '操作失败')).toBe('操作失败')
  })

  it('网络错误映射', () => {
    expect(handleError(new Error('Failed to fetch'))).toBe('网络异常，请检查网络或后端服务')
  })
})

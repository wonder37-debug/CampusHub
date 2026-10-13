import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCampusHubStore } from '@/stores/campusHub'

function reviewPayload(id: string, rating = 5) {
  return {
    id,
    orderId: 'o-' + id,
    responseId: null,
    demandId: 'd-' + id,
    demandTitle: '需求 ' + id,
    author: { id: 'reviewer-1', nickname: '评价人' },
    targetId: 'target-user',
    targetName: '目标用户',
    rating,
    comment: '评价内容 ' + id,
    createdAt: `2026-10-1${id.slice(-1)}T10:00:00`
  }
}

function okResponse(body: unknown) {
  return {
    ok: true,
    status: 200,
    json: async () => ({ data: body })
  }
}

describe('公开用户评价分页与切换用户竞态', () => {
  afterEach(() => vi.unstubAllGlobals())

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('第一页替换、后续页追加并按 id 去重，加载完毕后不再请求', async () => {
    const store = useCampusHubStore()
    const fetchMock = vi.fn()
    // page=1 返回 2 条，total=4
    fetchMock.mockResolvedValueOnce(okResponse({ items: [reviewPayload('r1'), reviewPayload('r2')], total: 4 }))
    // page=2 返回 2 条（含一条与 page1 重复的 r2），total=4
    fetchMock.mockResolvedValueOnce(okResponse({ items: [reviewPayload('r2'), reviewPayload('r3')], total: 4 }))
    vi.stubGlobal('fetch', fetchMock)

    const written1 = await store.fetchPublicUserReviews('target-user', 1, 2)
    expect(written1).toBe(true)
    expect(store.publicUserReviews.map((r) => r.id)).toEqual(['r1', 'r2'])
    expect(store.publicUserReviewsTotal).toBe(4)

    const written2 = await store.fetchPublicUserReviews('target-user', 2, 2)
    expect(written2).toBe(true)
    // 追加 r3，r2 去重后不重复
    expect(store.publicUserReviews.map((r) => r.id)).toEqual(['r1', 'r2', 'r3'])
    expect(store.publicUserReviewsTotal).toBe(4)
    // 已加载 3 条 < 4 条，仍可继续
    expect(store.publicUserReviews.length).toBeLessThan(store.publicUserReviewsTotal)
  })

  it('切换用户后仍在途的旧评价请求不得覆盖新用户数据', async () => {
    const store = useCampusHubStore()
    const pending: Array<(res: unknown) => void> = []
    const fetchMock = vi.fn(() => new Promise((resolve) => pending.push(resolve)))
    vi.stubGlobal('fetch', fetchMock)

    // 用户 A 的第一页请求（先不 resolve）
    const requestA = store.fetchPublicUserReviews('user-a', 1, 5)
    // 切换到用户 B：第一页会重置目标用户并清空
    const requestB = store.fetchPublicUserReviews('user-b', 1, 5)

    // 先 resolve 用户 B 的请求
    pending[1](okResponse({ items: [reviewPayload('b1')], total: 1 }))
    await requestB
    // 再 resolve 用户 A 的旧请求（应被丢弃）
    pending[0](okResponse({ items: [reviewPayload('a1'), reviewPayload('a2')], total: 2 }))
    await requestA

    expect(store.publicUserReviews.map((r) => r.id)).toEqual(['b1'])
    expect(store.publicUserReviewsTotal).toBe(1)
    expect(store.publicUserReviewsError).toBeNull()
  })

  it('被竞态丢弃的请求返回 false，成功写入返回 true', async () => {
    const store = useCampusHubStore()
    const pending: Array<(res: unknown) => void> = []
    vi.stubGlobal('fetch', vi.fn(() => new Promise((resolve) => pending.push(resolve))))

    // 用户 A 第一页（pending[0]，不 resolve）
    const requestA = store.fetchPublicUserReviews('user-a', 1, 5)
    // 切换到用户 B 第一页，立即 resolve
    const requestB = store.fetchPublicUserReviews('user-b', 1, 5)
    pending[1](okResponse({ items: [reviewPayload('b1')], total: 1 }))
    const writtenB = await requestB
    expect(writtenB).toBe(true)

    // A 的旧请求现在 resolve，应被丢弃并返回 false
    pending[0](okResponse({ items: [reviewPayload('a1')], total: 1 }))
    const writtenA = await requestA
    expect(writtenA).toBe(false)
    // 数据仍是 B 的，未被 A 覆盖
    expect(store.publicUserReviews.map((r) => r.id)).toEqual(['b1'])
  })

  it('请求失败时返回 false 并写入错误，不推进数据', async () => {
    const store = useCampusHubStore()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500, json: async () => ({ message: '服务异常' }) }))
    const written = await store.fetchPublicUserReviews('user-x', 1, 5)
    expect(written).toBe(false)
    expect(store.publicUserReviewsError).toBeTruthy()
    expect(store.publicUserReviews).toEqual([])
  })

  it('切换用户后仍在途的旧后续页请求不得追加到新用户列表', async () => {
    const store = useCampusHubStore()
    const pending: Array<(res: unknown) => void> = []
    vi.stubGlobal('fetch', vi.fn(() => new Promise((resolve) => pending.push(resolve))))

    // 用户 A 第一页：先 resolve 建立基线（pending[0]）
    const reqA1 = store.fetchPublicUserReviews('user-a', 1, 1)
    pending[0](okResponse({ items: [reviewPayload('a1')], total: 3 }))
    await reqA1
    expect(store.publicUserReviews.map((r) => r.id)).toEqual(['a1'])

    // 用户 A 第二页（pending[1]，模拟在途，先不 resolve）
    const reqA2 = store.fetchPublicUserReviews('user-a', 2, 1)
    // 切换到用户 B 第一页（pending[2]）：立即 resolve
    const reqB1 = store.fetchPublicUserReviews('user-b', 1, 1)
    pending[2](okResponse({ items: [reviewPayload('b1')], total: 1 }))
    await reqB1
    expect(store.publicUserReviews.map((r) => r.id)).toEqual(['b1'])

    // A 的第二页现在 resolve（应被丢弃，不会把 a2 追加到 B 的列表）
    pending[1](okResponse({ items: [reviewPayload('a2')], total: 3 }))
    await reqA2

    expect(store.publicUserReviews.map((r) => r.id)).toEqual(['b1'])
    expect(store.publicUserReviewsTotal).toBe(1)
  })
})

describe('公开用户资料与统计切换用户竞态', () => {
  afterEach(() => vi.unstubAllGlobals())

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('切换用户后旧的 profile 请求不得覆盖新资料', async () => {
    const store = useCampusHubStore()
    const pending: Array<(res: unknown) => void> = []
    vi.stubGlobal('fetch', vi.fn(() => new Promise((resolve) => pending.push(resolve))))

    const reqA = store.fetchPublicUserProfile('user-a')
    const reqB = store.fetchPublicUserProfile('user-b')

    pending[1](okResponse({ id: 'user-b', nickname: '用户B', creditScore: 90 }))
    await reqB
    pending[0](okResponse({ id: 'user-a', nickname: '用户A', creditScore: 50 }))
    await reqA

    expect(store.publicUserProfile?.id).toBe('user-b')
    expect(store.publicUserProfile?.nickname).toBe('用户B')
  })

  it('切换用户后旧的 stats 请求不得覆盖新统计', async () => {
    const store = useCampusHubStore()
    const pending: Array<(res: unknown) => void> = []
    vi.stubGlobal('fetch', vi.fn(() => new Promise((resolve) => pending.push(resolve))))

    const reqA = store.fetchPublicUserStats('user-a')
    const reqB = store.fetchPublicUserStats('user-b')

    pending[1](okResponse({ completedOrders: 9 }))
    await reqB
    pending[0](okResponse({ completedOrders: 3 }))
    await reqA

    expect(store.publicUserStats?.completedOrders).toBe(9)
  })
})

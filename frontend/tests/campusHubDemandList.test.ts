import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCampusHubStore } from '@/stores/campusHub'

describe('CampusHub Demand 列表状态', () => {
  afterEach(() => vi.unstubAllGlobals())

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('请求失败时清除旧需求并提供明确错误状态', async () => {
    const store = useCampusHubStore()
    store.demands = [{ id: 'stale' } as any]
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')))

    await store.fetchDemands({ all: true })

    expect(store.demands).toEqual([])
    expect(store.demandFetchError).toContain('需求加载失败')
  })

  it('映射组队已选人数，供首页展示权威进度', async () => {
    const store = useCampusHubStore()
    const demand = {
      id: 77,
      title: '社团招募',
      description: '一起参加活动',
      category: 'TEAM_UP',
      campusZone: 'GULOU',
      location: '鼓楼校区',
      startTime: '2026-10-12T10:00:00',
      endTime: '2026-10-12T12:00:00',
      reward: 0,
      interactionMode: 'SELECT_MANY',
      targetParticipantCount: 6,
      selectedParticipantCount: 2,
      status: 'PENDING',
      anonymous: false,
      anonymousCode: null,
      publisherId: 9,
      publisherDisplayName: '小王',
      publisher: { id: 9, nickname: '小王', avatarUrl: '/avatar.jpg', creditScore: 88, role: 'USER', status: 'ACTIVE' },
      tags: [],
      createdAt: '2026-10-12T08:00:00',
      updatedAt: '2026-10-12T08:00:00',
      images: []
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ data: { items: [demand], total: 1 } })
    }))

    await store.fetchDemands({ all: true })

    expect(store.demands[0].selectedParticipantCount).toBe(2)
    expect(store.demandFetchError).toBeNull()
    expect(store.demands[0].publisherAvatar).toBe('/avatar.jpg')
  })

  it('较早返回的筛选请求不能覆盖较新的列表', async () => {
    const store = useCampusHubStore()
    const pendingResponses: Array<(response: any) => void> = []
    vi.stubGlobal('fetch', vi.fn(() => new Promise((resolve) => pendingResponses.push(resolve))))

    const olderRequest = store.fetchDemands({ all: true })
    const newerRequest = store.fetchDemands({ all: true })
    const response = (title: string) => ({
      ok: true,
      status: 200,
      json: async () => ({ data: { items: [{ id: title, title, status: 'PENDING' }], total: 1 } })
    })

    pendingResponses[1](response('较新筛选'))
    await newerRequest
    pendingResponses[0](response('较旧筛选'))
    await olderRequest

    expect(store.demands.map((item) => item.title)).toEqual(['较新筛选'])
  })
})

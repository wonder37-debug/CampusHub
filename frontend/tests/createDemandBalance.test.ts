import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCampusHubStore } from '@/stores/campusHub'

describe('createDemand 余额同步', () => {
  let originalFetch: typeof global.fetch

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    originalFetch = global.fetch
  })

  afterEach(() => {
    vi.restoreAllMocks()
    global.fetch = originalFetch
  })

  it('createDemand 成功后调用 fetchProfile 刷新余额', async () => {
    const store = useCampusHubStore()
    store.token = 'test-token'
    store.currentUserId = '1'

    const fetchMock = vi.fn().mockImplementation((url: string, init?: any) => {
      if (url.includes('/demands') && init?.method === 'POST') {
        return Promise.resolve({
          ok: true, status: 200,
          json: async () => ({ code: 0, data: { id: 'd1', title: '测试', status: 'REVIEWING', interactionMode: 'DIRECT_ACCEPT', category: 'EXPRESS', campusZone: 'XIANLIN' } })
        })
      }
      if (url.includes('/users/me')) {
        return Promise.resolve({
          ok: true, status: 200,
          json: async () => ({ code: 0, data: { id: '1', studentId: 'S1', nickname: 'u', balance: 95, frozenBalance: 5, role: 'USER', status: 'ACTIVE', creditScore: 100 } })
        })
      }
      return Promise.resolve({ ok: true, status: 200, json: async () => ({ code: 0, data: { items: [], total: 0 } }) })
    })
    global.fetch = fetchMock as unknown as typeof global.fetch

    await store.createDemand({
      title: '测试需求', description: '描述', category: 'EXPRESS', campusZone: 'XIANLIN',
      location: '测试', startTime: '2026-10-08T16:00', endTime: '2026-10-09T17:00',
      reward: 5, tags: '', images: [], anonymous: false, interactionMode: 'DIRECT_ACCEPT'
    } as any)

    const profileCall = fetchMock.mock.calls.find(call => call[0]?.includes('/users/me'))
    expect(profileCall).toBeDefined()
  })
})

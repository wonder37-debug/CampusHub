import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCampusHubStore } from '@/stores/campusHub'

const UNAUTHORIZED_BODY = { code: 1001, errorCode: 'AUTH_FAILED', message: 'token has expired' }

function mockFetchUnauthorized() {
  global.fetch = vi.fn().mockResolvedValue({
    ok: false,
    status: 401,
    json: async () => UNAUTHORIZED_BODY
  }) as unknown as typeof global.fetch
}

describe('401 session expiry handling', () => {
  let assignMock: ReturnType<typeof vi.fn>
  let originalFetch: typeof global.fetch

  beforeEach(() => {
    localStorage.clear()
    assignMock = vi.fn()
    // jsdom 的 location.assign 会尝试真实导航，spy 并空实现避免污染
    vi.spyOn(window.location, 'assign').mockImplementation(assignMock as never)
    originalFetch = global.fetch
    mockFetchUnauthorized()
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.restoreAllMocks()
    global.fetch = originalFetch
  })

  it('clears session and redirects when the current token gets 401', async () => {
    localStorage.setItem('campushub.token', 'old-token')
    localStorage.setItem('campushub.userId', '1')
    const store = useCampusHubStore()

    await store.fetchProfile()

    expect(localStorage.getItem('campushub.token')).toBeNull()
    expect(localStorage.getItem('campushub.userId')).toBeNull()
    expect(assignMock).toHaveBeenCalled()
  })

  it('keeps the new session when a stale token request returns 401', async () => {
    // 用户已重新登录：本地已是新 token
    localStorage.setItem('campushub.token', 'new-token')
    localStorage.setItem('campushub.userId', '1')
    const store = useCampusHubStore()
    // 模拟旧请求发起时绑定的 token（请求飞行期间用户重新登录，本地 token 已更新）
    store.token = 'old-token'

    await store.fetchProfile()

    // requestJson 用旧 token 发起，401 后比较本地新 token 不一致 → 保留新登录态
    expect(localStorage.getItem('campushub.token')).toBe('new-token')
    expect(localStorage.getItem('campushub.userId')).toBe('1')
    expect(assignMock).not.toHaveBeenCalled()
  })
})

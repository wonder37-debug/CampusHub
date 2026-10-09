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

function seedSession(token: string, userId = '1') {
  localStorage.setItem('campushub.token', token)
  localStorage.setItem('campushub.userId', userId)
  localStorage.setItem('campushub.profile', JSON.stringify({ id: userId, nickname: 'u' }))
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

  it('clears localStorage + Pinia session and redirects when current token gets 401', async () => {
    seedSession('old-token')
    const store = useCampusHubStore()

    await store.fetchProfile()

    // localStorage session 清理
    expect(localStorage.getItem('campushub.token')).toBeNull()
    expect(localStorage.getItem('campushub.userId')).toBeNull()
    expect(localStorage.getItem('campushub.profile')).toBeNull()
    // Pinia 内存状态同步清理（关键：避免 UI 仍显示已登录但 API 全 401）
    expect(store.token).toBe('')
    expect(store.currentUserId).toBe('')
    expect(store.currentProfile).toBeNull()
    // 非 /auth 页面继续 redirect
    expect(assignMock).toHaveBeenCalled()
  })

  it('clears both localStorage and Pinia session on /auth page without redirect', async () => {
    seedSession('old-token')
    // 当前已在 /auth 页面，clearExpiredSession 不会触发 location.assign 重载
    vi.spyOn(window.location, 'pathname', 'get').mockReturnValue('/auth')
    const store = useCampusHubStore()

    await store.fetchProfile()

    // localStorage session 清理
    expect(localStorage.getItem('campushub.token')).toBeNull()
    expect(localStorage.getItem('campushub.userId')).toBeNull()
    expect(localStorage.getItem('campushub.profile')).toBeNull()
    // Pinia 内存状态同步清理（/auth 不跳转重载，否则会留下脏状态）
    expect(store.token).toBe('')
    expect(store.currentUserId).toBe('')
    expect(store.currentProfile).toBeNull()
    // 不执行重复 redirect
    expect(assignMock).not.toHaveBeenCalled()
  })

  it('keeps the new session when a stale token request returns 401', async () => {
    // 用户已重新登录：本地已是新 token
    seedSession('new-token')
    const store = useCampusHubStore()
    // 模拟旧请求发起时绑定的 token（请求飞行期间用户重新登录，本地 token 已更新为新值）
    store.token = 'old-token'

    // 用 fetchNotifications（其 catch 不清 currentProfile）隔离验证 clearExpiredSession 不误清
    await store.fetchNotifications()

    // requestJson 用旧 token 发起，401 后比较本地新 token 不一致 → 保留新登录态，不清理不跳转
    expect(localStorage.getItem('campushub.token')).toBe('new-token')
    expect(localStorage.getItem('campushub.userId')).toBe('1')
    expect(localStorage.getItem('campushub.profile')).not.toBeNull()
    // Pinia session 同样保留（未被误清）
    expect(store.currentUserId).toBe('1')
    expect(store.currentProfile).not.toBeNull()
    expect(assignMock).not.toHaveBeenCalled()
  })

  it('fetchProfile keeps new session when stale token returns 401', async () => {
    seedSession('new-token')
    const store = useCampusHubStore()
    store.token = 'old-token'

    await store.fetchProfile()

    expect(localStorage.getItem('campushub.token')).toBe('new-token')
    expect(localStorage.getItem('campushub.userId')).toBe('1')
    expect(localStorage.getItem('campushub.profile')).not.toBeNull()
    expect(assignMock).not.toHaveBeenCalled()
  })

  it('fetchProfile with no token preserves new login when stale 401 arrives', async () => {
    const store = useCampusHubStore()
    store.token = ''
    seedSession('new-token')

    await store.fetchProfile()

    expect(localStorage.getItem('campushub.token')).toBe('new-token')
    expect(localStorage.getItem('campushub.userId')).toBe('1')
    expect(assignMock).not.toHaveBeenCalled()
  })
})

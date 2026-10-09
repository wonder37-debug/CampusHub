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

  it('旧请求成功返回时不覆盖新登录用户的资料（可控延迟）', async () => {
    seedSession('token-a', 'user-a')
    const store = useCampusHubStore()
    store.token = 'token-a'
    store.currentUserId = 'user-a'
    store.currentProfile = { id: 'user-a', nickname: '用户A' } as any

    let resolveOldRequest!: (value: any) => void
    const oldRequestPromise = new Promise<any>((resolve) => { resolveOldRequest = resolve })
    global.fetch = vi.fn().mockReturnValue(oldRequestPromise) as unknown as typeof global.fetch

    const fetchProfilePromise = store.fetchProfile()

    seedSession('token-b', 'user-b')
    store.token = 'token-b'
    store.currentUserId = 'user-b'
    store.currentProfile = { id: 'user-b', nickname: '用户B' } as any

    resolveOldRequest({
      ok: true,
      status: 200,
      json: async () => ({ code: 0, data: { id: 'user-a', studentId: 'A', nickname: '用户A', balance: 100, frozenBalance: 0, role: 'USER', status: 'ACTIVE', creditScore: 100 } })
    })

    await fetchProfilePromise

    expect(store.token).toBe('token-b')
    expect(store.currentUserId).toBe('user-b')
    expect(store.currentProfile?.id).toBe('user-b')
    expect(store.currentProfile?.nickname).toBe('用户B')
    expect(localStorage.getItem('campushub.token')).toBe('token-b')
    expect(localStorage.getItem('campushub.userId')).toBe('user-b')
  })

  it('旧请求 401 返回时不清除新登录会话（可控延迟）', async () => {
    seedSession('token-b', 'user-b')
    const store = useCampusHubStore()
    store.token = 'token-a'
    store.currentUserId = 'user-b'
    store.currentProfile = { id: 'user-b', nickname: '用户B' } as any

    let resolveOldRequest!: (value: any) => void
    const oldRequestPromise = new Promise<any>((resolve) => { resolveOldRequest = resolve })
    global.fetch = vi.fn().mockReturnValue(oldRequestPromise) as unknown as typeof global.fetch

    const fetchProfilePromise = store.fetchProfile()

    resolveOldRequest({
      ok: false,
      status: 401,
      json: async () => ({ code: 1001, errorCode: 'AUTH_FAILED', message: 'token has expired' })
    })

    await fetchProfilePromise

    expect(localStorage.getItem('campushub.token')).toBe('token-b')
    expect(localStorage.getItem('campushub.userId')).toBe('user-b')
    expect(store.currentProfile).not.toBeNull()
    expect(assignMock).not.toHaveBeenCalled()
  })

  it('内存 token 与 localStorage token 暂时不一致时旧请求不写入', async () => {
    seedSession('token-a', 'user-a')
    const store = useCampusHubStore()
    store.token = 'token-a'
    store.currentUserId = 'user-a'
    store.currentProfile = { id: 'user-a', nickname: '用户A' } as any

    let resolveOldRequest!: (value: any) => void
    const oldRequestPromise = new Promise<any>((resolve) => { resolveOldRequest = resolve })
    global.fetch = vi.fn().mockReturnValue(oldRequestPromise) as unknown as typeof global.fetch

    const fetchProfilePromise = store.fetchProfile()

    // 模拟内存 token 已更新但 localStorage 未更新（另一个标签页登录的竞态边界）
    store.token = 'token-b'

    resolveOldRequest({
      ok: true,
      status: 200,
      json: async () => ({ code: 0, data: { id: 'user-a', studentId: 'A', nickname: '用户A', balance: 100, frozenBalance: 0, role: 'USER', status: 'ACTIVE', creditScore: 100 } })
    })

    await fetchProfilePromise

    // requestToken='token-a', this.token='token-b' → 不匹配 → 不写入
    expect(store.currentUserId).toBe('user-a')
    expect(store.currentProfile?.id).toBe('user-a')
    expect(localStorage.getItem('campushub.token')).toBe('token-a')
  })

  it('Pinia已切换新会话但localStorage未更新时旧请求401不清除新会话（可控延迟）', async () => {
    seedSession('token-a', 'user-a')
    const store = useCampusHubStore()
    store.token = 'token-a'
    store.currentUserId = 'user-a'
    store.currentProfile = { id: 'user-a', nickname: '用户A' } as any

    let resolveOldRequest!: (value: any) => void
    const oldRequestPromise = new Promise<any>((resolve) => { resolveOldRequest = resolve })
    global.fetch = vi.fn().mockReturnValue(oldRequestPromise) as unknown as typeof global.fetch

    // A 发出请求（requestToken = 'token-a'）
    const fetchProfilePromise = store.fetchProfile()

    // Pinia 切换到 B，但 localStorage 保持旧值（模拟内存已切换但持久化未同步）
    store.token = 'token-b'
    store.currentUserId = 'user-b'
    store.currentProfile = { id: 'user-b', nickname: '用户B' } as any
    // localStorage 不更新 — 仍然是 'token-a'

    // A 的请求返回 401
    resolveOldRequest({
      ok: false,
      status: 401,
      json: async () => ({ code: 1001, errorCode: 'AUTH_FAILED', message: 'token has expired' })
    })

    await fetchProfilePromise

    // Pinia token/userId/profile 保留为新会话（B），未被旧请求的 401 清除
    expect(store.token).toBe('token-b')
    expect(store.currentUserId).toBe('user-b')
    expect(store.currentProfile?.id).toBe('user-b')
    expect(store.currentProfile?.nickname).toBe('用户B')
    // localStorage 也保留（未被清除）
    expect(localStorage.getItem('campushub.token')).toBe('token-a')
    expect(localStorage.getItem('campushub.userId')).toBe('user-a')
    // 不跳转
    expect(assignMock).not.toHaveBeenCalled()
  })

  it('A发出请求后会话切换到B(localStorage+Pinia同步)再让A返回401不清除B（可控延迟）', async () => {
    seedSession('token-a', 'user-a')
    const store = useCampusHubStore()
    store.token = 'token-a'
    store.currentUserId = 'user-a'
    store.currentProfile = { id: 'user-a', nickname: '用户A' } as any

    let resolveOldRequest!: (value: any) => void
    const oldRequestPromise = new Promise<any>((resolve) => { resolveOldRequest = resolve })
    global.fetch = vi.fn().mockReturnValue(oldRequestPromise) as unknown as typeof global.fetch

    // A 发出请求（requestToken = 'token-a'）
    const fetchProfilePromise = store.fetchProfile()

    // 会话完整切换到 B：Pinia + localStorage 同步更新
    store.token = 'token-b'
    store.currentUserId = 'user-b'
    store.currentProfile = { id: 'user-b', nickname: '用户B' } as any
    seedSession('token-b', 'user-b')

    // A 的请求返回 401
    resolveOldRequest({
      ok: false,
      status: 401,
      json: async () => ({ code: 1001, errorCode: 'AUTH_FAILED', message: 'token has expired' })
    })

    await fetchProfilePromise

    // B 的 token/userId/profile 保留，未被 A 的 401 清除
    expect(store.token).toBe('token-b')
    expect(store.currentUserId).toBe('user-b')
    expect(store.currentProfile?.id).toBe('user-b')
    expect(localStorage.getItem('campushub.token')).toBe('token-b')
    expect(localStorage.getItem('campushub.userId')).toBe('user-b')
    expect(assignMock).not.toHaveBeenCalled()
  })

  it('fetchNotifications旧请求401在Pinia已切换新会话时不清除B（可控延迟）', async () => {
    seedSession('token-a', 'user-a')
    const store = useCampusHubStore()
    store.token = 'token-a'
    store.currentUserId = 'user-a'
    store.currentProfile = { id: 'user-a', nickname: '用户A' } as any

    let resolveOldRequest!: (value: any) => void
    const oldRequestPromise = new Promise<any>((resolve) => { resolveOldRequest = resolve })
    global.fetch = vi.fn().mockReturnValue(oldRequestPromise) as unknown as typeof global.fetch

    // A 发出 fetchNotifications 请求
    const notificationsPromise = store.fetchNotifications()

    // Pinia 切换到 B，localStorage 保持旧值
    store.token = 'token-b'
    store.currentUserId = 'user-b'
    store.currentProfile = { id: 'user-b', nickname: '用户B' } as any

    // A 的请求返回 401
    resolveOldRequest({
      ok: false,
      status: 401,
      json: async () => ({ code: 1001, errorCode: 'AUTH_FAILED', message: 'token has expired' })
    })

    await notificationsPromise

    // B 的 session 保留
    expect(store.token).toBe('token-b')
    expect(store.currentUserId).toBe('user-b')
    expect(store.currentProfile?.id).toBe('user-b')
    expect(localStorage.getItem('campushub.token')).toBe('token-a')
    expect(assignMock).not.toHaveBeenCalled()
  })
})

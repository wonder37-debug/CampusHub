import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

import PrivateImage from '@/components/PrivateImage.vue'
import { useCampusHubStore } from '@/stores/campusHub'

const PROOF_URL = '/api/v1/uploads/2026/10/proof-a.png'
const OTHER_PROOF_URL = '/api/v1/uploads/2026/10/proof-b.png'
const EXTERNAL_URL = 'https://evil.example.com/api/v1/uploads/2026/10/proof-a.png'

let createdBlobUrls: string[] = []
let revokedBlobUrls: string[] = []

function createStore(token: string) {
  setActivePinia(createPinia())
  if (token) {
    localStorage.setItem('campushub.token', token)
  } else {
    localStorage.removeItem('campushub.token')
  }
  return useCampusHubStore()
}

function okResponse(): Response {
  return new Response('binary-image-bytes', { status: 200 })
}

function errorResponse(status: number): Response {
  return new Response(null, { status })
}

/** 拦截全局 fetch，记录调用参数并返回预置响应 */
function stubFetch(handler: (input: any, init?: any) => Response | Promise<Response>) {
  const calls: Array<{ input: any; init?: any }> = []
  const fn = vi.fn(async (input: any, init?: any) => {
    calls.push({ input, init })
    return handler(input, init)
  })
  vi.stubGlobal('fetch', fn)
  return { fn, calls }
}

function mountPrivateImage(src: string) {
  return mount(PrivateImage, { props: { src, alt: '凭证图片 1' } })
}

beforeEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
  createdBlobUrls = []
  revokedBlobUrls = []

  // happy-dom 未实现 Blob URL 工厂，这里替换成可观测的假实现
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => {
    const url = `blob:mock-${createdBlobUrls.length + 1}`
    createdBlobUrls.push(url)
    return url
  })
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation((url: string) => {
    revokedBlobUrls.push(url)
  })
  // 401 会触发会话过期跳转，测试中只需确认被调用
  vi.spyOn(window.location, 'assign').mockImplementation(() => {})
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('fetchProofImageBlob - 私密凭证认证加载', () => {
  it('携带登录令牌请求成功 → 返回 Blob URL', async () => {
    const store = createStore('valid-token')
    const { fn, calls } = stubFetch(() => okResponse())

    const result = await store.fetchProofImageBlob(PROOF_URL)

    expect(result.ok).toBe(true)
    if (!result.ok) throw new Error('expected success')
    expect(result.objectUrl.startsWith('blob:')).toBe(true)
    expect(fn).toHaveBeenCalledTimes(1)
    expect(calls[0].input).toBe(PROOF_URL)
    expect(calls[0].init?.headers?.Authorization).toBe('Bearer valid-token')
  })

  it('没有登录令牌 → 直接失败，且不发出任何请求', async () => {
    const store = createStore('')
    const { fn } = stubFetch(() => okResponse())

    const result = await store.fetchProofImageBlob(PROOF_URL)

    expect(result.ok).toBe(false)
    if (result.ok) throw new Error('expected failure')
    expect(result.message).toContain('登录')
    expect(fn).not.toHaveBeenCalled()
  })

  it('401 未授权 → 失败并清理过期会话，绝不回退到原始 URL', async () => {
    const store = createStore('expired-token')
    const { fn } = stubFetch(() => errorResponse(401))

    const result = await store.fetchProofImageBlob(PROOF_URL)

    expect(result.ok).toBe(false)
    if (result.ok) throw new Error('expected failure')
    expect(result.message).toContain('登录')
    expect(createdBlobUrls).toHaveLength(0)
    expect(localStorage.getItem('campushub.token')).toBeNull()
    expect(window.location.assign).toHaveBeenCalled()
    expect(fn).toHaveBeenCalledTimes(1)
  })

  it('403 无权限 → 失败且不创建 Blob URL', async () => {
    const store = createStore('valid-token')
    const { fn } = stubFetch(() => errorResponse(403))

    const result = await store.fetchProofImageBlob(PROOF_URL)

    expect(result.ok).toBe(false)
    if (result.ok) throw new Error('expected failure')
    expect(result.message).toContain('权限')
    expect(createdBlobUrls).toHaveLength(0)
    expect(fn).toHaveBeenCalledTimes(1)
  })

  it('网络异常 → 失败且不创建 Blob URL', async () => {
    const store = createStore('valid-token')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    const result = await store.fetchProofImageBlob(PROOF_URL)

    expect(result.ok).toBe(false)
    if (result.ok) throw new Error('expected failure')
    expect(createdBlobUrls).toHaveLength(0)
  })

  it('500 服务端错误 → 失败', async () => {
    const store = createStore('valid-token')
    stubFetch(() => errorResponse(500))

    const result = await store.fetchProofImageBlob(PROOF_URL)

    expect(result.ok).toBe(false)
    if (result.ok) throw new Error('expected failure')
    expect(createdBlobUrls).toHaveLength(0)
  })

  it('外部域 URL → 拒绝请求，避免 Authorization 泄露到第三方', async () => {
    const store = createStore('valid-token')
    const { fn } = stubFetch(() => okResponse())

    const result = await store.fetchProofImageBlob(EXTERNAL_URL)

    expect(result.ok).toBe(false)
    if (result.ok) throw new Error('expected failure')
    expect(fn).not.toHaveBeenCalled()
    expect(createdBlobUrls).toHaveLength(0)
  })

  it('空 URL → 直接失败', async () => {
    const store = createStore('valid-token')
    const { fn } = stubFetch(() => okResponse())

    const result = await store.fetchProofImageBlob('')

    expect(result.ok).toBe(false)
    expect(fn).not.toHaveBeenCalled()
  })
})

describe('PrivateImage.vue - 凭证加载与失败态', () => {
  it('认证成功 → 渲染 Blob URL 图片，不使用原始 URL', async () => {
    createStore('valid-token')
    const { calls } = stubFetch(() => okResponse())

    const wrapper = mountPrivateImage(PROOF_URL)
    await flushPromises()

    const img = wrapper.find('img.private-image-img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('blob:mock-1')
    expect(img.attributes('src')).not.toBe(PROOF_URL)
    expect(wrapper.find('[data-testid="private-image-error"]').exists()).toBe(false)
    expect(calls[0].init?.headers?.Authorization).toBe('Bearer valid-token')
  })

  it('403 无权限 → 展示失败态，绝不渲染指向原始 URL 的 <img>', async () => {
    createStore('valid-token')
    stubFetch(() => errorResponse(403))

    const wrapper = mountPrivateImage(PROOF_URL)
    await flushPromises()

    const placeholder = wrapper.find('[data-testid="private-image-error"]')
    expect(placeholder.exists()).toBe(true)
    expect(placeholder.text()).toContain('权限')
    expect(wrapper.text()).not.toContain(PROOF_URL)
    // 页面上不允许出现任何指向未鉴权原始地址的图片
    expect(wrapper.find(`img[src="${PROOF_URL}"]`).exists()).toBe(false)
    expect(wrapper.findAll('img')).toHaveLength(0)
  })

  it('未登录 → 展示失败态且不发出请求', async () => {
    createStore('')
    const { fn } = stubFetch(() => okResponse())

    const wrapper = mountPrivateImage(PROOF_URL)
    await flushPromises()

    expect(wrapper.find('[data-testid="private-image-error"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('登录')
    expect(fn).not.toHaveBeenCalled()
  })

  it('网络失败 → 展示失败态', async () => {
    createStore('valid-token')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    const wrapper = mountPrivateImage(PROOF_URL)
    await flushPromises()

    expect(wrapper.find('[data-testid="private-image-error"]').exists()).toBe(true)
    expect(wrapper.findAll('img')).toHaveLength(0)
  })

  it('src 切换 → 释放旧 Blob URL 并加载新凭证', async () => {
    createStore('valid-token')
    let call = 0
    stubFetch(() => {
      call += 1
      return okResponse()
    })

    const wrapper = mountPrivateImage(PROOF_URL)
    await flushPromises()
    expect(wrapper.find('img.private-image-img').attributes('src')).toBe('blob:mock-1')

    await wrapper.setProps({ src: OTHER_PROOF_URL })
    await flushPromises()

    expect(revokedBlobUrls).toContain('blob:mock-1')
    expect(wrapper.find('img.private-image-img').attributes('src')).toBe('blob:mock-2')
    expect(call).toBe(2)
  })

  it('快速切换 src（异步完成顺序变化）→ 只应用最新结果，并释放过期 Blob URL', async () => {
    createStore('valid-token')
    // 第一张图延迟返回，第二张图立即返回：模拟完成顺序与请求顺序不一致
    const resolvers: Array<() => void> = []
    vi.stubGlobal('fetch', vi.fn((input: string) => {
      if (input === PROOF_URL) {
        return new Promise<Response>((resolve) => {
          resolvers.push(() => resolve(okResponse()))
        })
      }
      return Promise.resolve(okResponse())
    }))

    const wrapper = mountPrivateImage(PROOF_URL)
    await flushPromises()
    expect(createdBlobUrls).toHaveLength(0)

    await wrapper.setProps({ src: OTHER_PROOF_URL })
    await flushPromises()
    // 第二张图已成功，此时显示的应是第二张
    expect(wrapper.find('img.private-image-img').attributes('src')).toBe('blob:mock-1')
    const latestUrl = 'blob:mock-1'

    // 第一张图的迟到响应到达：结果必须被丢弃，其 Blob URL 必须被释放
    resolvers.forEach(r => r())
    await flushPromises()

    expect(wrapper.find('img.private-image-img').attributes('src')).toBe(latestUrl)
    expect(revokedBlobUrls).toContain('blob:mock-2')
    expect(createdBlobUrls).toHaveLength(2)
  })

  it('组件卸载 → 释放已创建的 Blob URL', async () => {
    createStore('valid-token')
    stubFetch(() => okResponse())

    const wrapper = mountPrivateImage(PROOF_URL)
    await flushPromises()
    expect(createdBlobUrls).toHaveLength(1)

    wrapper.unmount()

    expect(revokedBlobUrls).toContain('blob:mock-1')
  })
})

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

import OrderDetailView from '@/views/OrderDetailView.vue'
import { useRealCampusHubStore } from '@/stores/campusHub'

const DEMAND_ID = 'd1'
const PROOF_URLS = [
  '/api/v1/uploads/2026/10/proof-a.png',
  '/api/v1/uploads/2026/10/proof-b.png'
]

const { mockStore } = vi.hoisted(() => ({
  mockStore: { value: null as any }
}))

vi.mock('@/stores/campusHub', async () => {
  // 视图用的 mock store 只提供业务数据；凭证加载走真实 store 实现，保证认证逻辑被真正执行
  const actual = await vi.importActual<typeof import('@/stores/campusHub')>('@/stores/campusHub')
  return {
    ...actual,
    useRealCampusHubStore: actual.useCampusHubStore,
    useCampusHubStore: () => mockStore.value
  }
})

vi.mock('@/composables/useDialog', () => ({
  useConfirm: () => Promise.resolve(true),
  useAlert: () => Promise.resolve()
}))

vi.mock('@/components/SkeletonCard.vue', () => ({
  default: { name: 'SkeletonCard', template: '<div class="skeleton-stub"></div>' }
}))

function buildDemand() {
  return {
    id: DEMAND_ID,
    title: '组队打篮球',
    description: '描述',
    category: 'TEAM_UP',
    campusZone: 'GULOU',
    location: '北区操场',
    startTime: '2026-11-01T10:00',
    endTime: '2026-11-01T12:00',
    reward: 10,
    interactionMode: 'DIRECT_ACCEPT',
    targetParticipantCount: 1,
    status: 'PENDING',
    anonymous: false,
    anonymousCode: null,
    publisherId: 'publisher-1',
    publisherName: '发布者',
    publisherAvatar: '',
    tags: [],
    createdAt: '2026-10-01T00:00',
    updatedAt: '2026-10-01T00:00',
    images: [],
    ...({} as Record<string, unknown>)
  }
}

function buildOrder() {
  return {
    id: 'o1',
    demandId: DEMAND_ID,
    requesterId: 'publisher-1',
    serviceProviderId: 'provider-1',
    serviceProviderName: '接单方',
    serviceProviderStudentId: 'P001',
    serviceProviderCreditScore: 100,
    serviceProviderAvatar: '',
    status: 'COMPLETED',
    reward: 10,
    note: '',
    createdAt: '2026-10-02T00:00',
    updatedAt: '2026-10-02T00:00',
    proofImageCount: PROOF_URLS.length,
    proofImageUrls: PROOF_URLS,
    demandImages: [],
    reviews: [],
    timeline: [],
    arbitrationSubmissions: [],
    currentUserReviewed: false
  }
}

function createViewStore(realStore: any) {
  const demand = buildDemand()
  const order = buildOrder()
  return {
    currentUser: { id: 'publisher-1', role: 'USER', balance: 1000, frozenBalance: 0 },
    currentProfile: null,
    demands: [demand],
    orders: [order],
    reviews: [],
    demandResponses: [],
    notifications: [],
    getDemandById: () => demand,
    getOrderById: () => order,
    fetchOrderDetail: () => Promise.resolve(order),
    fetchDemandDetail: () => Promise.resolve(demand),
    fetchOrders: () => Promise.resolve(),
    fetchOrderByDemandId: () => Promise.resolve(order),
    fetchResponses: () => Promise.resolve([]),
    fetchUserReviews: () => Promise.resolve(),
    // 关键：复用真实 store 的认证加载逻辑
    fetchProofImageBlob: (url: string) => realStore.fetchProofImageBlob(url)
  }
}

function mountOrderDetailView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/orders/:id', component: OrderDetailView },
      { path: '/', component: { template: '<div></div>' } }
    ]
  })
  return mount(OrderDetailView, { global: { plugins: [router] } })
}

/** 全屏预览通过 Teleport 挂到 body，需要直接查询 document */
function viewerElement(): HTMLElement | null {
  return document.querySelector('.viewer-backdrop')
}

function stubFetch(handler: () => Response) {
  const fn = vi.fn(async () => handler())
  vi.stubGlobal('fetch', fn)
  return fn
}

beforeEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
  document.body.innerHTML = ''
  localStorage.setItem('campushub.token', 'valid-token')
  // 真实 store 需要在已激活的 pinia 下创建，且必须早于 mount（mount 不再重建 pinia）
  setActivePinia(createPinia())
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => 'blob:viewer-1')
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
  vi.spyOn(window.location, 'assign').mockImplementation(() => {})
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

describe('OrderDetailView - 私密凭证全屏预览', () => {
  it('打开预览时不会把未鉴权的原始 URL 交给 <img>', async () => {
    const realStore = useRealCampusHubStore()
    mockStore.value = createViewStore(realStore)
    stubFetch(() => new Response('binary-image-bytes', { status: 200 }))

    const wrapper = mountOrderDetailView()
    await flushPromises()
    await flushPromises()

    const thumb = wrapper.find('.private-image')
    expect(thumb.exists()).toBe(true)
    await thumb.trigger('click')

    // 点击后、异步加载完成前：预览已打开，但绝不能出现指向原始 URL 的图片
    expect(viewerElement()).not.toBeNull()
    const rawImgs = Array.from(document.body.querySelectorAll('img')).filter(
      img => PROOF_URLS.includes(img.getAttribute('src') || '')
    )
    expect(rawImgs).toHaveLength(0)

    await flushPromises()

    // 加载完成后显示 Blob URL
    const viewerImg = document.querySelector('.viewer-image') as HTMLImageElement | null
    expect(viewerImg).not.toBeNull()
    expect(viewerImg!.getAttribute('src')).toBe('blob:viewer-1')
  })

  it('凭证鉴权失败（403）→ 全屏预览展示明确失败状态，不回退原始 URL', async () => {
    const realStore = useRealCampusHubStore()
    mockStore.value = createViewStore(realStore)
    stubFetch(() => new Response(null, { status: 403 }))

    const wrapper = mountOrderDetailView()
    await flushPromises()
    await flushPromises()

    await wrapper.find('.private-image').trigger('click')
    await flushPromises()
    await flushPromises()

    const placeholder = document.querySelector('[data-testid="viewer-image-error"]')
    expect(placeholder).not.toBeNull()
    expect(placeholder!.textContent).toContain('权限')
    expect(document.querySelector('.viewer-image')).toBeNull()

    const rawImgs = Array.from(document.body.querySelectorAll('img')).filter(
      img => PROOF_URLS.includes(img.getAttribute('src') || '')
    )
    expect(rawImgs).toHaveLength(0)
  })

  it('未登录 → 预览展示失败状态且不发出任何请求', async () => {
    localStorage.removeItem('campushub.token')
    const realStore = useRealCampusHubStore()
    mockStore.value = createViewStore(realStore)
    const fetchMock = stubFetch(() => new Response('binary', { status: 200 }))

    const wrapper = mountOrderDetailView()
    await flushPromises()
    await flushPromises()

    await wrapper.find('.private-image').trigger('click')
    await flushPromises()
    await flushPromises()

    const placeholder = document.querySelector('[data-testid="viewer-image-error"]')
    expect(placeholder).not.toBeNull()
    expect(placeholder!.textContent).toContain('登录')
    expect(fetchMock).not.toHaveBeenCalled()
    expect(document.querySelector('.viewer-image')).toBeNull()
  })
})

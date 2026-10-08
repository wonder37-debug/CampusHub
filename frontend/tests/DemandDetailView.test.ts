import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

import DemandDetailView from '@/views/DemandDetailView.vue'

const { mockStore } = vi.hoisted(() => ({
  mockStore: { value: null as any }
}))

vi.mock('@/stores/campusHub', () => ({
  useCampusHubStore: () => mockStore.value
}))

vi.mock('@/composables/useDialog', () => ({
  useConfirm: () => Promise.resolve(true),
  useAlert: () => Promise.resolve()
}))

vi.mock('@/components/SkeletonCard.vue', () => ({
  default: { name: 'SkeletonCard', template: '<div class="skeleton-stub"></div>' }
}))

vi.mock('@/components/ImageViewer.vue', () => ({
  default: { name: 'ImageViewer', template: '<div class="image-viewer-stub"></div>' }
}))

function buildDemand(overrides: Partial<any> = {}) {
  return {
    id: 'd1',
    title: '组队打篮球',
    description: '描述',
    category: 'TEAM_UP',
    campusZone: 'GULOU',
    location: '北区操场',
    startTime: '2026-11-01T10:00',
    endTime: '2026-11-01T12:00',
    reward: 10,
    interactionMode: 'SELECT_MANY',
    targetParticipantCount: 3,
    status: 'PENDING',
    anonymous: false,
    anonymousCode: null,
    publisherId: 'publisher-1',
    publisherName: '发布者',
    publisherAvatar: '',
    tags: [],
    createdAt: '2026-10-01T00:00',
    updatedAt: '2026-10-01T00:00',
    distanceKm: 0,
    ...overrides
  }
}

function buildResponse(overrides: Partial<any> = {}) {
  return {
    id: 'resp-1',
    demandId: 'd1',
    authorId: 'current-user',
    authorName: '我',
    content: '我报名',
    status: 'PENDING',
    createdAt: '2026-10-02T00:00',
    updatedAt: '2026-10-02T00:00',
    ...overrides
  }
}

function createMockStore(options: {
  demand?: any
  responses?: any[]
  currentUserId?: string
  createDemandResponse?: (demandId: string, content: string) => Promise<any>
} = {}) {
  const demand = options.demand ?? buildDemand()
  const responses = options.responses ?? []
  return {
    currentUser: { id: options.currentUserId ?? 'current-user', role: 'USER', balance: 1000, frozenBalance: 0 },
    demands: [demand],
    orders: [],
    reviews: [],
    demandResponses: responses,
    notifications: [],
    getDemandById: () => demand,
    fetchDemandDetail: () => Promise.resolve(demand),
    fetchOrders: () => Promise.resolve(),
    fetchOrderByDemandId: () => Promise.resolve(null),
    fetchResponses: () => {
      // 模拟后端返回当前 responses
      return Promise.resolve(responses)
    },
    fetchUserReviews: () => Promise.resolve(),
    createResponse: options.createDemandResponse ?? ((demandId: string, content: string) =>
      Promise.resolve(buildResponse({ content }))),
    withdrawResponse: () => Promise.resolve(buildResponse({ status: 'WITHDRAWN' }))
  }
}

function mountDetailView() {
  setActivePinia(createPinia())
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/demands/:id', component: DemandDetailView },
      { path: '/', component: { template: '<div></div>' } }
    ]
  })
  return mount(DemandDetailView, {
    global: { plugins: [router] }
  })
}

describe('DemandDetailView - 重复留言/报名 UX', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('用户已经有 active Response → 不显示重复留言/报名表单', async () => {
    mockStore.value = createMockStore({
      responses: [buildResponse({ status: 'PENDING' })]
    })
    const wrapper = mountDetailView()
    // 触发 onMounted 中的数据加载
    await flushPromises()
    await flushPromises()

    // 不应出现创建留言的 textarea（placeholder 含"报名"）
    const textareas = wrapper.findAll('textarea')
    const responseTextarea = textareas.filter((t: any) => /报名/.test(t.attributes('placeholder') || ''))
    expect(responseTextarea.length).toBe(0)

    // 应出现状态卡片，提示"已报名"
    expect(wrapper.text()).toContain('已报名')
    expect(wrapper.text()).toContain('你已经报名过该组队需求')
  })

  it('用户已经有 SELECTED Response → 显示已选中状态，无撤回按钮', async () => {
    mockStore.value = createMockStore({
      responses: [buildResponse({ status: 'SELECTED' })]
    })
    const wrapper = mountDetailView()
    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('已报名')
    expect(wrapper.text()).toContain('已被选中')
    // SELECTED 已进入履约，不能撤回
    expect(wrapper.text()).toContain('已进入履约，无法撤回')
  })

  it('Response 提交过程中 → 提交按钮禁用', async () => {
    // createResponse 返回一个永不 resolve 的 Promise，让 responseSubmitting 保持 true
    let resolveFn: () => void
    const pendingPromise = new Promise<any>((resolve) => { resolveFn = resolve })
    mockStore.value = createMockStore({
      responses: [],
      createDemandResponse: () => pendingPromise
    })
    const wrapper = mountDetailView()
    await flushPromises()
    await flushPromises()

    // 此时无 active response，应显示提交表单
    const textarea = wrapper.find('textarea')
    expect(textarea.exists()).toBe(true)
    await textarea.setValue('我报名参加')
    await flushPromises()

    const submitBtn = wrapper.find('button[data-testid="submit-response"]')
    expect(submitBtn.exists()).toBe(true)
    await submitBtn.trigger('click')
    await flushPromises()

    // 提交过程中按钮应禁用，且文案变为"提交中..."
    const btnAfter = wrapper.find('button[data-testid="submit-response"]')
    expect(btnAfter.attributes('disabled')).toBeDefined()
    expect(btnAfter.text()).toContain('提交中')

    // 释放 pending promise 避免泄露
    resolveFn!()
    await flushPromises()
  })

  it('HELP 模式下用户已有 active Response → 仍可继续提交新回答', async () => {
    mockStore.value = createMockStore({
      demand: buildDemand({ interactionMode: 'HELP', category: 'HELP' }),
      responses: [buildResponse({ status: 'PENDING' })]
    })
    const wrapper = mountDetailView()
    await flushPromises()
    await flushPromises()

    // HELP 模式下即使已有 active Response，仍应显示提交入口（textarea placeholder 含"回答"）
    const textareas = wrapper.findAll('textarea')
    const responseTextarea = textareas.filter((t: any) => /回答/.test(t.attributes('placeholder') || ''))
    expect(responseTextarea.length).toBeGreaterThan(0)

    // 同时应显示已提交回答的状态卡片
    expect(wrapper.text()).toContain('已提交回答')
  })
})

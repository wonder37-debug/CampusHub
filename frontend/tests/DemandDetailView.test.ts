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
  fetchDemandDetail?: () => Promise<any>
  createDemandResponse?: (demandId: string, content: string) => Promise<any>
  withdrawDemand?: (demandId: string) => Promise<any>
} = {}) {
  const demand = options.demand ?? buildDemand()
  const responses = options.responses ?? []
  return {
    currentUser: { id: options.currentUserId ?? 'current-user', role: 'USER', balance: 1000, frozenBalance: 0 },
    demands: [demand],
    orders: [],
    reviews: [],
    demandResponses: responses,
    demandResponsesDemandId: '',
    notifications: [],
    getDemandById: function (demandId: string) {
      return this.demands.find((item: any) => item.id === demandId)
    },
    fetchDemandDetail: options.fetchDemandDetail ?? function (demandId: string) {
      return Promise.resolve(this.demands.find((item: any) => item.id === demandId) ?? null)
    },
    withdrawDemand: options.withdrawDemand ?? (() => Promise.resolve({ ...demand, status: 'CANCELLED' })),
    fetchOrders: () => Promise.resolve(),
    fetchOrderByDemandId: () => Promise.resolve(null),
    fetchResponses: function (demandId: string) {
      // 模拟后端返回当前 responses
      this.demandResponsesDemandId = demandId
      this.demandResponses = responses
      return Promise.resolve(responses)
    },
    fetchUserReviews: () => Promise.resolve(),
    createResponse: options.createDemandResponse ?? ((demandId: string, content: string) =>
      Promise.resolve(buildResponse({ content }))),
    withdrawResponse: () => Promise.resolve(buildResponse({ status: 'WITHDRAWN' }))
  }
}

async function mountDetailView() {
  setActivePinia(createPinia())
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/demands/:id', component: DemandDetailView },
      { path: '/', component: { template: '<div></div>' } }
    ]
  })
  await router.push('/demands/d1')
  await router.isReady()
  const wrapper = mount(DemandDetailView, {
    global: { plugins: [router] }
  })
  return { wrapper, router }
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
    const { wrapper } = await mountDetailView()
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
    const { wrapper } = await mountDetailView()
    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('已报名')
    expect(wrapper.text()).toContain('你已被选中')
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
    const { wrapper } = await mountDetailView()
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
    const { wrapper } = await mountDetailView()
    await flushPromises()
    await flushPromises()

    // HELP 模式下即使已有 active Response，仍应显示提交入口（textarea placeholder 含"回答"）
    const textareas = wrapper.findAll('textarea')
    const responseTextarea = textareas.filter((t: any) => /回答/.test(t.attributes('placeholder') || ''))
    expect(responseTextarea.length).toBeGreaterThan(0)

    // 同时应显示已提交回答的状态卡片
    expect(wrapper.text()).toContain('已提交回答')
  })

  it('发布者不会看到接单或响应自己的需求入口', async () => {
    mockStore.value = createMockStore({
      currentUserId: 'publisher-1',
      demand: buildDemand({ interactionMode: 'DIRECT_ACCEPT', canAccept: true })
    })
    const { wrapper } = await mountDetailView()
    await flushPromises()
    expect(wrapper.text()).not.toContain('立即接单')
    expect(wrapper.find('[data-testid="submit-response"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('这是您自己发布的需求')
  })

  it('发布者撤回后留在详情页并重新读取服务端状态', async () => {
    const demand = buildDemand({ interactionMode: 'DIRECT_ACCEPT', publisherId: 'publisher-1' })
    const fetchDemandDetail = vi.fn(async () => demand)
    const withdrawDemand = vi.fn(async () => {
      demand.status = 'CANCELLED'
      return demand
    })
    const store = createMockStore({ currentUserId: 'publisher-1', demand, fetchDemandDetail, withdrawDemand })
    mockStore.value = store
    const { wrapper } = await mountDetailView()
    await flushPromises()

    const withdraw = wrapper.findAll('button').find((button) => button.text() === '撤回需求')
    expect(withdraw).toBeDefined()
    await withdraw!.trigger('click')
    await flushPromises()

    expect(withdrawDemand).toHaveBeenCalledOnce()
    expect(fetchDemandDetail).toHaveBeenCalledTimes(2)
  })

  it('路由切换需求 ID 后重新加载详情和响应，不残留上一条内容', async () => {
    const firstResponse = buildResponse({ authorId: 'other-user', authorName: '旧响应者', content: '旧需求响应内容' })
    const store = createMockStore({ responses: [firstResponse] })
    const secondDemand = buildDemand({ id: 'd2', title: '微积分辅导', category: 'STUDY_HELP', interactionMode: 'SELECT_ONE' })
    store.demands.push(secondDemand)
    store.fetchDemandDetail = vi.fn(async (id: string) => store.demands.find((item: any) => item.id === id) ?? null)
    store.fetchResponses = vi.fn(async (id: string) => {
      store.demandResponsesDemandId = id
      const result = id === 'd1' ? [firstResponse] : []
      store.demandResponses = result
      return result
    })
    mockStore.value = store

    const { wrapper, router } = await mountDetailView()
    await flushPromises()
    expect(wrapper.text()).toContain('旧需求响应内容')

    await router.push('/demands/d2')
    await flushPromises()
    expect(store.fetchDemandDetail).toHaveBeenLastCalledWith('d2')
    expect(store.fetchResponses).toHaveBeenLastCalledWith('d2')
    expect(wrapper.text()).toContain('微积分辅导')
    expect(wrapper.text()).not.toContain('旧需求响应内容')
  })

  it('已完成需求为只读，不展示响应提交入口', async () => {
    mockStore.value = createMockStore({
      demand: buildDemand({ status: 'COMPLETED', interactionMode: 'HELP', category: 'HELP' })
    })
    const { wrapper } = await mountDetailView()
    await flushPromises()
    expect(wrapper.find('[data-testid="submit-response"]').exists()).toBe(false)
    expect(wrapper.findAll('button').some((button) => button.text() === '采纳')).toBe(false)
  })

  it.each([
    ['SELECT_MANY', 'TEAM_UP', '自动完成并结算'],
    ['HELP', 'HELP', '采纳回答后自动结算']
  ])('%s 需求按 Demand 流程完成，不展示正式订单入口', async (interactionMode, category, expectedCopy) => {
    mockStore.value = createMockStore({
      demand: buildDemand({ interactionMode, category })
    })
    const { wrapper } = await mountDetailView()
    await flushPromises()
    expect(wrapper.text()).toContain(expectedCopy)
    expect(wrapper.findAll('button').some((button) => button.text().includes('订单'))).toBe(false)
  })

  it('详情请求失败显示失败与重试，不伪装成空需求', async () => {
    mockStore.value = createMockStore({
      fetchDemandDetail: () => Promise.reject(new Error('network unavailable'))
    })
    const { wrapper } = await mountDetailView()
    await flushPromises()
    expect(wrapper.text()).toContain('需求详情加载失败')
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('暂无需求')
  })
})

describe('DemandDetailView - 分类型详情页布局', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('互助问答按论坛式展示：回答列表在问题下方，输入区有明确标签', async () => {
    mockStore.value = createMockStore({
      demand: buildDemand({ interactionMode: 'HELP', category: 'HELP' }),
      responses: [buildResponse({ id: 'r1', authorId: 'other-user', authorName: '回答者', content: '先复习极限定义' })]
    })
    const { wrapper } = await mountDetailView()
    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('全部回答')
    expect(wrapper.text()).toContain('先复习极限定义')
    // 输入区不再是只有 placeholder 的裸 textarea
    expect(wrapper.find('label[for="response-content"]').text()).toBe('写回答')
    expect(wrapper.find('[data-testid="submit-response"]').text()).toBe('提交回答')
    // 旧的“在此需求内回答与采纳”引导文案与符号已移除
    expect(wrapper.text()).not.toContain('在此需求内回答与采纳')
  })

  it('发布者在问答需求下可以对回答执行采纳', async () => {
    mockStore.value = createMockStore({
      currentUserId: 'publisher-1',
      demand: buildDemand({ interactionMode: 'HELP', category: 'HELP', publisherId: 'publisher-1' }),
      responses: [buildResponse({ id: 'r1', authorId: 'other-user', authorName: '回答者', content: '试试换元法' })]
    })
    const { wrapper } = await mountDetailView()
    await flushPromises()
    await flushPromises()

    const acceptButton = wrapper.findAll('button').find((button) => button.text() === '采纳')
    expect(acceptButton).toBeDefined()
  })

  it('二手交易突出价格与买家留言，而非通用响应文案', async () => {
    mockStore.value = createMockStore({
      demand: buildDemand({ category: 'SECOND_HAND', interactionMode: 'SELECT_ONE', reward: 45 })
    })
    const { wrapper } = await mountDetailView()
    await flushPromises()
    await flushPromises()

    expect(wrapper.find('.detail-rail-reward').text()).toBe('45 校邻币')
    expect(wrapper.text()).toContain('价格')
    expect(wrapper.text()).toContain('买家留言')
    expect(wrapper.text()).toContain('留言咨询')
  })

  it('组队需求突出报名成员与固定操作入口', async () => {
    mockStore.value = createMockStore({})
    const { wrapper } = await mountDetailView()
    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('报名成员')
    expect(wrapper.find('.detail-action-rail').exists()).toBe(true)

    const railCta = wrapper.findAll('button').find((button) => button.text() === '立即报名')
    expect(railCta).toBeDefined()

    // 固定操作入口把焦点送到输入框，键盘用户无需手动滚动查找
    const composer = wrapper.find('#response-content').element as HTMLTextAreaElement
    const focusSpy = vi.spyOn(composer, 'focus')
    await railCta!.trigger('click')
    expect(focusSpy).toHaveBeenCalled()
  })
})

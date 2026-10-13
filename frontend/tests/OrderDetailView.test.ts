import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { reactive } from 'vue'
import OrderDetailView from '@/views/OrderDetailView.vue'

const { mockStore } = vi.hoisted(() => ({ mockStore: { value: null as any } }))

vi.mock('@/stores/campusHub', () => ({ useCampusHubStore: () => mockStore.value }))
vi.mock('@/composables/useDialog', () => ({
  useConfirm: () => Promise.resolve(true),
  useAlert: () => Promise.resolve()
}))
vi.mock('@/components/SkeletonCard.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/ImageViewer.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/ImageUploader.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/PrivateImage.vue', () => ({ default: { template: '<div />' } }))

function createOrder(overrides: Record<string, unknown> = {}) {
  return {
    id: 'o1', demandId: 'd1', demandTitle: '代取快递', demandDescription: '南门取件',
    demandLocation: '南门驿站', demandStartTime: '2026-10-12T15:00:00', demandEndTime: '2026-10-12T17:00:00',
    demandCategory: 'EXPRESS', demandInteractionMode: 'DIRECT_ACCEPT', demandCampusZone: 'GULOU', demandReward: 30,
    requesterId: 'requester', requesterName: '发布者', requesterAvatar: '', requesterCreditScore: 100,
    serviceProviderId: 'provider', serviceProviderName: '接单者', serviceProviderAvatar: '', serviceProviderCreditScore: 100,
    status: 'ACCEPTED', note: null, proofSubmitted: false, proofImageCount: 0, proofImageUrls: [],
    createdAt: '2026-10-12T12:00:00', updatedAt: '2026-10-12T12:00:00', completedAt: '', timeline: [],
    reviews: [], currentUserReviewed: false, demandImages: [], demandContactInfo: null,
    arbitrationSubmissions: [], ...overrides
  }
}

async function mountOrder(currentUserId: string, interactionMode = 'DIRECT_ACCEPT') {
  const order = createOrder({ demandInteractionMode: interactionMode })
  const orderById = reactive<Record<string, any>>({ o1: order })
  const store = {
    currentUser: { id: currentUserId, role: 'USER' },
    reviews: [],
    orderById,
    getOrderById: (id: string) => orderById[id],
    fetchOrderDetail: vi.fn(async (id: string) => orderById[id] ?? null),
    fetchOrders: vi.fn(async () => undefined),
    cancelOrder: vi.fn(async () => order),
    startOrder: vi.fn(async () => order)
  }
  mockStore.value = store
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/orders/:id', component: OrderDetailView },
      { path: '/demands/:id', name: 'DemandDetail', component: { template: '<div />' } }
    ]
  })
  await router.push('/orders/o1')
  await router.isReady()
  const wrapper = mount(OrderDetailView, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router, store }
}

describe('OrderDetailView 角色操作与来源展示', () => {
  it('需求方不会看到取消订单；奖励以校邻币展示', async () => {
    const { wrapper } = await mountOrder('requester')
    expect(wrapper.text()).not.toContain('取消订单')
    expect(wrapper.text()).toContain('30 校邻币')
    expect(wrapper.text()).toContain('直接接单委托')
  })

  it('接单方可在 ACCEPTED 阶段看到取消和开始执行操作', async () => {
    const { wrapper } = await mountOrder('provider')
    expect(wrapper.text()).toContain('取消订单')
    expect(wrapper.text()).toContain('开始执行')
  })

  it('接单方提交完成确认后只提示等待需求方，不重复上传或确认', async () => {
    const timeline = [{
      operatorId: 'provider', note: 'PROVIDER_CONFIRMED_COMPLETION', label: 'IN_PROGRESS',
      at: '2026-10-12T13:00:00'
    }]
    const { wrapper } = await mountOrder('provider')
    mockStore.value.orderById.o1.status = 'IN_PROGRESS'
    mockStore.value.orderById.o1.timeline = timeline
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('正在等待需求方查看凭证并确认')
    expect(wrapper.findAll('button').some((button) => button.text() === '提交完成确认')).toBe(false)
    expect(wrapper.text()).not.toContain('完成凭证')
  })

  it('仅在接单方已确认后向需求方提供二次确认入口', async () => {
    const { wrapper } = await mountOrder('requester')
    const order = mockStore.value.orderById.o1
    order.status = 'IN_PROGRESS'
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('button').some((button) => button.text() === '确认完成')).toBe(false)
    order.timeline = [{
      operatorId: 'provider', note: 'PROVIDER_CONFIRMED_COMPLETION', label: 'IN_PROGRESS',
      at: '2026-10-12T13:00:00'
    }]
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('确认完成')
  })

  it('仲裁中显示补充说明入口，不重复提供发起仲裁操作', async () => {
    const { wrapper } = await mountOrder('provider')
    const order = mockStore.value.orderById.o1
    order.status = 'IN_ARBITRATION'
    order.canSubmitArbitrationResponse = true
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('补充说明')
    expect(wrapper.text()).not.toContain('发起仲裁')
  })

  it('已完成订单仅向尚未评价的订单双方显示评价表单', async () => {
    const { wrapper } = await mountOrder('requester')
    const order = mockStore.value.orderById.o1
    order.status = 'COMPLETED'
    await wrapper.vm.$nextTick()
    expect(wrapper.find('textarea[placeholder="分享你的体验"]').exists()).toBe(true)
    order.currentUserReviewed = true
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('您已提交评价')
    expect(wrapper.find('textarea[placeholder="分享你的体验"]').exists()).toBe(false)
  })

  it('只按后端结构化自动完成事件解释 48 小时规则', async () => {
    const { wrapper } = await mountOrder('requester')
    const order = mockStore.value.orderById.o1
    order.status = 'COMPLETED'
    order.timeline = [{
      operatorId: 'system', note: 'SYSTEM_AUTO_COMPLETED', label: 'COMPLETED',
      at: '2026-10-14T13:00:00'
    }]
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('48 小时内未收到需求方确认')
    expect(wrapper.text()).not.toContain('¥')
  })

  it('选择响应建立的订单显示对应来源并保留原需求入口', async () => {
    const { wrapper } = await mountOrder('provider', 'SELECT_ONE')
    expect(wrapper.text()).toContain('由需求响应进入履约')
    expect(wrapper.text()).toContain('查看原始需求')
  })

  it('路由切换后重新请求新 ID，旧订单标题不再显示', async () => {
    const { wrapper, router, store } = await mountOrder('provider')
    store.orderById.o2 = createOrder({ id: 'o2', demandTitle: '帮忙搬书' })
    await router.push('/orders/o2')
    await flushPromises()
    expect(store.fetchOrderDetail).toHaveBeenCalledWith('o2')
    expect(wrapper.text()).toContain('帮忙搬书')
    expect(wrapper.text()).not.toContain('代取快递')
  })

  it('查看原始需求跳转到公开 Demand 路由', async () => {
    const { wrapper, router } = await mountOrder('provider', 'SELECT_ONE')
    const sourceButton = wrapper.findAll('button').find((button) => button.text().includes('查看原始需求'))
    expect(sourceButton).toBeDefined()
    await sourceButton!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/demands/d1')
  })
})

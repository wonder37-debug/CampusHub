import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import UserDemandsView from '@/views/UserDemandsView.vue'

const { storeHolder, routeHolder, mockFetchDemands, mockPush, defaultFetchImpl } = vi.hoisted(() => {
  const storeHolder: any = { value: null }
  const routeHolder: any = { value: null }
  const defaultFetchImpl = async (query: any) => {
    const page = query?.page ?? 1
    const size = query?.size ?? 12
    const total = 30
    const start = (page - 1) * size
    const items = Array.from({ length: Math.min(size, total - start) }, (_, i) => ({
      id: `d-${start + i + 1}`,
      title: `需求 ${start + i + 1}`,
      status: 'PENDING'
    }))
    storeHolder.value.demands = items
    storeHolder.value.demandFetchTotal = total
    storeHolder.value.demandFetchError = null
  }
  const fetchDemands = vi.fn(defaultFetchImpl)
  return { storeHolder, routeHolder, mockFetchDemands: fetchDemands, mockPush: vi.fn(), defaultFetchImpl }
})

vi.mock('@/stores/campusHub', () => ({ useCampusHubStore: () => storeHolder.value }))
vi.mock('vue-router', () => ({
  useRoute: () => routeHolder.value,
  useRouter: () => ({ push: mockPush, back: vi.fn() })
}))
vi.mock('@/components/DemandHomeCard.vue', () => ({
  default: {
    name: 'DemandHomeCard',
    props: { demand: { type: Object, required: true } },
    template: '<div class="demand-card" :data-id="demand.id" />'
  }
}))
vi.mock('@/components/SkeletonCard.vue', () => ({ default: { name: 'SkeletonCard', template: '<div />' } }))

const mockStore = reactive({
  demands: [] as any[],
  demandFetchError: null as string | null,
  demandFetchTotal: 0,
  currentUser: { id: 'me' },
  fetchDemands: mockFetchDemands
})
const mockRoute = reactive({ params: { id: 'user-a' } })
storeHolder.value = mockStore
routeHolder.value = mockRoute

describe('UserDemandsView - 加载更多与切换用户', () => {
  let wrapper: VueWrapper | undefined

  beforeEach(() => {
    vi.clearAllMocks()
    // 恢复默认实现，防止上个测试的 mockImplementation 残留导致后续测试卡住
    mockFetchDemands.mockImplementation(defaultFetchImpl)
    mockStore.demands = []
    mockStore.demandFetchError = null
    mockStore.demandFetchTotal = 0
    mockRoute.params.id = 'user-a'
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    vi.restoreAllMocks()
  })

  it('加载更多使用 button 控件，连续点击只发一次请求', async () => {
    wrapper = mount(UserDemandsView)
    await flushPromises()
    const loadMoreBtn = wrapper.find('button.load-more-panel')
    expect(loadMoreBtn.exists()).toBe(true)
    expect(loadMoreBtn.element.tagName).toBe('BUTTON')

    loadMoreBtn.trigger('click')
    loadMoreBtn.trigger('click')
    await flushPromises()

    // 第一页 1 次 + 加载更多 1 次（第二次连续点击被 loadingMore 拦截）
    expect(mockFetchDemands).toHaveBeenCalledTimes(2)
    const secondCall = mockFetchDemands.mock.calls[1][0]
    expect(secondCall.page).toBe(2)
  })

  it('加载完毕后隐藏加载更多按钮并展示全部提示', async () => {
    mockFetchDemands.mockImplementationOnce(async () => {
      mockStore.demands = Array.from({ length: 12 }, (_, i) => ({ id: `d-${i + 1}`, title: `需求 ${i + 1}`, status: 'PENDING' }))
      mockStore.demandFetchTotal = 12
      mockStore.demandFetchError = null
    })
    wrapper = mount(UserDemandsView)
    await flushPromises()
    expect(wrapper.find('button.load-more-panel').exists()).toBe(false)
    expect(wrapper.find('.load-more-done').exists()).toBe(true)
  })

  it('切换用户 ID 后清理旧列表并重新加载', async () => {
    wrapper = mount(UserDemandsView)
    await flushPromises()
    vi.clearAllMocks()
    mockStore.demands = [{ id: 'a-1', title: 'A需求', status: 'PENDING' }]
    mockStore.demandFetchTotal = 1

    mockRoute.params.id = 'user-b'
    await flushPromises()

    expect(mockFetchDemands).toHaveBeenCalledTimes(1)
    const call = mockFetchDemands.mock.calls[0][0]
    expect(call.publisherId).toBe('user-b')
    expect(call.page).toBe(1)
  })

  it('切换用户后旧 loadMore 请求返回不得污染新用户列表（无旧数据、无重复）', async () => {
    // 用 pending Promise 模拟可控的异步返回顺序
    const resolvers: Array<() => void> = []
    mockFetchDemands.mockImplementation(() => new Promise<void>((resolve) => resolvers.push(resolve)))

    wrapper = mount(UserDemandsView)
    // 调用0: loadDemands(A, page1) pending
    // 设置 A 第一页数据并 resolve
    mockStore.demands = Array.from({ length: 12 }, (_, i) => ({ id: `a-${i + 1}`, title: `A${i + 1}`, status: 'PENDING' }))
    mockStore.demandFetchTotal = 30
    mockStore.demandFetchError = null
    resolvers[0]()
    await flushPromises()
    expect(wrapper.findAll('.demand-card')).toHaveLength(12)

    // 触发 loadMore（A page2）→ 调用1 pending
    await wrapper.find('button.load-more-panel').trigger('click')
    await flushPromises()

    // 切换到用户 B：watch 清空列表并发起 loadDemands(B) → 调用2 pending
    mockRoute.params.id = 'user-b'
    await flushPromises()

    // 先完成 B 的第一页请求（调用2）
    mockStore.demands = Array.from({ length: 12 }, (_, i) => ({ id: `b-${i + 1}`, title: `B${i + 1}`, status: 'PENDING' }))
    mockStore.demandFetchTotal = 30
    mockStore.demandFetchError = null
    resolvers[2]()
    await flushPromises()

    // 再完成 A 的旧 loadMore 请求（调用1）：模拟 store 序列号丢弃，不写入 store.demands（保持 B 数据）
    resolvers[1]()
    await flushPromises()

    // 最终列表必须是 B 的 12 条，无 A 数据、无重复
    const cards = wrapper.findAll('.demand-card')
    expect(cards).toHaveLength(12)
    const ids = cards.map((c) => c.attributes('data-id'))
    expect(ids.every((id) => typeof id === 'string' && id.startsWith('b-'))).toBe(true)
  })
})

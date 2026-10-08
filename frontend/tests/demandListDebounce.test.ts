import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

const { mockStore } = vi.hoisted(() => ({
  mockStore: { value: null as any }
}))

vi.mock('@/stores/campusHub', () => ({
  useCampusHubStore: () => mockStore.value
}))

vi.mock('@/utils/format', () => ({
  campusZoneOptions: () => [],
  formatCampusZone: () => '',
  formatDemandCategory: () => '',
  formatDemandStatus: () => '',
  formatMoney: (v: number) => String(v),
  formatRelativeTime: () => '',
  formatScore: () => '',
  statusToneClass: () => '',
  truncateText: (v: string) => v,
  formatDateTime: () => ''
}))

vi.mock('@/types/campushub', () => ({
  DEMAND_CATEGORY_OPTIONS: []
}))

vi.mock('@/components/SkeletonCard.vue', () => ({
  default: { name: 'SkeletonCard', template: '<div class="skeleton-stub"></div>' }
}))

import DemandListView from '@/views/DemandListView.vue'

function createMockStore() {
  return {
    currentUser: { id: '1', role: 'USER' },
    demands: [],
    fetchDemands: vi.fn().mockResolvedValue(undefined),
    fetchProfile: vi.fn().mockResolvedValue(undefined)
  }
}

function mountListView() {
  setActivePinia(createPinia())
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/demands', component: DemandListView }]
  })
  return mount(DemandListView, { global: { plugins: [router] } })
}

describe('DemandListView debounce 竞争', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('搜索输入后立即刷新只产生一次最终 fetch（debounce timer 被清除）', async () => {
    vi.useFakeTimers()
    mockStore.value = createMockStore()
    const wrapper = mountListView()
    await flushPromises()

    mockStore.value.fetchDemands.mockClear()

    const searchInput = wrapper.find('#demand-q')
    await searchInput.setValue('快递')
    await flushPromises()

    expect(mockStore.value.fetchDemands).not.toHaveBeenCalled()

    const refreshBtn = wrapper.find('.refresh-button')
    await refreshBtn.trigger('click')
    await flushPromises()

    expect(mockStore.value.fetchDemands).toHaveBeenCalledTimes(1)

    vi.advanceTimersByTime(400)
    await flushPromises()

    expect(mockStore.value.fetchDemands).toHaveBeenCalledTimes(1)

    vi.useRealTimers()
  })

  it('组件卸载时 timer 被清理', async () => {
    vi.useFakeTimers()
    mockStore.value = createMockStore()
    const wrapper = mountListView()
    await flushPromises()

    mockStore.value.fetchDemands.mockClear()

    const searchInput = wrapper.find('#demand-q')
    await searchInput.setValue('测试')
    await flushPromises()

    wrapper.unmount()

    vi.advanceTimersByTime(400)
    await flushPromises()

    expect(mockStore.value.fetchDemands).not.toHaveBeenCalled()

    vi.useRealTimers()
  })
})

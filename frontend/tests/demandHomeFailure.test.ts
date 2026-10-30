import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { reactive } from 'vue'

const { mockStore } = vi.hoisted(() => ({ mockStore: { value: null as any } }))

vi.mock('@/stores/campusHub', () => ({
  useCampusHubStore: () => mockStore.value
}))

vi.mock('@/components/SkeletonCard.vue', () => ({
  default: { template: '<div class="skeleton-stub" />' }
}))

import DemandListView from '@/views/DemandListView.vue'

describe('首页需求列表错误状态', () => {
  beforeEach(() => {
    let attempt = 0
    mockStore.value = reactive({
      currentUser: null,
      demands: [{
        id: 'demand-1', title: '正常展示的需求', description: '推荐服务不可用时仍能浏览',
        category: 'EXPRESS', campusZone: 'GULOU', location: '图书馆', status: 'PENDING', reward: 0,
        interactionMode: 'DIRECT_ACCEPT', targetParticipantCount: null, selectedParticipantCount: null,
        anonymous: false, anonymousCode: null, publisherId: 'publisher-1', publisherName: '发布者', publisherAvatar: '',
        createdAt: '2026-10-01T10:00:00', updatedAt: '2026-10-01T10:00:00', startTime: '2026-10-02T10:00:00',
        endTime: '2026-10-02T12:00:00', tags: [], images: [], canAccept: false
      }],
      demandFetchError: null,
      fetchDemands: vi.fn().mockImplementation(async () => {
        attempt++
        mockStore.value.demandFetchError = attempt === 1 ? '需求加载失败，请重试。' : null
      }),
      fetchRecommendations: vi.fn().mockResolvedValue([])
    })
  })

  it('请求失败显示可重试的错误，不显示空列表文案', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/demands', component: DemandListView }]
    })
    const wrapper = mount(DemandListView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('需求加载失败')
    expect(wrapper.text()).not.toContain('暂无需求')

    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(mockStore.value.fetchDemands).toHaveBeenCalledTimes(2)
  })

  it('推荐接口失败不遮蔽已加载的需求流，仅显示非阻塞降级提示', async () => {
    mockStore.value.currentUser = { id: 'viewer-1', role: 'USER' }
    mockStore.value.fetchDemands.mockImplementation(async () => {
      mockStore.value.demandFetchError = null
    })
    mockStore.value.fetchRecommendations.mockRejectedValue(new Error('recommendation unavailable'))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/demands', component: DemandListView }]
    })
    const wrapper = mount(DemandListView, { global: { plugins: [router] } })
    await flushPromises()

    // 降级提示可见（不静默回退）
    const banner = wrapper.get('.home-recommend-fallback')
    expect(banner.text()).toContain('推荐排序暂时不可用')
    // 推荐接口失败不得用整页 ErrorState 阻断已加载成功的需求数据
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })
})

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'

const { storeHolder, routeHolder } = vi.hoisted(() => ({
  storeHolder: { value: null as any },
  routeHolder: { value: null as any }
}))

vi.mock('@/stores/campusHub', () => ({ useCampusHubStore: () => storeHolder.value }))
vi.mock('vue-router', () => ({
  useRoute: () => routeHolder.value,
  useRouter: () => ({ push: vi.fn() })
}))
vi.mock('@/components/DemandHomeCard.vue', () => ({
  default: {
    name: 'DemandHomeCard',
    props: { demand: { type: Object, required: true } },
    template: '<div class="demand-card" :data-id="demand.id" />'
  }
}))
vi.mock('@/components/SkeletonCard.vue', () => ({ default: { template: '<div />' } }))

import UserDemandsView from '@/views/UserDemandsView.vue'

function buildDemand(id: string, anonymous: boolean) {
  return {
    id,
    title: `需求 ${id}`,
    description: '描述',
    category: 'OTHER',
    campusZone: 'XIANLIN',
    location: '图书馆',
    startTime: '',
    endTime: '',
    reward: 0,
    interactionMode: 'DIRECT_ACCEPT',
    targetParticipantCount: null,
    selectedParticipantCount: null,
    status: 'PENDING',
    anonymous,
    anonymousCode: anonymous ? '匿名校友0001' : null,
    publisherId: 'p1',
    publisherName: anonymous ? '匿名校友0001' : '小林',
    publisherAvatar: '',
    tags: [],
    createdAt: '2026-10-01T10:00:00',
    updatedAt: '2026-10-01T10:00:00',
    images: []
  }
}

const demands = [buildDemand('public-1', false), buildDemand('anonymous-1', true)]

function createStore(currentUserId: string | null) {
  return reactive({
    currentUser: currentUserId ? { id: currentUserId, role: 'USER' } : null,
    demands: [] as any[],
    demandFetchTotal: 0,
    demandFetchError: null,
    fetchDemands: vi.fn(async () => {
      storeHolder.value.demands = demands
      storeHolder.value.demandFetchTotal = demands.length
      storeHolder.value.demandFetchError = null
    })
  })
}

describe('用户发布需求页 - 匿名需求可见性与紧凑布局', () => {
  let wrapper: VueWrapper | undefined

  beforeEach(() => {
    routeHolder.value = reactive({ params: { id: 'p1' } })
  })

  function mountView() {
    wrapper = mount(UserDemandsView)
    return wrapper
  }

  it('非发布者本人看不到匿名需求', async () => {
    storeHolder.value = createStore('u2')
    const view = mountView()
    await flushPromises()

    const ids = view.findAll('.demand-card').map((card) => card.attributes('data-id'))
    expect(ids).toEqual(['public-1'])
  })

  it('发布者本人可以看到自己的匿名需求', async () => {
    storeHolder.value = createStore('p1')
    const view = mountView()
    await flushPromises()

    const ids = view.findAll('.demand-card').map((card) => card.attributes('data-id'))
    expect(ids).toEqual(['public-1', 'anonymous-1'])
  })

  it('使用紧凑网格，一屏可展示更多卡片', async () => {
    storeHolder.value = createStore('u2')
    const view = mountView()
    await flushPromises()

    expect(view.find('.home-grid--dense').exists()).toBe(true)
  })
})

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import UserProfileView from '@/views/UserProfileView.vue'

const { storeHolder, routeHolder, mockFetchProfile, mockFetchReviews, mockFetchStats, mockPush } = vi.hoisted(() => {
  const storeHolder: any = { value: null }
  const routeHolder: any = { value: null }
  return {
    storeHolder,
    routeHolder,
    mockFetchProfile: vi.fn(async (id: string) => {
      storeHolder.value.publicUserProfile = { id, nickname: '用户' + id, avatarUrl: '', role: 'USER', status: 'ACTIVE', creditScore: 80 }
    }),
    mockFetchReviews: vi.fn(async () => {}),
    mockFetchStats: vi.fn(async (id: string) => {
      storeHolder.value.publicUserStats = { completedOrders: 5 }
    }),
    mockPush: vi.fn()
  }
})

vi.mock('@/stores/campusHub', () => ({ useCampusHubStore: () => storeHolder.value }))
vi.mock('vue-router', () => ({
  useRoute: () => routeHolder.value,
  useRouter: () => ({ push: mockPush, back: vi.fn() })
}))
vi.mock('@/components/SkeletonCard.vue', () => ({ default: { name: 'SkeletonCard', template: '<div />' } }))

const mockStore = reactive({
  publicUserProfile: null as any,
  publicUserReviews: [] as any[],
  publicUserReviewsTotal: 0,
  publicUserReviewsError: null as string | null,
  publicUserProfileError: null as string | null,
  publicUserStats: null as any,
  publicUserStatsError: null as string | null,
  currentUser: { id: 'me' },
  fetchPublicUserProfile: mockFetchProfile,
  fetchPublicUserReviews: mockFetchReviews,
  fetchPublicUserStats: mockFetchStats
})
const mockRoute = reactive({ params: { id: 'user-a' } })
storeHolder.value = mockStore
routeHolder.value = mockRoute

describe('UserProfileView - 切换用户 ID 监听', () => {
  let wrapper: VueWrapper | undefined

  beforeEach(() => {
    vi.clearAllMocks()
    mockStore.publicUserProfile = null
    mockStore.publicUserReviews = []
    mockStore.publicUserReviewsTotal = 0
    mockStore.publicUserStats = null
    mockStore.publicUserProfileError = null
    mockStore.publicUserReviewsError = null
    mockStore.publicUserStatsError = null
    mockRoute.params.id = 'user-a'
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    vi.restoreAllMocks()
  })

  it('挂载时按当前 ID 加载资料、评价与统计', async () => {
    wrapper = mount(UserProfileView)
    await flushPromises()
    expect(mockFetchProfile).toHaveBeenCalledWith('user-a')
    expect(mockFetchReviews).toHaveBeenCalledWith('user-a', 1, 5)
    expect(mockFetchStats).toHaveBeenCalledWith('user-a')
  })

  it('切换用户 ID 后清理旧数据并按新 ID 重新加载', async () => {
    mockStore.publicUserProfile = { id: 'user-a', nickname: '用户A', avatarUrl: '', role: 'USER', status: 'ACTIVE', creditScore: 70 }
    mockStore.publicUserReviews = [{ id: 'r-a1' }] as any
    mockStore.publicUserReviewsTotal = 1
    mockStore.publicUserStats = { completedOrders: 3 }

    wrapper = mount(UserProfileView)
    await flushPromises()
    vi.clearAllMocks()

    mockRoute.params.id = 'user-b'
    await flushPromises()

    // 按新 ID 重新加载（watch 触发了 loadProfile）
    expect(mockFetchProfile).toHaveBeenCalledWith('user-b')
    expect(mockFetchReviews).toHaveBeenCalledWith('user-b', 1, 5)
    expect(mockFetchStats).toHaveBeenCalledWith('user-b')
    // 旧用户数据被清理后由新请求写入新用户数据
    expect(mockStore.publicUserProfile?.id).toBe('user-b')
    expect(mockStore.publicUserStats?.completedOrders).toBe(5)
  })

  it('切换到空 ID 时不触发加载', async () => {
    wrapper = mount(UserProfileView)
    await flushPromises()
    vi.clearAllMocks()
    mockRoute.params.id = ''
    await flushPromises()
    expect(mockFetchProfile).not.toHaveBeenCalled()
  })
})

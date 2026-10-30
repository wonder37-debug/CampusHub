<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowUp, Plus, RefreshCw } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import ErrorState from '@/components/ErrorState.vue'
import DemandHomeCard from '@/components/DemandHomeCard.vue'
import { CAMPUS_ZONE_OPTIONS, DEMAND_CATEGORY_OPTIONS, type CampusZone, type DemandRecord, type DemandSortMode, type RecommendationRecord } from '@/types/campushub'
import { useCampusHubStore } from '@/stores/campusHub'
import { formatCampusZone, formatDemandCategory } from '@/utils/format'
import { isVisibleOnDemandHome } from '@/lib/home-demand-card'

type DemandSortField = DemandSortMode
type DemandSortDirection = 'asc' | 'desc'
type DemandCategoryValue = (typeof DEMAND_CATEGORY_OPTIONS)[number]

const store = useCampusHubStore()
const router = useRouter()
const filters = reactive({
  q: '',
  category: '' as '' | (typeof DEMAND_CATEGORY_OPTIONS)[number],
  campusZone: '' as '' | CampusZone,
  status: 'PENDING' as string,
  sortField: (store.currentUser ? 'recommend' : 'time') as DemandSortField,
  sortDirection: 'desc' as DemandSortDirection,
  page: 1,
  size: 9
})

const loadingDemands = ref(true)
const refreshing = ref(false)
const recommendationsError = ref('')
const recommendedItems = ref<RecommendationRecord[]>([])
// 需求数据本身的加载错误才阻塞整个列表；推荐接口失败不遮蔽已加载成功的需求流，仅降级为非阻塞提示
const listError = computed(() => store.demandFetchError || '')
const categoryShortcuts = computed<Array<{ value: '' | DemandCategoryValue; label: string }>>(() => [
  { value: '', label: '全部需求' },
  ...DEMAND_CATEGORY_OPTIONS.map((value) => ({ value, label: formatDemandCategory(value) }))
])

let searchDebounceTimer: number | undefined
let refreshSequence = 0

function setSortField(field: DemandSortField): void {
  if (filters.sortField === field) return
  filters.sortField = field
  filters.sortDirection = 'desc'
}

function getBackendSortMode(): DemandSortMode {
  return filters.sortField
}

function filteredDemandItems(source: DemandRecord[]): DemandRecord[] {
  const query = filters.q.trim().toLocaleLowerCase()
  return source
    .filter(isVisibleOnDemandHome)
    .filter((demand) => !filters.category || demand.category === filters.category)
    .filter((demand) => !filters.campusZone || demand.campusZone === filters.campusZone)
    .filter((demand) => {
      if (!filters.status) return true
      if (filters.status === 'ACCEPTED') return demand.status === 'IN_PROGRESS'
      return demand.status === filters.status
    })
    .filter((demand) => !query || `${demand.title} ${demand.description} ${demand.location}`.toLocaleLowerCase().includes(query))
}

const filteredDemands = computed(() => filteredDemandItems(store.demands))
const totalCount = computed(() => filteredDemands.value.length)
const visibleDemands = computed(() => {
  const filtered = [...filteredDemands.value]
  if (filters.sortField === 'recommend' && recommendedItems.value.length) {
    const rankById = new Map(recommendedItems.value.map((item) => [item.demand.id, item]))
    filtered.sort((left, right) => {
      const leftRank = rankById.get(left.id)?.rank
      const rightRank = rankById.get(right.id)?.rank
      if (leftRank != null && rightRank != null) return leftRank - rightRank
      if (leftRank != null) return -1
      if (rightRank != null) return 1
      return right.createdAt.localeCompare(left.createdAt)
    })
  } else if (filters.sortField === 'reward') {
    filtered.sort((left, right) => filters.sortDirection === 'asc' ? left.reward - right.reward : right.reward - left.reward)
  } else {
    filtered.sort((left, right) => filters.sortDirection === 'asc'
      ? left.createdAt.localeCompare(right.createdAt)
      : right.createdAt.localeCompare(left.createdAt))
  }
  return filtered.slice(0, filters.page * filters.size)
})
const hasMore = computed(() => visibleDemands.value.length < totalCount.value)
const recommendationByDemandId = computed(() => new Map(recommendedItems.value.map((item) => [item.demand.id, item])))

async function syncRecommendations(sequence: number): Promise<void> {
  if (!store.currentUser) {
    recommendedItems.value = []
    return
  }
  const items = await store.fetchRecommendations(filters.page, filters.size * 4)
  if (sequence === refreshSequence) recommendedItems.value = items
}

async function refreshList(): Promise<void> {
  if (searchDebounceTimer !== undefined) {
    window.clearTimeout(searchDebounceTimer)
    searchDebounceTimer = undefined
  }

  const sequence = ++refreshSequence
  refreshing.value = true
  filters.page = 1
  recommendationsError.value = ''

  try {
    await store.fetchDemands({
      q: filters.q,
      category: filters.category || undefined,
      campusZone: filters.campusZone || undefined,
      sort: getBackendSortMode(),
      page: 1,
      size: 100,
      all: true
    })
    if (sequence !== refreshSequence) return
    if (store.demandFetchError) {
      recommendedItems.value = []
      return
    }
    if (filters.sortField === 'recommend') {
      try {
          await syncRecommendations(sequence)
      } catch {
        if (sequence === refreshSequence) {
          recommendedItems.value = []
          recommendationsError.value = '推荐排序暂时不可用，请重试或切换排序方式。'
        }
      }
    } else {
      recommendedItems.value = []
    }
  } finally {
    if (sequence === refreshSequence) refreshing.value = false
  }
}

function setCategory(category: '' | (typeof DEMAND_CATEGORY_OPTIONS)[number]): void {
  filters.category = category
}

function goPublish(): void {
  if (!store.currentUser) {
    void router.push('/auth')
    return
  }
  void router.push('/demands/new')
}

function loadMore(): void {
  if (!refreshing.value && hasMore.value) filters.page++
}

function scrollToTop(): void {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

onMounted(() => {
  void refreshList().finally(() => {
    loadingDemands.value = false
  })
})

watch(
  () => [filters.sortField, filters.sortDirection],
  () => { void refreshList() }
)

watch(() => filters.q, () => {
  if (searchDebounceTimer !== undefined) window.clearTimeout(searchDebounceTimer)
  searchDebounceTimer = window.setTimeout(() => {
    searchDebounceTimer = undefined
    void refreshList()
  }, 300)
})

watch(
  () => [filters.category, filters.campusZone, filters.status],
  () => { void refreshList() }
)

watch(() => store.currentUser?.id, (userId) => {
  if (!userId && filters.sortField === 'recommend') {
    filters.sortField = 'time'
    return
  }
  void refreshList()
})

onBeforeUnmount(() => {
  if (searchDebounceTimer !== undefined) window.clearTimeout(searchDebounceTimer)
  refreshSequence++
})
</script>

<template>
  <main class="home-page">
    <section class="home-toolbar" aria-labelledby="home-title">
      <div class="home-heading">
        <div>
          <p class="home-eyebrow">校园社区</p>
          <h1 id="home-title">发现校园里的互助与好物</h1>
          <p>把正在发生的需求，和愿意搭把手的同学连接起来。</p>
        </div>
        <Button class="home-publish" @click="goPublish"><Plus :size="17" />发布需求</Button>
      </div>

      <div class="home-search-row">
        <label class="home-search" for="demand-q">
          <span aria-hidden="true" class="home-search-icon"></span>
          <input id="demand-q" v-model="filters.q" type="search" placeholder="搜索需求、物品或地点" />
        </label>
        <div class="home-filter">
          <label for="demand-campus">校区</label>
          <select id="demand-campus" v-model="filters.campusZone">
            <option value="">全部校区</option>
            <option v-for="zone in CAMPUS_ZONE_OPTIONS" :key="zone" :value="zone">{{ formatCampusZone(zone) }}</option>
          </select>
        </div>
        <div class="home-filter">
          <label for="demand-status">状态</label>
          <select id="demand-status" v-model="filters.status">
            <option value="">全部阶段</option>
            <option value="PENDING">开放中</option>
            <option value="IN_PROGRESS">进行中</option>
            <option value="COMPLETED">已完成</option>
          </select>
        </div>
        <div class="home-filter home-filter--sort">
          <label for="demand-sort">排序</label>
          <select id="demand-sort" :value="filters.sortField" @change="setSortField(($event.target as HTMLSelectElement).value as DemandSortField)">
            <option v-if="store.currentUser" value="recommend">为你推荐</option>
            <option value="time">最新发布</option>
            <option value="reward">校邻币奖励</option>
          </select>
        </div>
        <Button variant="outline" class="home-refresh" :disabled="refreshing" @click="refreshList">
          <RefreshCw :size="15" :class="{ 'is-spinning': refreshing }" />刷新
        </Button>
      </div>

      <nav class="home-categories" aria-label="需求类型快捷入口">
        <button
          v-for="category in categoryShortcuts"
          :key="category.value || 'all'"
          type="button"
          :class="{ 'is-active': filters.category === category.value }"
          :aria-pressed="filters.category === category.value"
          @click="setCategory(category.value)"
        >
          {{ category.label }}
        </button>
      </nav>
    </section>

    <section class="home-feed" aria-label="校园需求">
      <div class="home-feed-heading">
        <div>
          <h2>{{ filters.category ? formatDemandCategory(filters.category) : '正在发生' }}</h2>
          <p v-if="!listError && !refreshing">{{ totalCount }} 条公开需求</p>
          <p v-else>更新校园里的新需求</p>
        </div>
        <span>同学之间的真实需求</span>
      </div>

      <!-- 推荐服务降级提示：不阻塞已加载需求，排序自动回退最新发布 -->
      <p v-if="recommendationsError && !listError && !refreshing" class="home-recommend-fallback" role="status">
        {{ recommendationsError }}
      </p>

      <ErrorState
        v-if="listError && !refreshing"
        title="需求流暂时不可用"
        :description="listError"
        retry
        @retry="refreshList"
      />

      <div v-else-if="refreshing || loadingDemands" class="home-grid home-grid--skeleton" aria-label="正在加载需求" aria-busy="true">
        <article v-for="item in 6" :key="item" class="home-skeleton">
          <div class="home-skeleton__image"></div>
          <div class="home-skeleton__lines"><span></span><span></span><span></span></div>
        </article>
      </div>

      <div v-else-if="!visibleDemands.length" class="home-empty">
        <div class="home-empty__mark" aria-hidden="true">⌕</div>
        <h3>{{ filters.q || filters.category || filters.campusZone || filters.status !== 'PENDING' ? '没有找到匹配的需求' : '暂时还没有开放需求' }}</h3>
        <p>{{ filters.q || filters.category || filters.campusZone || filters.status !== 'PENDING' ? '试试调整关键词或筛选条件。' : '发布一条真实需求，让校园里的互助开始发生。' }}</p>
        <Button v-if="!filters.q && !filters.category && !filters.campusZone && filters.status === 'PENDING'" variant="outline" @click="goPublish">发布第一条需求</Button>
      </div>

      <div v-else class="home-grid">
        <DemandHomeCard
          v-for="demand in visibleDemands"
          :key="demand.id"
          :demand="demand"
          :current-user-id="store.currentUser?.id ?? ''"
          :recommendation="recommendationByDemandId.get(demand.id)"
        />
      </div>

      <div v-if="!listError && !refreshing && hasMore" class="home-load-more">
        <Button variant="outline" @click="loadMore">加载更多需求</Button>
        <span>已显示 {{ visibleDemands.length }} / {{ totalCount }}</span>
      </div>
      <p v-else-if="!listError && !refreshing && totalCount > 0 && !hasMore" class="home-all-loaded">已显示全部 {{ totalCount }} 条需求</p>
    </section>

    <Button class="home-back-top" variant="secondary" size="icon" aria-label="回到顶部" @click="scrollToTop"><ArrowUp :size="17" /></Button>
  </main>
</template>

<style scoped>
.home-page {
  width: min(100%, 1480px);
  margin: 0 auto;
  padding: 20px clamp(22px, 3.1vw, 46px) 56px;
}

.home-toolbar {
  padding-bottom: 17px;
  border-bottom: 1px solid var(--border);
}

.home-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 19px;
}

.home-eyebrow {
  margin: 0 0 7px;
  color: var(--primary);
  font-size: 0.66rem;
  font-weight: 760;
  letter-spacing: 0.15em;
}

.home-heading h1 {
  margin: 0;
  color: var(--foreground);
  font-size: clamp(1.45rem, 1.8vw, 1.9rem);
  font-weight: 730;
  letter-spacing: -0.035em;
  line-height: 1.2;
}

.home-heading p:last-child {
  margin: 7px 0 0;
  color: var(--muted-foreground);
  font-size: 0.86rem;
}

.home-publish { flex: none; gap: 7px; }

.home-search-row {
  display: flex;
  min-height: 42px;
  align-items: stretch;
  gap: 10px;
}

.home-search {
  display: flex;
  min-width: 240px;
  flex: 1;
  align-items: center;
  gap: 10px;
  padding: 0 12px;
  border: 1px solid var(--input);
  border-radius: calc(var(--radius) * 0.85);
  background: var(--card);
  transition: border-color 140ms ease, box-shadow 140ms ease;
}

.home-search:focus-within {
  border-color: var(--ring);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--ring) 17%, transparent);
}

.home-search-icon {
  position: relative;
  width: 15px;
  height: 15px;
  flex: none;
  border: 1.5px solid #78867b;
  border-radius: 50%;
}

.home-search-icon::after {
  position: absolute;
  right: -4px;
  bottom: -3px;
  width: 6px;
  height: 1.5px;
  transform: rotate(45deg);
  background: #78867b;
  content: '';
}

.home-search input {
  width: 100%;
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--foreground);
  font: inherit;
  font-size: 0.85rem;
}

.home-search input::placeholder { color: #8a958c; }
.home-search kbd { color: #8a958c; font: inherit; font-size: 0.68rem; white-space: nowrap; }

.home-filter {
  display: grid;
  min-width: 132px;
  grid-template-columns: auto 1fr;
  align-items: center;
  gap: 8px;
  padding: 0 11px;
  border: 1px solid var(--input);
  border-radius: calc(var(--radius) * 0.85);
  background: var(--card);
}

.home-filter label { color: var(--muted-foreground); font-size: 0.75rem; white-space: nowrap; }
.home-filter select {
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--foreground);
  font: inherit;
  font-size: 0.79rem;
  /* 自绘箭头占右侧空间，避免文字与箭头重叠 */
  padding-right: 18px;
  background-image: var(--select-arrow);
  background-repeat: no-repeat;
  background-position: right center;
  background-size: 11px 7px;
}
.home-filter--sort { min-width: 146px; }
.home-refresh { flex: none; gap: 6px; }
.is-spinning { animation: spin 900ms linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.home-categories {
  display: flex;
  gap: 4px;
  overflow-x: auto;
  padding-top: 12px;
}

.home-categories button {
  flex: none;
  padding: 7px 13px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--card);
  color: var(--muted-foreground);
  cursor: pointer;
  font: inherit;
  font-size: 0.78rem;
  transition: color 140ms ease, background 140ms ease, border-color 140ms ease;
}

.home-categories button:hover { background: var(--muted); color: var(--foreground); border-color: color-mix(in srgb, var(--primary) 22%, var(--border)); }
.home-categories button:focus-visible { outline: 3px solid var(--ring); outline-offset: 2px; }
.home-categories button.is-active { border-color: color-mix(in srgb, var(--primary) 30%, transparent); background: color-mix(in srgb, var(--primary) 8%, var(--card)); color: var(--primary); font-weight: 650; }

.home-feed { padding-top: 20px; }

.home-recommend-fallback {
  margin: 0 0 14px;
  padding: 10px 14px;
  border: 1px solid color-mix(in srgb, var(--primary) 24%, transparent);
  border-radius: calc(var(--radius) * 0.85);
  background: color-mix(in srgb, var(--primary) 7%, transparent);
  color: var(--muted-foreground);
  font-size: 0.8rem;
}

.home-feed-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin: 0 0 14px;
}

.home-feed-heading h2 { margin: 0; color: var(--foreground); font-size: 1.08rem; font-weight: 700; }
.home-feed-heading p { margin: 4px 0 0; color: var(--muted-foreground); font-size: 0.75rem; }
.home-feed-heading > span { color: var(--muted-foreground); font-size: 0.72rem; }

.home-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  align-items: stretch;
  gap: 18px;
}

.home-grid--skeleton { row-gap: 18px; }
.home-skeleton { overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius); background: var(--card); }
.home-skeleton__image { aspect-ratio: 4 / 3; background: linear-gradient(100deg, #edf0ec 20%, #f5f6f3 38%, #edf0ec 58%); background-size: 200% 100%; animation: skeleton 1.4s ease infinite; }
.home-skeleton__lines { display: grid; gap: 10px; padding: 18px; }
.home-skeleton__lines span { height: 10px; border-radius: 99px; background: #edf0ec; }
.home-skeleton__lines span:first-child { width: 46%; }
.home-skeleton__lines span:last-child { width: 72%; }
@keyframes skeleton { to { background-position-x: -200%; } }

.home-empty {
  display: grid;
  min-height: 300px;
  align-content: center;
  justify-items: center;
  padding: 30px;
  border: 1px dashed #cdd8ce;
  border-radius: var(--radius);
  background: color-mix(in srgb, var(--card) 70%, var(--background));
  text-align: center;
}

.home-empty__mark { color: #758679; font-size: 2rem; line-height: 1; }
.home-empty h3 { margin: 14px 0 0; color: var(--foreground); font-size: 1rem; }
.home-empty p { margin: 7px 0 16px; color: var(--muted-foreground); font-size: 0.84rem; }
.home-load-more { display: flex; align-items: center; justify-content: center; gap: 12px; margin: 24px 0; }
.home-load-more > span, .home-all-loaded { color: var(--muted-foreground); font-size: 0.75rem; text-align: center; }
.home-all-loaded { margin: 24px 0 0; }
.home-back-top { position: fixed; right: 26px; bottom: 26px; z-index: 20; border: 1px solid var(--border); opacity: 0.92; }

@media (max-width: 1100px) {
  .home-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 15px; }
  .home-search-row { flex-wrap: wrap; }
  .home-search { flex-basis: 100%; }
  .home-filter { flex: 1; }
}

@media (max-width: 760px) {
  .home-page { padding: 16px 16px 42px; }
  .home-heading { align-items: flex-start; }
  .home-heading p:last-child { max-width: 34rem; }
  .home-grid { grid-template-columns: 1fr; }
  .home-search-row { gap: 8px; }
  .home-filter { min-width: 0; }
  .home-feed-heading > span { display: none; }
}

@media (prefers-reduced-motion: reduce) {
  .is-spinning, .home-skeleton__image { animation: none; }
}
</style>

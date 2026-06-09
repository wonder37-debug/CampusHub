<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import SkeletonCard from '@/components/SkeletonCard.vue'
import { useRouter } from 'vue-router'

import { DEMAND_CATEGORY_OPTIONS, type CampusZone, type DemandRecord, type DemandSortMode, type RecommendationRecord } from '@/types/campushub'
import { useCampusHubStore } from '@/stores/campusHub'
import { campusZoneOptions, formatCampusZone, formatDemandCategory, formatDemandStatus, formatMoney, formatRelativeTime, formatScore, statusToneClass, truncateText, formatDateTime } from '@/utils/format'

type DemandSortField = 'time' | 'reward' | 'recommend'
type DemandSortDirection = 'asc' | 'desc'

const store = useCampusHubStore()
const router = useRouter()

const filters = reactive({
  q: '',
  category: '' as '' | (typeof DEMAND_CATEGORY_OPTIONS)[number],
  campusZone: '' as '' | CampusZone,
  status: '' as string,
  sortField: 'recommend' as DemandSortField,
  sortDirection: 'desc' as DemandSortDirection,
  page: 1,
  size: 6
})

const listRef = ref<HTMLElement | null>(null)
const refreshing = ref(false)
const loadingDemands = ref(false)
const recommendedItems = ref<RecommendationRecord[]>([])
let observer: IntersectionObserver | null = null

function setSortField(field: DemandSortField): void {
  if (filters.sortField === field) {
    filters.sortDirection = filters.sortDirection === 'asc' ? 'desc' : 'asc'
    return
  }

  filters.sortField = field
  filters.sortDirection = 'asc'
}

function isSortActive(field: DemandSortField): boolean {
  return filters.sortField === field
}

function formatSortDirectionLabel(direction: DemandSortDirection): string {
  return direction === 'asc' ? '正序' : '逆序'
}

function getBackendSortMode(): DemandSortMode {
  return filters.sortField
}

function isAscendingSort(): boolean {
  return filters.sortDirection === 'asc'
}

function isRecommendSort(): boolean {
  return filters.sortField === 'recommend'
}

function filteredDemandItems(source: DemandRecord[]): DemandRecord[] {
  const selectedCategory = filters.category
  const selectedCampusZone = filters.campusZone
  const selectedStatus = filters.status

  return source
    .filter((demand) => demand.status !== 'EXPIRED')
    .filter((demand) => !selectedCategory || demand.category === selectedCategory)
    .filter((demand) => !selectedCampusZone || demand.campusZone === selectedCampusZone)
    .filter((demand) => !filters.q.trim() || `${demand.title} ${demand.description} ${demand.location}`.toLowerCase().includes(filters.q.trim().toLowerCase()))
    .filter((demand) => {
      if (!selectedStatus) return true
      if (selectedStatus === 'ACCEPTED') {
        return store.orders.some((o) => o.demandId === demand.id && o.status === 'ACCEPTED')
      }
      return demand.status === selectedStatus
    })
}

const visibleDemands = computed(() => {
  const source = isRecommendSort() && recommendedItems.value.length
    ? recommendedItems.value.map((item) => item.demand)
    : [...store.demands]

  const filtered = filteredDemandItems(source)

  if (isRecommendSort()) {
    const ordered = isAscendingSort() ? filtered : [...filtered].reverse()
    return ordered.slice(0, filters.page * filters.size)
  }

  const sorted = filtered.sort((left: DemandRecord, right: DemandRecord) => {
    if (filters.sortField === 'time') {
      return isAscendingSort() ? left.createdAt.localeCompare(right.createdAt) : right.createdAt.localeCompare(left.createdAt)
    }

    if (filters.sortField === 'reward') {
      return isAscendingSort() ? left.reward - right.reward : right.reward - left.reward
    }

    if (isRecommendSort()) {
      if (recommendedItems.value.length) {
        const leftRank = recommendedItems.value.find((item) => item.demand.id === left.id)?.rank ?? Number.MAX_SAFE_INTEGER
        const rightRank = recommendedItems.value.find((item) => item.demand.id === right.id)?.rank ?? Number.MAX_SAFE_INTEGER
        return isAscendingSort() ? leftRank - rightRank : rightRank - leftRank
      }
      const preferredCategories = store.popularCategories
      const leftIndex = preferredCategories.indexOf(left.category)
      const rightIndex = preferredCategories.indexOf(right.category)
      const normalizedLeft = leftIndex === -1 ? preferredCategories.length : leftIndex
      const normalizedRight = rightIndex === -1 ? preferredCategories.length : rightIndex
      const baseCompare = normalizedLeft - normalizedRight || right.createdAt.localeCompare(left.createdAt)
      return isAscendingSort() ? -baseCompare : baseCompare
    }

    return right.createdAt.localeCompare(left.createdAt)
  })

  return sorted.slice(0, filters.page * filters.size)
})

const totalCount = computed(() => {
  if (isRecommendSort() && recommendedItems.value.length) {
    return filteredDemandItems(recommendedItems.value.map((item) => item.demand)).length
  }

  return filteredDemandItems(store.demands).length
})
const hasMore = computed(() => visibleDemands.value.length < totalCount.value)

async function refreshList(): Promise<void> {
  refreshing.value = true
  filters.page = 1
  try {
    await store.fetchDemands({
      q: filters.q,
      category: filters.category || undefined,
      campusZone: filters.campusZone || undefined,
      sort: getBackendSortMode(),
      page: 1,
      size: 100
    })
    if (isRecommendSort()) {
      await syncRecommendations()
    } else {
      recommendedItems.value = []
    }
  } finally {
    refreshing.value = false
  }
}

function loadMore(): void {
  if (hasMore.value) {
    filters.page += 1
  }
}

function openDemand(demandId: string): void {
  router.push(`/demands/${demandId}`)
}

function goPublish(): void {
  if (!store.currentUser) {
    router.push('/auth')
    return
  }
  router.push('/demands/new')
}



async function syncRecommendations(): Promise<void> {
  if (!store.currentUser) {
    recommendedItems.value = []
    return
  }

  const items = await store.fetchRecommendations(filters.page, filters.size * 4)
  recommendedItems.value = items
}

onMounted(() => {
  // 首页仅请求 PENDING 的需求以减小数据量并符合展示目的
  loadingDemands.value = true
  void (async () => {
    try {
      await refreshList()
    } finally {
      loadingDemands.value = false
    }
  })()

  if (listRef.value) {
    observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          loadMore()
        }
      },
      { rootMargin: '200px' }
    )
    observer.observe(listRef.value)
  }
})

watch(
  () => [filters.sortField, filters.sortDirection],
  () => {
    void refreshList()
  }
)

watch(
  () => [filters.q, filters.category, filters.campusZone],
  () => {
    void refreshList()
  }
)

onBeforeUnmount(() => {
  observer?.disconnect()
})
</script>

<template>
  <div class="page-grid">
    <section class="panel">
      <div class="page-head">
        <div>
          <p class="eyebrow">首页</p>
          <h1 class="page-title">需求列表</h1>
          <p class="page-summary">筛选最新需求，点击卡片查看详情并可直接接单。</p>
        </div>
        <!-- 已有刷新列表按钮，移除重复的“下拉刷新”按钮 -->
      </div>

      <div class="filters">
        <div class="field" style="flex: 1 1 260px;">
          <label for="demand-q">关键词</label>
          <input id="demand-q" v-model="filters.q" type="search" placeholder="搜索标题、描述或地点" />
        </div>

        <div class="field" style="flex: 0 1 160px;">
          <label for="demand-category">分类</label>
          <select id="demand-category" v-model="filters.category">
            <option value="">全部分类</option>
            <option v-for="category in DEMAND_CATEGORY_OPTIONS" :key="category" :value="category">
              {{ formatDemandCategory(category) }}
            </option>
          </select>
        </div>

        <div class="field" style="flex: 0 1 140px;">
          <label for="demand-status">状态</label>
          <select id="demand-status" v-model="filters.status">
            <option value="">全部状态</option>
            <option value="PENDING">开放中</option>
            <option value="ACCEPTED">已接单</option>
            <option value="IN_PROGRESS">进行中</option>
            <option value="COMPLETED">已完成</option>
          </select>
        </div>

        <div class="field" style="flex: 0 1 140px;">
          <label for="demand-campus">校区</label>
          <select id="demand-campus" v-model="filters.campusZone">
            <option value="">全部校区</option>
            <option v-for="zone in campusZoneOptions()" :key="zone.value" :value="zone.value">
              {{ zone.label }}
            </option>
          </select>
        </div>

        <div class="field" style="flex: 1 1 100%;">
          <label>排序方式</label>
          <div class="sort-toggle-group" role="group" aria-label="排序方式">
            <button
              type="button"
              class="button"
              :class="isSortActive('time') ? 'primary' : 'secondary'"
              @click="setSortField('time')"
            >
              时间最近
              <span class="sort-toggle-state">{{ isSortActive('time') ? formatSortDirectionLabel(filters.sortDirection) : '' }}</span>
            </button>
            <button
              type="button"
              class="button"
              :class="isSortActive('reward') ? 'primary' : 'secondary'"
              @click="setSortField('reward')"
            >
              报酬最高
              <span class="sort-toggle-state">{{ isSortActive('reward') ? formatSortDirectionLabel(filters.sortDirection) : '' }}</span>
            </button>
            <button
              type="button"
              class="button"
              :class="isSortActive('recommend') ? 'primary' : 'secondary'"
              @click="setSortField('recommend')"
            >
              推荐排序
              <span class="sort-toggle-state">{{ isSortActive('recommend') ? formatSortDirectionLabel(filters.sortDirection) : '' }}</span>
            </button>
          </div>
        </div>

        <button type="button" class="button secondary" @click="refreshList">刷新列表</button>
      </div>
    </section>

    <section class="demand-grid" ref="listRef">
      <div v-if="refreshing" class="empty-state">
        <strong>正在刷新...</strong>
      </div>

      <div v-else-if="loadingDemands" class="demand-grid">
        <SkeletonCard v-for="n in 6" :key="n" />
      </div>

      <div v-else-if="!visibleDemands.length" class="empty-state" style="--empty-icon:'📋'">
        <strong>暂无需求</strong>
        <p>去发布第一条需求，让同学们看到你。</p>
      </div>

      <article
        v-for="demand in visibleDemands"
        :key="demand.id"
        class="list-card demand-card"
        :class="{ faded: demand.status !== 'PENDING' }"
        @click="openDemand(demand.id)"
      >
        <div class="status-row">
          <span class="badge is-neutral">{{ formatDemandCategory(demand.category) }}</span>
          <span
            class="chip"
            :class="store.orders.some((o) => o.demandId === demand.id && o.status === 'ACCEPTED') ? 'is-warning' : statusToneClass(demand.status)"
          >
            {{ store.orders.some((o) => o.demandId === demand.id && o.status === 'ACCEPTED') ? '已接单' : formatDemandStatus(demand.status) }}
          </span>
        </div>

        <div class="card-head">
          <h3>{{ demand.title }}</h3>
          <strong>{{ formatMoney(demand.reward) }}</strong>
        </div>

        <div v-if="isRecommendSort() && recommendedItems.find((item) => item.demand.id === demand.id)" class="status-row" style="margin-top: 8px;">
          <span class="chip is-success">推荐第 {{ recommendedItems.find((item) => item.demand.id === demand.id)?.rank }} 名</span>
          <span class="chip">推荐分 {{ formatScore(recommendedItems.find((item) => item.demand.id === demand.id)?.score ?? 0) }}</span>
        </div>

        <div v-if="isRecommendSort() && recommendedItems.find((item) => item.demand.id === demand.id)?.reasonTags?.length" class="tag-row">
          <span
            v-for="tag in recommendedItems.find((item) => item.demand.id === demand.id)?.reasonTags || []"
            :key="tag"
            class="badge is-neutral"
          >
            {{ tag }}
          </span>
        </div>

        <div class="meta">地点：{{ demand.location || '无' }}</div>

        <div class="meta" style="margin-top:6px">
          <span v-if="demand.startTime || demand.endTime">时间：{{ formatDateTime(demand.startTime || '') }} - {{ formatDateTime(demand.endTime || '') }}</span>
        </div>

        <div class="tag-row">
          <span class="badge is-neutral">{{ formatCampusZone(demand.campusZone) }}</span>
        </div>

        <p>描述：{{ truncateText(demand.description || '无', 86) }}</p>

        <div class="avatar-row">
          <img :src="demand.publisher?.avatarUrl ?? demand.publisherAvatar" :alt="demand.publisher?.nickname ?? demand.publisherName" class="avatar" />
          <div>
            <strong>{{ demand.anonymous ? demand.anonymousCode ?? '匿名用户' : (demand.publisher?.nickname ?? demand.publisherName) }}</strong>
            <div class="meta">信用分：{{ demand.publisher ? formatScore(demand.publisher.creditScore) : '未知' }}</div>
          </div>
        </div>

        <div class="meta">{{ formatRelativeTime(demand.createdAt) }}</div>
      </article>

      <div v-if="hasMore" class="empty-state subtle" style="background: transparent; box-shadow: none; border: 0;">
        正在加载更多...
      </div>
    </section>

    <button class="fab" type="button" title="发布需求" @click="goPublish">发布需求</button>
  </div>
</template>

<style scoped>
.sort-toggle-group {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.sort-toggle-group .button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.sort-toggle-state {
  font-size: 0.85em;
  opacity: 0.8;
}
</style>
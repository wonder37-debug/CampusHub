<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { DEMAND_CATEGORY_OPTIONS, type CampusZone, type DemandRecord, type DemandSortMode } from '@/types/campushub'
import { useCampusHubStore } from '@/stores/campusHub'
import { campusZoneOptions, formatCampusZone, formatDemandCategory, formatDemandStatus, formatMoney, formatRelativeTime, formatScore, statusToneClass, truncateText } from '@/utils/format'

const store = useCampusHubStore()
const router = useRouter()

const filters = reactive({
  category: '' as '' | (typeof DEMAND_CATEGORY_OPTIONS)[number],
  campusZone: '' as '' | CampusZone,
  status: '' as string,
  sort: 'time' as DemandSortMode,
  page: 1,
  size: 6
})

const listRef = ref<HTMLElement | null>(null)
const refreshing = ref(false)
const recommendedDemands = ref<DemandRecord[]>([])
let observer: IntersectionObserver | null = null

const visibleDemands = computed(() => {
  const selectedCategory = filters.category
  const selectedCampusZone = filters.campusZone
  const selectedStatus = filters.status
  const source = filters.sort === 'recommend' && recommendedDemands.value.length ? recommendedDemands.value : [...store.demands]
  const sorted = source
    .filter((demand) => !selectedCategory || demand.category === selectedCategory)
    .filter((demand) => !selectedCampusZone || demand.campusZone === selectedCampusZone)
    .filter((demand) => {
      if (!selectedStatus) return true
      if (selectedStatus === 'ACCEPTED') {
        // treat '已接单' as a demand that has an order in ACCEPTED state
        return store.orders.some((o) => o.demandId === demand.id && o.status === 'ACCEPTED')
      }
      return demand.status === selectedStatus
    })
    .sort((left: DemandRecord, right: DemandRecord) => {
      if (filters.sort === 'distance') {
        return left.distanceKm - right.distanceKm
      }

      if (filters.sort === 'reward') {
        return right.reward - left.reward
      }

      if (filters.sort === 'recommend') {
        const preferredCategories = store.popularCategories
        const leftIndex = preferredCategories.indexOf(left.category)
        const rightIndex = preferredCategories.indexOf(right.category)
        const normalizedLeft = leftIndex === -1 ? preferredCategories.length : leftIndex
        const normalizedRight = rightIndex === -1 ? preferredCategories.length : rightIndex
        return normalizedLeft - normalizedRight || right.createdAt.localeCompare(left.createdAt)
      }

      return right.createdAt.localeCompare(left.createdAt)
    })

  return sorted.slice(0, filters.page * filters.size)
})

const totalCount = computed(() =>
  store.demands.filter(
    (demand) =>
      (!filters.category || demand.category === filters.category) &&
      (!filters.campusZone || demand.campusZone === filters.campusZone) &&
      (!filters.status || demand.status === filters.status)
  ).length
)
const hasMore = computed(() => visibleDemands.value.length < totalCount.value)

function refreshList(): void {
  refreshing.value = true
  filters.page = 1
  window.setTimeout(() => {
    void store.fetchDemands().finally(() => {
      if (filters.sort === 'recommend') {
        void syncRecommendations()
      }
      refreshing.value = false
    })
  }, 0)
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

function onPullToRefresh(): void {
  refreshList()
}

async function syncRecommendations(): Promise<void> {
  if (!store.currentUser) {
    recommendedDemands.value = []
    return
  }

  const items = await store.fetchRecommendations(filters.page, filters.size * 4)
  recommendedDemands.value = items
}

onMounted(() => {
  void store.fetchDemands()
  if (filters.sort === 'recommend') {
    void syncRecommendations()
  }

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
  () => filters.sort,
  (sort) => {
    if (sort === 'recommend') {
      void syncRecommendations()
    }
  }
)

onBeforeUnmount(() => {
  observer?.disconnect()
})
</script>

<template>
  <div class="page-grid" @touchend="onPullToRefresh">
    <section class="panel">
      <div class="page-head">
        <div>
          <p class="eyebrow">首页</p>
          <h1 class="page-title">需求列表</h1>
          <p class="page-summary">筛选最新需求，点击卡片查看详情并可直接接单。</p>
        </div>
        <button type="button" class="button secondary" @click="refreshList">下拉刷新</button>
      </div>

      <div class="filters">
        <div class="field">
          <label for="demand-category">分类</label>
          <select id="demand-category" v-model="filters.category">
            <option value="">全部分类</option>
            <option v-for="category in DEMAND_CATEGORY_OPTIONS" :key="category" :value="category">
              {{ formatDemandCategory(category) }}
            </option>
          </select>
        </div>

          <div class="field">
            <label for="demand-status">状态</label>
            <select id="demand-status" v-model="filters.status">
              <option value="">全部状态</option>
              <option value="PENDING">开放中</option>
              <option value="ACCEPTED">已接单</option>
              <option value="IN_PROGRESS">进行中</option>
              <option value="COMPLETED">已完成</option>
            </select>
          </div>

        <div class="field">
          <label for="demand-campus">校区</label>
          <select id="demand-campus" v-model="filters.campusZone">
            <option value="">全部校区</option>
            <option v-for="zone in campusZoneOptions()" :key="zone.value" :value="zone.value">
              {{ zone.label }}
            </option>
          </select>
        </div>

        <div class="field">
          <label for="demand-sort">排序方式</label>
          <select id="demand-sort" v-model="filters.sort">
            <option value="time">时间倒序</option>
            <option value="distance">距离最近</option>
            <option value="reward">报酬从高到低</option>
            <option value="recommend">推荐排序</option>
          </select>
        </div>

        <button type="button" class="button secondary" @click="refreshList">刷新列表</button>
      </div>
    </section>

    <section class="demand-grid" ref="listRef">
      <div v-if="refreshing" class="empty-state">
        <strong>正在刷新...</strong>
      </div>

      <div v-else-if="!visibleDemands.length" class="empty-state">
        <strong>暂无需求，去发布第一条~</strong>
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
          <strong>{{ demand.reward > 0 ? formatMoney(demand.reward) : '一杯奶茶' }}</strong>
        </div>

        <div class="meta">{{ demand.location }}</div>

        <div class="tag-row">
          <span class="badge is-neutral">{{ formatCampusZone(demand.campusZone) }}</span>
        </div>

        <p>{{ truncateText(demand.description, 86) }}</p>

        <div class="avatar-row">
          <img :src="demand.publisherAvatar" :alt="demand.publisherName" class="avatar" />
          <div>
            <strong>{{ demand.anonymous ? demand.anonymousCode ?? '匿名用户' : demand.publisherName }}</strong>
            <div class="meta">信用分 {{ formatScore(store.getUserById(demand.publisherId)?.creditScore ?? 0) }}</div>
          </div>
        </div>

        <div class="meta">{{ formatRelativeTime(demand.createdAt) }}</div>
      </article>

      <div v-if="hasMore" class="empty-state subtle" style="background: transparent; box-shadow: none; border: 0;">
        正在加载更多...
      </div>
    </section>

    <button class="fab" type="button" title="发布需求" @click="goPublish">+</button>
  </div>
</template>
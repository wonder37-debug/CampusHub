<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import SkeletonCard from '@/components/SkeletonCard.vue'
import { useRouter } from 'vue-router'

import { DEMAND_CATEGORY_OPTIONS, type CampusZone, type DemandRecord, type DemandSortMode, type RecommendationRecord } from '@/types/campushub'
import { useCampusHubStore } from '@/stores/campusHub'
import { campusZoneOptions, formatCampusZone, formatDemandCategory, formatDemandStatus, formatMoney, formatRelativeTime, formatScore, statusToneClass, truncateText, formatDateTime } from '@/utils/format'

type DemandSortField = 'time' | 'reward' | 'recommend'
type DemandSortDirection = 'asc' | 'desc'

const store = useCampusHubStore()
const router = useRouter()

// 匿名用户头像（SVG data URI，保护发布者身份）
const ANONYMOUS_AVATAR =
  "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='42' height='42' viewBox='0 0 42 42'%3E%3Crect width='42' height='42' rx='14' fill='%231f5f53' fill-opacity='0.12'/%3E%3Ccircle cx='21' cy='16' r='6' fill='%231f5f53' fill-opacity='0.45'/%3E%3Cpath d='M9 35c0-6.6 5.4-12 12-12s12 5.4 12 12' fill='%231f5f53' fill-opacity='0.45'/%3E%3C/svg%3E"

const filters = reactive({
  q: '',
  category: '' as '' | (typeof DEMAND_CATEGORY_OPTIONS)[number],
  campusZone: '' as '' | CampusZone,
  status: 'PENDING' as string,
  sortField: 'recommend' as DemandSortField,
  sortDirection: 'desc' as DemandSortDirection,
  page: 1,
  size: 7
})

const refreshing = ref(false)
const loadingDemands = ref(false)
const recommendedItems = ref<RecommendationRecord[]>([])

function setSortField(field: DemandSortField): void {
  if (filters.sortField !== field) {
    filters.sortField = field
    filters.sortDirection = (field === 'time' || field === 'reward') ? 'desc' : 'desc'
  }
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
    .filter((demand) => !['EXPIRED', 'REVIEWING', 'CANCELLED'].includes(demand.status))
    .filter((demand) => !selectedCategory || demand.category === selectedCategory)
    .filter((demand) => !selectedCampusZone || demand.campusZone === selectedCampusZone)
    .filter((demand) => !filters.q.trim() || `${demand.title} ${demand.description} ${demand.location}`.toLowerCase().includes(filters.q.trim().toLowerCase()))
    .filter((demand) => {
      if (!selectedStatus) return true
      if (selectedStatus === 'ACCEPTED') {
        // “已接单”对应 demand.status === 'IN_PROGRESS'（接单时后端已将需求状态更新为 IN_PROGRESS）
        return demand.status === 'IN_PROGRESS'
      }
      return demand.status === selectedStatus
    })
}

const visibleDemands = computed(() => {
  // Always use full demand list as source; recommendation only affects order
  const source = [...store.demands]
  const filtered = filteredDemandItems(source)

  if (isRecommendSort() && recommendedItems.value.length) {
    // Recommendation: fully trust backend order, no front-end sorting
    const recOrder = recommendedItems.value.map((item) => item.demand.id)
    const recSet = new Set(recOrder)
    const recItems = recOrder.map((id) => filtered.find((d) => d.id === id)).filter(Boolean) as DemandRecord[]
    const otherItems = filtered.filter((d) => !recSet.has(d.id)).sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    if (filters.sortDirection === 'asc') {
      return [...recItems.reverse(), ...otherItems].slice(0, filters.page * filters.size)
    }
    return [...recItems, ...otherItems].slice(0, filters.page * filters.size)
  }

  const sorted = filtered.sort((left: DemandRecord, right: DemandRecord) => {
    if (filters.sortField === 'time') {
      return isAscendingSort() ? left.createdAt.localeCompare(right.createdAt) : right.createdAt.localeCompare(left.createdAt)
    }

    if (filters.sortField === 'reward') {
      return isAscendingSort() ? left.reward - right.reward : right.reward - left.reward
    }

    return right.createdAt.localeCompare(left.createdAt)
  })

  return sorted.slice(0, filters.page * filters.size)
})

const totalCount = computed(() => {
  return filteredDemandItems(store.demands).length
})
const hasMore = computed(() => visibleDemands.value.length < totalCount.value)
const allLoaded = computed(() => !hasMore.value && totalCount.value > 0)

async function loadMore(): Promise<void> {
  filters.page++
}

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
      size: 100,
      all: true
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

/** 匿名需求检测：通过 anonymous 标志或 anonymousCode 多重判断 */
function isAnonymousDemand(demand: DemandRecord): boolean {
  return demand.anonymous === true || (demand.anonymousCode != null && demand.anonymousCode !== '')
}

/** 获取展示用头像：匿名需求返回占位头像 */
function getDisplayAvatar(demand: DemandRecord): string {
  if (isAnonymousDemand(demand)) return ANONYMOUS_AVATAR
  return demand.publisher?.avatarUrl ?? demand.publisherAvatar
}

/** 获取展示用名称：匿名需求返回匿名代号 */
function getDisplayName(demand: DemandRecord): string {
  if (isAnonymousDemand(demand)) return demand.anonymousCode ?? '匿名用户'
  return demand.publisher?.nickname ?? demand.publisherName
}



function scrollToTop(): void {
  window.scrollTo({ top: 0, behavior: 'smooth' })
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
  loadingDemands.value = true
  void (async () => {
    try {
      await refreshList()
    } catch {
      // handled by store
    } finally {
      loadingDemands.value = false
    }
  })()
})

watch(
  () => [filters.sortField, filters.sortDirection],
  () => {
    void refreshList()
  }
)

watch(
  () => [filters.q, filters.category, filters.campusZone, filters.status],
  () => {
    void refreshList()
  }
)

</script>

<template>
  <div>
    <div class="page-grid">
    <section class="panel">
      <div class="page-head">
        <div>
          <p class="eyebrow">首页</p>
          <h1 class="page-title">需求列表</h1>
          <p class="page-summary">筛选最新需求，点击卡片查看详情并可直接接单。</p>
        </div>
      </div>

      <div class="filters">
        <div class="field" style="flex: 1 1 260px;">
          <label for="demand-q">关键词</label>
          <input id="demand-q" v-model="filters.q" type="search" placeholder="搜索标题、描述或地点" />
        </div>

        <div class="field" style="flex: 0 1 160px;">
          <label for="demand-category">订单分类</label>
          <select id="demand-category" v-model="filters.category">
            <option value="">全部分类</option>
            <option v-for="category in DEMAND_CATEGORY_OPTIONS" :key="category" :value="category">
              {{ formatDemandCategory(category) }}
            </option>
          </select>
        </div>

        <div class="field" style="flex: 0 1 140px;">
          <label for="demand-status">订单状态</label>
          <select id="demand-status" v-model="filters.status">
            <option value="">全部状态</option>
            <option value="PENDING">开放中</option>
            <option value="ACCEPTED">已接单</option>
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

        <div class="sort-controls">
          <div class="sort-section sort-left">
            <div class="field sort-field">
              <label for="demand-sort">订单排序</label>
              <select
                id="demand-sort"
                class="sort-select"
                :value="filters.sortField"
                @change="setSortField(($event.target as HTMLSelectElement).value as DemandSortField)"
              >
                <option value="recommend">推荐排序</option>
                <option value="reward">报酬最高</option>
                <option value="time">时间最近</option>
              </select>
            </div>
          </div>

          <div class="sort-section sort-center">
            <button type="button" class="publish-button" @click="goPublish">
              <span class="publish-icon">+</span>
              发布需求
            </button>
          </div>

          <div class="sort-section sort-right">
            <button type="button" class="button secondary refresh-button" @click="refreshList">
              ↻ 刷新列表
            </button>
          </div>
        </div>
      </div>
    </section>

    <section class="demand-grid">
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
            :class="statusToneClass(demand.status)"
          >
            {{ demand.status === 'IN_PROGRESS' ? '已接单' : formatDemandStatus(demand.status) }}
          </span>
        </div>

        <div class="card-head">
          <h3>{{ demand.title }}</h3>
          <strong>{{ formatMoney(demand.reward) }}</strong>
        </div>

        <template v-if="isRecommendSort()">
          <div v-if="recommendedItems.find((item) => item.demand.id === demand.id)" class="status-row" style="margin-top: 8px;">
            <span class="chip is-success">推荐第 {{ recommendedItems.find((item) => item.demand.id === demand.id)!.rank }} 名</span>
            <span class="chip">推荐指数 {{ Math.round((recommendedItems.find((item) => item.demand.id === demand.id)!.score) * 100) }}%</span>
          </div>

          <div v-if="recommendedItems.find((item) => item.demand.id === demand.id)?.reasonTags?.length" class="tag-row">
            <span
              v-for="tag in recommendedItems.find((item) => item.demand.id === demand.id)!.reasonTags || []"
              :key="tag"
              class="badge is-neutral"
            >
              {{ tag }}
            </span>
          </div>
        </template>

        <div class="meta">地点：{{ demand.location || '无' }}</div>

        <div class="meta" style="margin-top:6px">
          <span v-if="demand.startTime || demand.endTime">时间：{{ formatDateTime(demand.startTime || '') }} - {{ formatDateTime(demand.endTime || '') }}</span>
        </div>

        <p>描述：{{ truncateText(demand.description || '无', 86) }}</p>

        <!-- 需求图片（最多展示3张，超出显示+N） -->
        <div
          v-if="demand.images && demand.images.length > 0"
          class="card-images"
          :class="`image-count-${Math.min(demand.images.length, 3)}`"
        >
          <div
            v-for="(imgUrl, imgIdx) in demand.images.slice(0, 3)"
            :key="imgUrl"
            class="card-image-item"
          >
            <img :src="imgUrl" alt="需求图片" loading="lazy" class="card-image-img" />
            <span v-if="imgIdx === 2 && demand.images.length > 3" class="image-more-overlay">
              +{{ demand.images.length - 2 }}
            </span>
          </div>
        </div>

        <div class="tag-row">
          <span class="badge is-neutral">{{ formatCampusZone(demand.campusZone) }}</span>
          <template v-if="demand.tags && demand.tags.length">
            <span v-for="tag in demand.tags" :key="tag" class="badge is-neutral">
              {{ tag }}
            </span>
          </template>
        </div>

        <div class="avatar-row">
          <img :src="getDisplayAvatar(demand)" :alt="getDisplayName(demand)" class="avatar" />
          <div>
            <strong>{{ getDisplayName(demand) }}</strong>
            <div class="meta">信用分：{{ isAnonymousDemand(demand) ? '隐藏' : (demand.publisher?.creditScore != null ? formatScore(demand.publisher.creditScore) : '未知') }}</div>
          </div>
        </div>

        <div class="meta">{{ formatRelativeTime(demand.createdAt) }}</div>
      </article>

      <div v-if="hasMore" class="list-card load-more-panel" @click="loadMore">
        <h3>加载更多需求</h3>
        <p class="subtle">当前显示 {{ visibleDemands.length }} / {{ totalCount }} 条</p>
      </div>
      <div v-if="allLoaded" class="load-more-done">📋 已展示全部 {{ totalCount }} 条需求</div>
    </section>
  </div>

  <Teleport to="body">
    <button class="fab back-to-top" @click="scrollToTop">↑ 回到顶部</button>
  </Teleport>
  </div>
</template>

<style scoped>
.publish-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 10px 24px;
  border: none;
  border-radius: 14px;
  background: linear-gradient(135deg, #2d8a4e 0%, #1b6e3a 100%);
  color: #fff;
  font-size: 0.95em;
  font-weight: 700;
  letter-spacing: 0.3px;
  cursor: pointer;
  box-shadow: 0 4px 14px rgba(45, 138, 78, 0.28);
  transition: all 0.2s ease;
  white-space: nowrap;
  min-width: 150px;
}

.publish-button:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 22px rgba(45, 138, 78, 0.38);
}

.publish-button:active {
  transform: translateY(0);
  box-shadow: 0 3px 10px rgba(45, 138, 78, 0.22);
}

.publish-icon {
  font-size: 1.3em;
  font-weight: 300;
  line-height: 1;
}

.sort-controls {
  flex: 1 1 100%;
  display: flex;
  align-items: flex-end;
  gap: 0;
  padding-top: 8px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
}

.sort-section {
  flex: 1;
  display: flex;
}

.sort-left {
  justify-content: flex-start;
}

.sort-center {
  justify-content: center;
}

.sort-right {
  justify-content: flex-end;
}

.sort-field {
  flex: 0 1 160px;
}

.sort-select {
  width: 100%;
  border: 1px solid rgba(0, 0, 0, 0.12);
  background: rgba(255, 255, 255, 0.84);
  color: var(--text-strong);
  border-radius: 16px;
  padding: 12px 14px;
  outline: none;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
  cursor: pointer;
}

.sort-select:focus {
  border-color: rgba(31, 95, 83, 0.46);
  box-shadow: 0 0 0 4px rgba(31, 95, 83, 0.12);
}

.refresh-button {
  color: var(--accent) !important;
  border-color: rgba(31, 95, 83, 0.18) !important;
  white-space: nowrap;
}

.refresh-button:hover {
  background: rgba(31, 95, 83, 0.08) !important;
}

.demand-card {
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
  display: flex;
  flex-direction: column;
}
.demand-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow);
}

.card-images {
  display: grid;
  gap: 6px;
  margin: 8px 0;
  height: 130px;
  flex-shrink: 0;
}

.image-count-1 {
  grid-template-columns: 1fr;
}

.image-count-2 {
  grid-template-columns: 1fr 1fr;
}

.image-count-3 {
  grid-template-columns: repeat(3, 1fr);
}

.card-image-item {
  position: relative;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid rgba(0, 0, 0, 0.08);
  height: 100%;
}

.card-image-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.3s ease;
}

.demand-card:hover .card-image-img {
  transform: scale(1.03);
}

.image-more-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.5);
  color: #fff;
  font-size: 18px;
  font-weight: 700;
}
</style>
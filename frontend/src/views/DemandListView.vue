<script setup lang="ts">
import { computed, reactive } from 'vue'
import { RouterLink } from 'vue-router'

import { DEMAND_CATEGORIES, type DemandRecord, type DemandSortMode } from '@/types/campushub'
import { useCampusHubStore } from '@/stores/campusHub'
import { formatDateTime, formatMoney, statusToneClass, truncateText } from '@/utils/format'

const store = useCampusHubStore()
const filters = reactive({
  q: '',
  category: '',
  location: '',
  sort: 'recommend' as DemandSortMode,
  page: 1,
  size: 6
})

const currentUserCategories = computed(() => {
  const weights = new Map<string, number>()

  store.currentUserOrders.forEach((order) => {
    const demand = store.getDemandById(order.demandId)
    if (!demand) {
      return
    }

    weights.set(demand.category, (weights.get(demand.category) ?? 0) + 2)
  })

  store.currentUserDemands.forEach((demand) => {
    weights.set(demand.category, (weights.get(demand.category) ?? 0) + 1)
  })

  return weights
})

const filteredDemands = computed(() => {
  const keyword = filters.q.trim().toLowerCase()
  const location = filters.location.trim().toLowerCase()

  const matched = store.demands.filter((demand) => {
    const keywordMatch =
      !keyword ||
      demand.title.toLowerCase().includes(keyword) ||
      demand.description.toLowerCase().includes(keyword) ||
      demand.tags.some((tag) => tag.toLowerCase().includes(keyword))

    const categoryMatch = !filters.category || demand.category === filters.category
    const locationMatch = !location || demand.location.toLowerCase().includes(location)

    return keywordMatch && categoryMatch && locationMatch
  })

  const sorted = [...matched].sort((left: DemandRecord, right: DemandRecord) => {
    if (filters.sort === 'reward') {
      return right.reward - left.reward
    }

    if (filters.sort === 'distance') {
      return left.distanceKm - right.distanceKm
    }

    if (filters.sort === 'recommend') {
      const leftScore = currentUserCategories.value.get(left.category) ?? 0
      const rightScore = currentUserCategories.value.get(right.category) ?? 0
      return rightScore - leftScore || right.createdAt.localeCompare(left.createdAt)
    }

    return right.createdAt.localeCompare(left.createdAt)
  })

  const start = (filters.page - 1) * filters.size
  return sorted.slice(start, start + filters.size)
})

const filteredTotal = computed(() => {
  const keyword = filters.q.trim().toLowerCase()
  const location = filters.location.trim().toLowerCase()

  return store.demands.filter((demand) => {
    const keywordMatch =
      !keyword ||
      demand.title.toLowerCase().includes(keyword) ||
      demand.description.toLowerCase().includes(keyword) ||
      demand.tags.some((tag) => tag.toLowerCase().includes(keyword))

    const categoryMatch = !filters.category || demand.category === filters.category
    const locationMatch = !location || demand.location.toLowerCase().includes(location)
    return keywordMatch && categoryMatch && locationMatch
  }).length
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredTotal.value / filters.size)))

function goPrev(): void {
  if (filters.page > 1) {
    filters.page -= 1
  }
}

function goNext(): void {
  if (filters.page < totalPages.value) {
    filters.page += 1
  }
}

function resetFilters(): void {
  filters.q = ''
  filters.category = ''
  filters.location = ''
  filters.sort = 'recommend'
  filters.page = 1
  filters.size = 6
}
</script>

<template>
  <div class="page-grid">
    <section class="panel">
      <div class="page-head">
        <div>
          <p class="eyebrow">P3 / DEM-05</p>
          <h1 class="page-title">需求列表与推荐排序</h1>
          <p class="page-summary">支持关键词、分类、地点筛选，以及时间、报酬、距离和推荐排序。</p>
        </div>
        <RouterLink class="button primary" to="/demands/new">发布需求</RouterLink>
      </div>

      <div class="filters">
        <div class="field">
          <label for="demand-q">关键词</label>
          <input id="demand-q" v-model="filters.q" placeholder="搜索标题、描述、标签" />
        </div>
        <div class="field">
          <label for="demand-category">分类</label>
          <select id="demand-category" v-model="filters.category">
            <option value="">全部分类</option>
            <option v-for="category in DEMAND_CATEGORIES" :key="category" :value="category">{{ category }}</option>
          </select>
        </div>
        <div class="field">
          <label for="demand-location">地点</label>
          <input id="demand-location" v-model="filters.location" placeholder="北区、图书馆、食堂" />
        </div>
        <div class="field">
          <label for="demand-sort">排序</label>
          <select id="demand-sort" v-model="filters.sort">
            <option value="recommend">推荐排序</option>
            <option value="time">时间优先</option>
            <option value="reward">报酬优先</option>
            <option value="distance">距离优先</option>
          </select>
        </div>
        <button type="button" class="button secondary" @click="resetFilters">重置筛选</button>
      </div>
    </section>

    <section class="demand-grid">
      <article v-for="demand in filteredDemands" :key="demand.id" class="list-card" :class="{ highlight: demand.approvalStatus === '待审核' }">
        <div class="status-row">
          <span class="chip" :class="statusToneClass(demand.approvalStatus)">{{ demand.approvalStatus }}</span>
          <span class="chip" :class="statusToneClass(demand.status)">{{ demand.status }}</span>
          <span class="chip">{{ demand.category }}</span>
        </div>

        <div class="card-head">
          <h3>{{ demand.title }}</h3>
          <strong>{{ formatMoney(demand.reward) }}</strong>
        </div>

        <p>{{ truncateText(demand.description, 92) }}</p>

        <div class="meta">
          {{ demand.location }} · 距离 {{ demand.distanceKm }} km · {{ formatDateTime(demand.createdAt) }}
        </div>

        <div class="tag-row">
          <span v-for="tag in demand.tags" :key="tag" class="badge is-neutral">{{ tag }}</span>
        </div>

        <div class="avatar-row">
          <img :src="demand.publisherAvatar" :alt="demand.publisherName" class="avatar" />
          <div>
            <strong>{{ demand.publisherName }}</strong>
            <div class="meta">发布者 · {{ demand.publisherId }}</div>
          </div>
        </div>

        <div class="card-actions">
          <RouterLink class="button primary" :to="`/demands/${demand.id}`">查看详情</RouterLink>
          <RouterLink class="button secondary" to="/orders">前往订单</RouterLink>
        </div>
      </article>
    </section>

    <section class="panel toolbar">
      <div class="stack">
        <strong>共 {{ filteredTotal }} 条结果</strong>
        <span class="meta">当前页 {{ filters.page }} / {{ totalPages }} · 每页 {{ filters.size }} 条</span>
      </div>
      <div class="inline-actions">
        <button type="button" class="button secondary" @click="goPrev">上一页</button>
        <button type="button" class="button secondary" @click="goNext">下一页</button>
      </div>
    </section>
  </div>
</template>
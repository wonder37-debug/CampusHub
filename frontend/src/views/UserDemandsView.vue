<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import type { DemandRecord } from '@/types/campushub'
import { handleError } from '@/utils/errorHandler'
import DemandHomeCard from '@/components/DemandHomeCard.vue'
import SkeletonCard from '@/components/SkeletonCard.vue'

const route = useRoute()
const router = useRouter()
const store = useCampusHubStore()

const userId = computed(() => String(route.params.id ?? ''))
const loading = ref(true)
const loadingMore = ref(false)
const error = ref('')
const PAGE_SIZE = 12
const page = ref(1)
const localDemands = ref<DemandRecord[]>([])
const totalCount = ref(0)
// 组件级请求版本号：切换用户或重新加载时递增，使仍在途中的旧请求返回后不处理结果
let requestVersion = 0

const hasMore = computed(() => localDemands.value.length < totalCount.value)
const allLoaded = computed(() => !hasMore.value && localDemands.value.length > 0)

async function loadDemands(): Promise<void> {
  const version = ++requestVersion
  const targetUser = userId.value
  loading.value = true
  error.value = ''
  page.value = 1
  try {
    await store.fetchDemands({
      publisherId: targetUser,
      page: 1,
      size: PAGE_SIZE
    })
    // 切换用户后旧请求不得更新新用户的列表、总数、页码或错误状态
    if (version !== requestVersion || targetUser !== userId.value) return
    if (store.demandFetchError) {
      error.value = store.demandFetchError
    } else {
      localDemands.value = [...store.demands]
      totalCount.value = store.demandFetchTotal
    }
  } catch (e) {
    if (version !== requestVersion || targetUser !== userId.value) return
    error.value = handleError(e, '需求列表加载失败')
  } finally {
    if (version === requestVersion) loading.value = false
  }
}

async function loadMore(): Promise<void> {
  if (loadingMore.value || !hasMore.value) return
  const version = ++requestVersion
  const targetUser = userId.value
  loadingMore.value = true
  const nextPage = page.value + 1
  try {
    await store.fetchDemands({
      publisherId: targetUser,
      page: nextPage,
      size: PAGE_SIZE
    })
    if (version !== requestVersion || targetUser !== userId.value) return
    if (store.demandFetchError) {
      error.value = store.demandFetchError
    } else {
      localDemands.value = [...localDemands.value, ...store.demands]
      totalCount.value = store.demandFetchTotal
      page.value = nextPage
    }
  } catch (e) {
    if (version !== requestVersion || targetUser !== userId.value) return
    error.value = handleError(e, '加载更多失败')
  } finally {
    if (version === requestVersion) loadingMore.value = false
  }
}

async function refresh(): Promise<void> {
  await loadDemands()
}

function backToProfile(): void {
  router.push(`/users/${userId.value}`)
}

watch(() => route.params.id, (newId) => {
  // 切换用户 ID 时清理旧列表并重新加载；递增版本号使仍在途中的旧请求失效
  if (newId) {
    localDemands.value = []
    totalCount.value = 0
    loadingMore.value = false
    void loadDemands()
  }
})

onMounted(() => {
  if (userId.value) void loadDemands()
})
</script>

<template>
  <div class="page-grid">
    <section class="panel">
      <div class="page-head">
        <div>
          <p class="eyebrow">公开需求</p>
          <h1 class="page-title">TA 发布的需求</h1>
          <p class="page-summary">该用户发布的公开需求列表，按时间倒序排列。</p>
        </div>
        <button type="button" class="button" @click="backToProfile">← 返回主页</button>
      </div>
    </section>

    <section v-if="loading" class="home-grid">
      <SkeletonCard v-for="n in 6" :key="n" />
    </section>

    <section v-else-if="error" class="panel">
      <div class="empty-state" style="--empty-icon:'⚠'">
        <strong>需求列表加载失败</strong>
        <p>{{ error }}</p>
        <button type="button" class="button secondary" style="margin-top:12px" @click="refresh">重新加载</button>
      </div>
    </section>

    <section v-else-if="!localDemands.length" class="panel">
      <div class="empty-state" style="--empty-icon:'📋'">
        <strong>暂无公开需求</strong>
        <p>该用户还没有发布公开需求，或需求尚在审核中。</p>
        <button type="button" class="button secondary" style="margin-top:12px" @click="backToProfile">返回主页</button>
      </div>
    </section>

    <section v-else class="home-grid">
      <DemandHomeCard
        v-for="demand in localDemands"
        :key="demand.id"
        :demand="demand"
        :current-user-id="store.currentUser?.id ?? ''"
      />

      <button v-if="hasMore" type="button" class="list-card load-more-panel" :disabled="loadingMore" @click="loadMore">
        <span class="load-more-title">{{ loadingMore ? '加载中...' : '加载更多' }}</span>
        <span class="load-more-count">当前显示 {{ localDemands.length }} / {{ totalCount }} 条</span>
      </button>
      <div v-if="allLoaded" class="load-more-done">📋 已展示全部 {{ totalCount }} 条公开需求</div>
    </section>
  </div>
</template>

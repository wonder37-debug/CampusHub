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
const error = ref('')
const PAGE_SIZE = 12
const page = ref(1)
const localDemands = ref<DemandRecord[]>([])
const totalCount = ref(0)

const hasMore = computed(() => localDemands.value.length < totalCount.value)
const allLoaded = computed(() => !hasMore.value && localDemands.value.length > 0)

async function loadDemands(): Promise<void> {
  loading.value = true
  error.value = ''
  page.value = 1
  try {
    await store.fetchDemands({
      publisherId: userId.value,
      page: 1,
      size: PAGE_SIZE
    })
    if (store.demandFetchError) {
      error.value = store.demandFetchError
    } else {
      localDemands.value = [...store.demands]
      totalCount.value = store.demandFetchTotal
    }
  } catch (e) {
    error.value = handleError(e, '需求列表加载失败')
  } finally {
    loading.value = false
  }
}

async function loadMore(): Promise<void> {
  page.value++
  try {
    await store.fetchDemands({
      publisherId: userId.value,
      page: page.value,
      size: PAGE_SIZE
    })
    if (store.demandFetchError) {
      page.value--
      error.value = store.demandFetchError
    } else {
      localDemands.value = [...localDemands.value, ...store.demands]
      totalCount.value = store.demandFetchTotal
    }
  } catch (e) {
    page.value--
    error.value = handleError(e, '加载更多失败')
  }
}

async function refresh(): Promise<void> {
  await loadDemands()
}

function backToProfile(): void {
  router.push(`/users/${userId.value}`)
}

watch(() => route.params.id, (newId) => {
  if (newId && String(newId) !== userId.value) {
    localDemands.value = []
    totalCount.value = 0
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

      <div v-if="hasMore" class="list-card load-more-panel" @click="loadMore">
        <h3>加载更多</h3>
        <p class="subtle">当前显示 {{ localDemands.length }} / {{ totalCount }} 条</p>
      </div>
      <div v-if="allLoaded" class="load-more-done">📋 已展示全部 {{ totalCount }} 条公开需求</div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatScore, formatRelativeTime } from '@/utils/format'
import SkeletonCard from '@/components/SkeletonCard.vue'

const route = useRoute()
const router = useRouter()
const store = useCampusHubStore()

const userId = computed(() => String(route.params.id ?? ''))
const loading = ref(true)
const loadingMoreReviews = ref(false)
const reviewPage = ref(1)
const REVIEW_PAGE_SIZE = 5
// 组件级请求版本号：切换用户或重新加载时递增，使仍在途中的旧请求返回后不更新加载状态
let requestVersion = 0

const profile = computed(() => store.publicUserProfile)
const reviews = computed(() => store.publicUserReviews)
const reviewsTotal = computed(() => store.publicUserReviewsTotal)
const stats = computed(() => store.publicUserStats)
const isSelf = computed(() => store.currentUser?.id === userId.value)

const profileError = computed(() => store.publicUserProfileError)
const reviewsError = computed(() => store.publicUserReviewsError)
const statsError = computed(() => store.publicUserStatsError)

const hasMoreReviews = computed(() => reviews.value.length < reviewsTotal.value)

function initials(name: string): string {
  return name?.slice(0, 1) ?? '?'
}

async function loadProfile(): Promise<void> {
  const version = ++requestVersion
  const targetUser = userId.value
  loading.value = true
  reviewPage.value = 1
  try {
    await Promise.all([
      store.fetchPublicUserProfile(targetUser),
      store.fetchPublicUserReviews(targetUser, 1, REVIEW_PAGE_SIZE),
      store.fetchPublicUserStats(targetUser)
    ])
  } catch (e) {
    // errors are stored in store state
  } finally {
    // 仅当仍是最新请求时才结束加载态，避免旧请求的 finally 错误重置新请求的 loading
    if (version === requestVersion) loading.value = false
  }
}

async function loadMoreReviews(): Promise<void> {
  if (loadingMoreReviews.value || !hasMoreReviews.value) return
  const version = ++requestVersion
  const targetUser = userId.value
  loadingMoreReviews.value = true
  const nextPage = reviewPage.value + 1
  try {
    // 仅当 store 确认数据已写入且组件请求仍有效时才推进页码，避免跳页或旧请求污染
    const written = await store.fetchPublicUserReviews(targetUser, nextPage, REVIEW_PAGE_SIZE)
    if (version !== requestVersion || targetUser !== userId.value) return
    if (written) {
      reviewPage.value = nextPage
    }
  } finally {
    if (version === requestVersion) loadingMoreReviews.value = false
  }
}

function viewDemands(): void {
  router.push(`/users/${userId.value}/demands`)
}

function goBack(): void {
  if (window.history.length > 1) router.back()
  else router.push('/demands')
}

watch(() => route.params.id, (newId) => {
  // 切换用户 ID 时清理旧数据并重新加载；store 层的请求版本号会丢弃仍在途中的旧请求
  if (newId) {
    store.publicUserProfile = null
    store.publicUserReviews = []
    store.publicUserReviewsTotal = 0
    store.publicUserStats = null
    loadingMoreReviews.value = false
    void loadProfile()
  }
})

onMounted(() => {
  if (userId.value) void loadProfile()
})
</script>

<template>
  <div class="page-grid">
    <!-- 加载中 -->
    <section v-if="loading" class="panel">
      <SkeletonCard v-for="n in 2" :key="n" />
    </section>

    <!-- 用户信息加载失败 -->
    <section v-else-if="profileError && !profile" class="panel">
      <div class="empty-state" style="--empty-icon:'⚠'">
        <strong>用户信息加载失败</strong>
        <p>{{ profileError }}</p>
        <button type="button" class="button secondary" style="margin-top:12px" @click="loadProfile">重新加载</button>
      </div>
    </section>

    <!-- 公开主页内容 -->
    <template v-else-if="profile">
      <section class="panel">
        <div class="page-head">
          <div class="profile-public-header">
            <div class="profile-public-avatar" :class="{ 'is-placeholder': !profile.avatarUrl }">
              <img v-if="profile.avatarUrl" :src="profile.avatarUrl" :alt="profile.nickname" />
              <span v-else>{{ initials(profile.nickname) }}</span>
            </div>
            <div>
              <p class="eyebrow">公开主页</p>
              <h1 class="page-title">{{ profile.nickname }}</h1>
              <p class="page-summary">
                信用分 {{ formatScore(profile.creditScore) }}
                <span v-if="isSelf" class="chip is-neutral" style="margin-left:8px">这是你自己</span>
              </p>
            </div>
          </div>
          <button type="button" class="button" @click="goBack">返回</button>
        </div>

        <div class="mini-grid">
          <div class="mini-stat">
            <span class="subtle">信用分</span>
            <strong>{{ formatScore(profile.creditScore) }}</strong>
            <small class="subtle" style="margin-top:4px;display:block;font-size:11px;">基于历史评价动态计算，非星级平均</small>
          </div>
          <div class="mini-stat">
            <span class="subtle">已完成订单</span>
            <strong v-if="stats">{{ stats.completedOrders }}</strong>
            <strong v-else-if="statsError" style="color:var(--danger)">—</strong>
            <strong v-else>…</strong>
            <small class="subtle" style="margin-top:4px;display:block;font-size:11px;">真实正式订单，不含报名或回答</small>
          </div>
          <div class="mini-stat">
            <span class="subtle">收到的评价</span>
            <strong>{{ reviewsTotal }}</strong>
          </div>
        </div>
      </section>

      <!-- 最近评价 -->
      <section class="panel">
        <div class="page-head">
          <div>
            <p class="eyebrow">信任档案</p>
            <h2 class="section-title">最近收到的评价</h2>
          </div>
          <button v-if="reviewsTotal > reviews.length" type="button" class="button secondary" :disabled="loadingMoreReviews" @click="loadMoreReviews">
            {{ loadingMoreReviews ? '加载中...' : '加载更多' }}
          </button>
        </div>

        <div v-if="reviewsError" class="empty-state" style="--empty-icon:'⚠'">
          <strong>评价加载失败</strong>
          <p>{{ reviewsError }}</p>
          <button type="button" class="button secondary" style="margin-top:12px" @click="loadProfile">重新加载</button>
        </div>

        <div v-else-if="reviews.length === 0" class="empty-state" style="--empty-icon:'⭐'">
          <strong>暂无评价</strong>
          <p>该用户还没有收到评价。信用分为初始值，不代表已有履约记录。</p>
        </div>

        <div v-else class="review-list">
          <div v-for="review in reviews" :key="review.id" class="review-item">
            <div class="review-item-header">
              <span class="chip is-success">{{ review.rating }} 星</span>
              <span v-if="review.targetName" class="review-order-title">{{ review.targetName }}</span>
              <span class="meta">{{ formatRelativeTime(review.createdAt) }}</span>
            </div>
            <div class="review-item-body">
              <span v-if="review.reviewerName" class="review-partner">{{ review.reviewerName }}</span>
              <p class="review-comment">{{ review.comment || '仅评分，未填写文字评价' }}</p>
            </div>
          </div>
        </div>
      </section>

      <!-- 查看公开需求入口 -->
      <section class="panel">
        <button type="button" class="button primary" style="width:100%" @click="viewDemands">
          查看 TA 发布的公开需求 →
        </button>
      </section>
    </template>

    <!-- 用户不存在 -->
    <section v-else class="panel">
      <div class="empty-state" style="--empty-icon:'👤'">
        <strong>用户不存在</strong>
        <p>该用户可能已注销或链接无效。</p>
        <button type="button" class="button secondary" style="margin-top:12px" @click="goBack">返回</button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.profile-public-header {
  display: flex;
  align-items: center;
  gap: 16px;
}

.profile-public-avatar {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  overflow: hidden;
  flex-shrink: 0;
  background: var(--card);
  border: 1px solid var(--border);
  display: grid;
  place-items: center;
  font-size: 24px;
  font-weight: 700;
  color: var(--text-muted);
}

.profile-public-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.profile-public-avatar.is-placeholder {
  background: #edf4ef;
  color: #366d57;
}
</style>

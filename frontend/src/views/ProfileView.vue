<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatMoney, formatRelativeTime, formatScore, formatUserRole, formatUserStatus } from '@/utils/format'

const store = useCampusHubStore()
const router = useRouter()

const creditLevel = computed(() => {
  const score = store.currentUser?.creditScore ?? 0
  if (score >= 95) return '金牌助教'
  if (score >= 85) return '银牌助教'
  return '成长中'
})

function openEditPage(): void {
  router.push('/profile/edit')
}

function logout(): void {
  store.logout()
  router.push('/auth')
}

onMounted(() => {
  void store.fetchProfile()
  void store.fetchCurrentUserReviews()
})
</script>

<template>
  <div v-if="store.currentUser" class="page-grid">
    <section class="panel">
      <div class="avatar-row">
        <img :src="store.currentUser.avatarUrl" :alt="store.currentUser.nickname" class="avatar large" />
        <div>
          <p class="eyebrow">个人中心</p>
          <h1 class="page-title">{{ store.currentUser.nickname }}</h1>
          <p class="page-summary">学号：{{ store.currentUser.studentId }}</p>
          <div class="stats-row" style="gap:12px;">
            <div class="meta">当前身份：<strong>{{ formatUserRole(store.currentUser.role) }}</strong></div>
            <div class="meta">当前状态：<strong>{{ formatUserStatus(store.currentUser.status) }}</strong></div>
          </div>
          <button type="button" class="button secondary" @click="openEditPage">编辑资料</button>
        </div>
      </div>

      <div class="mini-grid">
        <div class="mini-stat">
          <span class="subtle">信用分</span>
          <strong>{{ formatScore(store.currentUser.creditScore) }}</strong>
        </div>
        <div class="mini-stat">
          <span class="subtle">可用余额</span>
          <strong>{{ formatMoney(store.currentUser.balance) }}</strong>
        </div>
        <div class="mini-stat">
          <span class="subtle">冻结金额</span>
          <strong>{{ formatMoney(store.currentUser.frozenBalance) }}</strong>
        </div>
        <div class="mini-stat">
          <span class="subtle">信用等级</span>
          <strong>{{ creditLevel }}</strong>
        </div>
        <div class="mini-stat">
          <span class="subtle">已完成订单</span>
          <strong>{{ store.currentUserOrders.filter((order) => order.status === 'COMPLETED').length }}</strong>
        </div>
      </div>
    </section>

    <!-- 功能入口已移除，退出登录按钮已移动到页面底部 -->

    <section class="panel">
      <p class="eyebrow">评价列表</p>
      <h2 class="section-title">我的评价</h2>

      <div v-if="store.currentUserReviews.length" class="review-grid">
        <div v-for="review in store.currentUserReviews" :key="review.id" class="list-card">
          <div class="status-row">
            <span class="chip is-success">{{ review.rating }} 星</span>
            <span class="meta">{{ formatRelativeTime(review.createdAt) }}</span>
          </div>
          <strong>{{ review.reviewerName }} → {{ review.targetName }}</strong>
          <p class="subtle">{{ review.comment || '暂无评价内容' }}</p>
        </div>
      </div>

      <div v-else class="empty-state">
        <strong>暂无评价</strong>
      </div>
    </section>

    <!-- 页面底部：退出登录（横向全宽样式） -->
    <section class="panel" style="margin-top: 16px;">
      <div>
        <button type="button" class="button primary" style="width:100%;" @click="logout">退出登录</button>
      </div>
    </section>
  </div>

  <div v-else class="empty-state">
    <strong>请先登录</strong>
    <p>前往认证页后再查看个人中心。</p>
    <button type="button" class="button primary" @click="router.push('/auth')">去认证页</button>
  </div>
</template>
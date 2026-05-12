<script setup lang="ts">
import { computed, reactive, ref } from 'vue'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatDateTime, formatScore, formatUserRole, formatUserStatus } from '@/utils/format'

const store = useCampusHubStore()
const message = ref('')
const error = ref('')

const profileForm = reactive({
  nickname: store.currentUser?.nickname ?? '',
  avatarUrl: store.currentUser?.avatarUrl ?? ''
})

const userReviews = computed(() => store.currentUserReviews.filter((review) => review.targetId === store.currentUser?.id))
const givenReviews = computed(() => store.currentUserReviews.filter((review) => review.reviewerId === store.currentUser?.id))

function updateProfile(): void {
  error.value = ''
  message.value = ''

  try {
    store.updateProfile(profileForm)
    message.value = '个人资料已更新。'
  } catch (profileError) {
    error.value = profileError instanceof Error ? profileError.message : '更新失败'
  }
}
</script>

<template>
  <div v-if="store.currentUser" class="page-grid two-column">
    <section class="panel">
      <div class="avatar-row">
        <img :src="store.currentUser.avatarUrl" :alt="store.currentUser.nickname" class="avatar large" />
        <div>
          <p class="eyebrow">我的资料</p>
          <h1 class="page-title">{{ store.currentUser.nickname }}</h1>
          <p class="page-summary">{{ store.currentUser.studentId }} · {{ store.currentUser.email }}</p>
          <div class="stats-row">
            <span class="chip is-neutral">{{ formatUserRole(store.currentUser.role) }}</span>
            <span class="chip is-neutral">{{ formatUserStatus(store.currentUser.status) }}</span>
            <span class="chip is-success">{{ formatScore(store.currentUser.creditScore) }}</span>
          </div>
          <p class="subtle">你可以在这里查看个人信息、修改昵称和头像，并浏览收到的评价。</p>
        </div>
      </div>

      <div class="mini-grid">
        <div class="mini-stat"><span class="subtle">我的需求</span><strong>{{ store.currentUserDemands.length }}</strong></div>
        <div class="mini-stat"><span class="subtle">我的订单</span><strong>{{ store.currentUserOrders.length }}</strong></div>
        <div class="mini-stat"><span class="subtle">我的评价</span><strong>{{ userReviews.length }}</strong></div>
        <div class="mini-stat"><span class="subtle">我已评价</span><strong>{{ givenReviews.length }}</strong></div>
      </div>

      <div class="form-grid two-column">
        <div class="field">
          <label for="profile-nickname">昵称</label>
          <input id="profile-nickname" v-model="profileForm.nickname" />
        </div>
        <div class="field">
          <label for="profile-avatar">头像链接</label>
          <input id="profile-avatar" v-model="profileForm.avatarUrl" />
        </div>
        <button type="button" class="button primary" style="grid-column: 1 / -1;" @click="updateProfile">保存资料</button>
      </div>

      <p v-if="message" class="hero-badge">{{ message }}</p>
      <p v-if="error" class="hero-badge" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>
    </section>

    <section class="section-grid">
      <article class="panel">
        <p class="eyebrow">收到的评价</p>
        <h2 class="section-title">收到的评价</h2>
        <div v-if="userReviews.length" class="review-grid">
          <div v-for="review in userReviews" :key="review.id" class="timeline-item">
            <div>
              <strong>{{ review.reviewerName }} 评价你为 {{ review.rating }} 星</strong>
              <div class="meta">{{ review.comment }}</div>
              <div class="meta">{{ formatDateTime(review.createdAt) }}</div>
            </div>
          </div>
        </div>
        <div v-else class="empty-state">当前没有收到评价。</div>
      </article>

      <article class="panel">
        <p class="eyebrow">我提交的评价</p>
        <h2 class="section-title">历史评价记录</h2>
        <div v-if="givenReviews.length" class="review-grid">
          <div v-for="review in givenReviews" :key="review.id" class="timeline-item">
            <div>
              <strong>→ {{ review.targetName }}</strong>
              <div class="meta">{{ review.comment }}</div>
              <div class="meta">{{ formatDateTime(review.createdAt) }}</div>
            </div>
            <span class="chip is-success">{{ review.rating }} 星</span>
          </div>
        </div>
        <div v-else class="empty-state">还没有提交过评价。</div>
      </article>
    </section>
  </div>

  <div v-else class="empty-state">
    <strong>当前未登录</strong>
    <p>请先前往认证页登录或注册账号。</p>
  </div>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatOrderStatus, formatRelativeTime, formatScore, statusToneClass } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const store = useCampusHubStore()

const order = computed(() => store.getOrderById(String(route.params.id)))
const isRequester = computed(() => order.value && store.currentUser?.id === order.value.requesterId)
const isProvider = computed(() => order.value && store.currentUser?.id === order.value.serviceProviderId)
const message = ref('')
const error = ref('')
const completionSubmitted = ref(false)
const reviewRating = ref('5')
const reviewComment = ref('')

const relatedReviews = computed(() => {
  if (!order.value) return []
  return store.reviews.filter((r) => r.orderId === order.value!.id)
})

const hasSubmittedReview = computed(() => {
  if (!order.value || !store.currentUser) return false
  return relatedReviews.value.some((r) => r.reviewerId === store.currentUser?.id)
})

function goBack(): void {
  router.back()
}

async function startOrder(): Promise<void> {
  if (order.value) await store.startOrder(order.value.id)
}

async function completeOrder(): Promise<void> {
  if (!order.value) return

  message.value = ''
  error.value = ''

  try {
    const updatedOrder = await store.completeOrder(order.value.id)
    completionSubmitted.value = updatedOrder.status !== 'COMPLETED'
    message.value = updatedOrder.status === 'COMPLETED'
      ? '双方都已确认完成，订单已完成。'
      : '已提交完成确认，等待对方确认。'
  } catch (completeError) {
    error.value = completeError instanceof Error ? completeError.message : '操作失败'
  }
}

async function cancelOrder(): Promise<void> {
  if (order.value) await store.cancelOrder(order.value.id)
}

async function submitReview(): Promise<void> {
  if (!order.value) return
  message.value = ''
  error.value = ''
  try {
    await store.submitReview(order.value.id, Number(reviewRating.value), reviewComment.value)
    message.value = '评价已提交。'
    reviewComment.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : '评价失败'
  }
}

// (no automatic demand enrichment) keep original simple behavior
</script>

<template>
  <div v-if="order" class="page-grid">
    <section class="panel">
      <div class="page-head">
        <button type="button" class="button secondary" @click="goBack">返回</button>
        <div>
          <p class="eyebrow">订单详情</p>
          <h1 class="page-title">{{ order.demandTitle }}</h1>
          <p class="page-summary">{{ formatRelativeTime(order.createdAt) }}</p>
        </div>
      </div>

      <div class="status-row">
        <span class="chip" :class="statusToneClass(order.status)">{{ formatOrderStatus(order.status) }}</span>
      </div>

      <div class="mini-grid">
        <div class="mini-stat"><span class="subtle">需求方</span><strong>{{ order.requesterName }}</strong><div class="meta">信用分 {{ formatScore(order.requesterCreditScore) }}</div></div>
        <div class="mini-stat"><span class="subtle">接单方</span><strong>{{ order.serviceProviderName }}</strong><div class="meta">信用分 {{ formatScore(order.serviceProviderCreditScore) }}</div></div>
        <div class="mini-stat"><span class="subtle">留言</span><strong>{{ order.note || '无' }}</strong></div>
      </div>

      <div class="mini-grid" style="margin-top:12px;">
        <div class="mini-stat"><span class="subtle">地点</span><strong>{{ order.demandLocation || '未填写' }}</strong></div>
        <div class="mini-stat"><span class="subtle">开始时间</span><strong>{{ order.demandStartTime ? new Date(order.demandStartTime).toLocaleString() : '—' }}</strong></div>
        <div class="mini-stat"><span class="subtle">结束时间</span><strong>{{ order.demandEndTime ? new Date(order.demandEndTime).toLocaleString() : '—' }}</strong></div>
        <div class="mini-stat"><span class="subtle">报酬</span><strong>{{ order.demandReward ? order.demandReward + ' 元' : '无' }}</strong></div>
      </div>

      <p v-if="message" class="hero-badge">{{ message }}</p>
      <p v-if="error" class="hero-badge" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>

      <div class="timeline" style="margin-top: 16px;">
        <div v-for="entry in order.timeline" :key="`${entry.at}-${entry.label}`" class="timeline-item">
          <span>{{ entry.label }}</span>
          <span class="meta">{{ formatRelativeTime(entry.at) }}</span>
        </div>
      </div>
      <div v-if="order.status === 'COMPLETED'" class="section-grid" style="margin-top: 16px;">
        <div class="list-card">
          <strong>相关评价</strong>
          <div class="review-grid">
            <div v-if="relatedReviews.length === 0" class="empty-state">暂无评价记录</div>
            <div v-for="review in relatedReviews" :key="review.id" class="timeline-item">
              <div>
                <strong>{{ review.reviewerName }} → {{ review.targetName }}</strong>
                <div class="meta">{{ review.comment }}</div>
              </div>
              <span class="chip is-success">{{ review.rating }} 星</span>
            </div>
          </div>
        </div>

        <div class="list-card" v-if="!hasSubmittedReview && (isRequester || isProvider)">
          <strong>提交评价</strong>
          <div class="field">
            <label for="review-rating">评分</label>
            <select id="review-rating" v-model="reviewRating">
              <option value="5">5 分</option>
              <option value="4">4 分</option>
              <option value="3">3 分</option>
              <option value="2">2 分</option>
              <option value="1">1 分</option>
            </select>
          </div>
          <div class="field">
            <label for="review-comment">评价</label>
            <textarea id="review-comment" v-model="reviewComment" placeholder="分享你的体验"></textarea>
          </div>
          <button type="button" class="button primary" @click="submitReview">提交评价</button>
        </div>
      </div>
    </section>

    <section class="panel">
      <p class="eyebrow">操作</p>
      <div class="card-actions">
        <button v-if="order.status === 'ACCEPTED' && isProvider" type="button" class="button secondary" @click="startOrder">开始执行</button>
        <button
          v-if="order.status === 'IN_PROGRESS' && isProvider && !completionSubmitted"
          type="button"
          class="button primary"
          @click="completeOrder"
        >
          提交完成确认
        </button>
        <button
          v-else-if="order.status === 'IN_PROGRESS' && isRequester && !completionSubmitted"
          type="button"
          class="button primary"
          @click="completeOrder"
        >
          确认完成
        </button>
        <span v-else-if="order.status === 'IN_PROGRESS'" class="chip is-warning">等待对方确认完成</span>
        <button v-if="order.status === 'ACCEPTED' && isRequester" type="button" class="button secondary" @click="cancelOrder">取消订单</button>
      </div>
    </section>
  </div>

  <div v-else class="empty-state">
    <strong>未找到订单</strong>
  </div>
</template>

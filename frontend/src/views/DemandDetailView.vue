<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatCampusZone, formatDateTime, formatDemandCategory, formatDemandStatus, formatMoney, formatOrderStatus, formatScore, statusToneClass } from '@/utils/format'

const route = useRoute()
const store = useCampusHubStore()
const note = ref('')
const reviewRating = ref('5')
const reviewComment = ref('')
const message = ref('')
const error = ref('')

const demand = computed(() => store.getDemandById(String(route.params.id)))
const relatedOrder = computed(() => store.orders.find((order) => order.demandId === demand.value?.id))
const relatedReviews = computed(() => store.reviews.filter((review) => review.orderId === relatedOrder.value?.id))

async function acceptCurrentDemand(): Promise<void> {
  if (!demand.value) {
    return
  }

  error.value = ''
  message.value = ''

  try {
    const order = await store.acceptDemand(demand.value.id, note.value)
    message.value = `已接单，生成订单 ${order.id}`
  } catch (acceptError) {
    error.value = acceptError instanceof Error ? acceptError.message : '接单失败'
  }
}

async function startOrder(): Promise<void> {
  if (!relatedOrder.value) {
    return
  }

  try {
    await store.startOrder(relatedOrder.value.id)
    message.value = '订单已进入进行中状态。'
  } catch (startError) {
    error.value = startError instanceof Error ? startError.message : '操作失败'
  }
}

async function completeOrder(): Promise<void> {
  if (!relatedOrder.value) {
    return
  }

  try {
    await store.completeOrder(relatedOrder.value.id)
    message.value = '订单已完成，可以提交评价。'
  } catch (completeError) {
    error.value = completeError instanceof Error ? completeError.message : '操作失败'
  }
}

async function submitReview(): Promise<void> {
  if (!relatedOrder.value) {
    return
  }

  error.value = ''
  message.value = ''

  try {
    await store.submitReview(relatedOrder.value.id, Number(reviewRating.value), reviewComment.value)
    message.value = '评价已提交。'
    reviewComment.value = ''
  } catch (reviewError) {
    error.value = reviewError instanceof Error ? reviewError.message : '评价失败'
  }
}
</script>

<template>
  <div v-if="demand" class="page-grid two-column">
    <section class="panel">
      <div class="status-row">
        <span class="chip" :class="relatedOrder ? statusToneClass(relatedOrder.status) : statusToneClass(demand.status)">
          {{ relatedOrder ? formatOrderStatus(relatedOrder.status) : formatDemandStatus(demand.status) }}
        </span>
        <span class="chip">{{ formatDemandCategory(demand.category) }}</span>
        <span class="chip">{{ formatCampusZone(demand.campusZone) }}</span>
        <span v-if="demand.anonymous" class="chip is-warning">匿名</span>
      </div>

      <div class="page-head">
        <div>
          <p class="eyebrow">需求详情</p>
          <h1 class="page-title">{{ demand.title }}</h1>
          <p class="page-summary">{{ demand.description }}</p>
        </div>
        <strong class="page-title">{{ formatMoney(demand.reward) }}</strong>
      </div>

      <div class="avatar-row">
        <img :src="demand.publisher?.avatarUrl ?? demand.publisherAvatar" :alt="demand.publisher?.nickname ?? demand.publisherName" class="avatar large" />
        <div>
          <strong>{{ demand.anonymous ? demand.anonymousCode ?? '匿名发布' : (demand.publisher?.nickname ?? demand.publisherName) }}</strong>
          <p class="subtle">发布者编号 {{ demand.publisherId || '匿名' }} · {{ demand.publisher ? formatScore(demand.publisher.creditScore) : '未知' }}</p>
          <p class="meta">{{ demand.location }} · {{ formatDateTime(demand.createdAt) }}</p>
        </div>
      </div>

      <div class="tag-row">
        <span class="badge is-neutral">{{ formatCampusZone(demand.campusZone) }}</span>
        <span class="badge is-neutral">{{ demand.anonymous ? '匿名发布' : '实名发布' }}</span>
      </div>

      <div class="tag-row">
        <span v-for="tag in demand.tags" :key="tag" class="badge is-neutral">{{ tag }}</span>
      </div>

      <div class="list-card">
        <strong>接单留言</strong>
        <div class="field">
          <label for="accept-note">留言内容</label>
          <textarea id="accept-note" v-model="note" placeholder="给发布者留一句话"></textarea>
        </div>
        <div class="card-actions">
          <button
            v-if="!relatedOrder && demand.status === 'PENDING' && store.currentUser?.id !== demand.publisherId"
            type="button"
            class="button primary"
            @click="acceptCurrentDemand"
          >
            立即接单
          </button>
          <span v-else class="chip is-warning">当前需求暂时不可接单</span>

          <button v-if="relatedOrder?.status === 'ACCEPTED'" type="button" class="button secondary" @click="startOrder">开始执行</button>
          <button v-if="relatedOrder?.status === 'IN_PROGRESS'" type="button" class="button secondary" @click="completeOrder">完成确认</button>
        </div>
      </div>

      <p v-if="message" class="hero-badge">{{ message }}</p>
      <p v-if="error" class="hero-badge" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>
    </section>

    <section class="panel">
      <p class="eyebrow">关联信息</p>
      <h2 class="section-title">订单、时间线与评价</h2>

      <div v-if="relatedOrder" class="section-grid">
        <div class="list-card">
          <div class="status-row">
            <span class="chip" :class="statusToneClass(relatedOrder.status)">{{ formatOrderStatus(relatedOrder.status) }}</span>
            <span class="chip">订单 {{ relatedOrder.id }}</span>
          </div>
          <div class="avatar-row">
            <img :src="relatedOrder.serviceProviderAvatar" :alt="relatedOrder.serviceProviderName" class="avatar" />
            <div>
              <strong>{{ relatedOrder.serviceProviderName }}</strong>
              <div class="meta">接单方 · 信用分 {{ formatScore(relatedOrder.serviceProviderCreditScore) }}</div>
            </div>
          </div>
          <div class="meta" style="margin-top: 8px;">需求方 · 信用分 {{ formatScore(relatedOrder.requesterCreditScore) }}</div>
          <p>{{ relatedOrder.note || '暂无留言' }}</p>
        </div>

        <div class="list-card">
          <strong>流程时间线</strong>
          <div class="timeline">
            <div v-for="entry in relatedOrder.timeline" :key="`${entry.at}-${entry.label}`" class="timeline-item">
              <span>{{ entry.label }}</span>
              <span class="meta">{{ formatDateTime(entry.at) }}</span>
            </div>
          </div>
        </div>

        <div v-if="relatedOrder.status === 'COMPLETED'" class="list-card">
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

        <div v-if="relatedReviews.length" class="list-card">
          <strong>相关评价</strong>
          <div class="review-grid">
            <div v-for="review in relatedReviews" :key="review.id" class="timeline-item">
              <div>
                <strong>{{ review.reviewerName }} → {{ review.targetName }}</strong>
                <div class="meta">{{ review.comment }}</div>
              </div>
              <span class="chip is-success">{{ review.rating }} 星</span>
            </div>
          </div>
        </div>
      </div>

      <div v-else class="empty-state">
        <strong>当前还没有订单记录</strong>
        <p>你可以先在该需求上完成接单操作，再回到这里查看订单与评价流程。</p>
      </div>
    </section>
  </div>

  <div v-else class="empty-state">
    <strong>未找到需求</strong>
    <p>请返回需求列表重新选择一个条目。</p>
  </div>
</template>
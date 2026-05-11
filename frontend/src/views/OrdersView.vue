<script setup lang="ts">
import { computed, ref } from 'vue'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatDateTime, formatOrderStatus, statusToneClass } from '@/utils/format'

const store = useCampusHubStore()
const activeFilter = ref<'all' | 'requester' | 'provider'>('all')
const reviewOrderId = ref('')
const reviewRating = ref('5')
const reviewComment = ref('')
const message = ref('')
const error = ref('')

const orders = computed(() => {
  const currentUserId = store.currentUser?.id
  const visible = store.currentUserOrders

  if (activeFilter.value === 'requester') {
    return visible.filter((order) => order.requesterId === currentUserId)
  }

  if (activeFilter.value === 'provider') {
    return visible.filter((order) => order.serviceProviderId === currentUserId)
  }

  return visible
})

function beginOrder(orderId: string): void {
  try {
    store.startOrder(orderId)
    message.value = '订单已切换为进行中。'
  } catch (startError) {
    error.value = startError instanceof Error ? startError.message : '操作失败'
  }
}

function finishOrder(orderId: string): void {
  try {
    store.completeOrder(orderId)
    message.value = '订单已完成。'
  } catch (completeError) {
    error.value = completeError instanceof Error ? completeError.message : '操作失败'
  }
}

function sendReview(): void {
  if (!reviewOrderId.value) {
    error.value = '请先选择一个已完成的订单进行评价。'
    return
  }

  try {
    store.submitReview(reviewOrderId.value, Number(reviewRating.value), reviewComment.value)
    message.value = '评价已提交。'
    reviewComment.value = ''
  } catch (reviewError) {
    error.value = reviewError instanceof Error ? reviewError.message : '评价失败'
  }
}
</script>

<template>
  <div class="page-grid">
    <section class="panel">
      <div class="page-head">
        <div>
          <p class="eyebrow">ORD-05 / REV-05</p>
          <h1 class="page-title">订单列表与完成确认</h1>
          <p class="page-summary">这里展示作为需求方或服务方时的订单视图，并支持状态流转和评价提交。</p>
        </div>
      </div>

      <div class="segment-row">
        <button type="button" class="button" :class="activeFilter === 'all' ? 'primary' : 'secondary'" @click="activeFilter = 'all'">全部</button>
        <button type="button" class="button" :class="activeFilter === 'requester' ? 'primary' : 'secondary'" @click="activeFilter = 'requester'">作为需求方</button>
        <button type="button" class="button" :class="activeFilter === 'provider' ? 'primary' : 'secondary'" @click="activeFilter = 'provider'">作为服务方</button>
      </div>
    </section>

    <section class="order-grid">
      <article v-for="order in orders" :key="order.id" class="list-card">
        <div class="status-row">
          <span class="chip" :class="statusToneClass(order.status)">{{ formatOrderStatus(order.status) }}</span>
          <span class="chip">{{ order.id }}</span>
        </div>

        <div class="card-head">
          <h3>{{ order.demandTitle }}</h3>
          <span class="meta">{{ formatDateTime(order.createdAt) }}</span>
        </div>

        <div class="avatar-row">
          <img :src="order.requesterAvatar" :alt="order.requesterName" class="avatar" />
          <div>
            <strong>{{ order.requesterName }}</strong>
            <div class="meta">需求方</div>
          </div>
          <span>→</span>
          <img :src="order.serviceProviderAvatar" :alt="order.serviceProviderName" class="avatar" />
          <div>
            <strong>{{ order.serviceProviderName }}</strong>
            <div class="meta">服务方</div>
          </div>
        </div>

        <p>{{ order.note || '暂无留言' }}</p>
        <div class="meta">凭证提交：{{ order.proofSubmitted ? `${order.proofImageCount} 张图片` : '未提交' }}</div>

        <div class="timeline">
          <div v-for="entry in order.timeline" :key="`${order.id}-${entry.at}-${entry.label}`" class="timeline-item">
            <span>{{ entry.label }}</span>
            <span class="meta">{{ formatDateTime(entry.at) }}</span>
          </div>
        </div>

        <div class="card-actions">
          <button v-if="order.status === 'ACCEPTED' && store.currentUser?.id === order.serviceProviderId" type="button" class="button secondary" @click="beginOrder(order.id)">开始执行</button>
          <button v-if="order.status === 'IN_PROGRESS' && store.currentUser?.id === order.serviceProviderId" type="button" class="button primary" @click="finishOrder(order.id)">完成确认</button>
          <button
            v-if="order.status === 'COMPLETED'"
            type="button"
            class="button secondary"
            @click="reviewOrderId = order.id"
          >
            评价此单
          </button>
        </div>
      </article>
    </section>

    <section class="two-column page-grid">
      <article class="panel">
        <p class="eyebrow">评价入口</p>
        <h2 class="section-title">提交订单评价</h2>
        <div class="field">
          <label for="order-review-id">订单</label>
          <select id="order-review-id" v-model="reviewOrderId">
            <option value="">请选择订单</option>
            <option v-for="order in orders.filter((item) => item.status === 'COMPLETED')" :key="order.id" :value="order.id">{{ order.demandTitle }} · {{ order.id }}</option>
          </select>
        </div>
        <div class="field">
          <label for="order-review-rating">评分</label>
          <select id="order-review-rating" v-model="reviewRating">
            <option value="5">5 分</option>
            <option value="4">4 分</option>
            <option value="3">3 分</option>
            <option value="2">2 分</option>
            <option value="1">1 分</option>
          </select>
        </div>
        <div class="field">
          <label for="order-review-comment">评价</label>
          <textarea id="order-review-comment" v-model="reviewComment" placeholder="输入你的文字评价"></textarea>
        </div>
        <button type="button" class="button primary" @click="sendReview">提交评价</button>
      </article>

      <article class="panel helper-card">
        <p class="eyebrow">流程说明</p>
        <h2 class="section-title">与 P4 看板一致的按钮策略</h2>
        <div class="timeline">
          <div class="timeline-item"><span>ACCEPTED</span><span>显示开始执行</span></div>
          <div class="timeline-item"><span>IN_PROGRESS</span><span>显示完成确认</span></div>
          <div class="timeline-item"><span>COMPLETED</span><span>允许评价提交</span></div>
        </div>
      </article>
    </section>

    <p v-if="message" class="hero-badge">{{ message }}</p>
    <p v-if="error" class="hero-badge" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>
  </div>
</template>
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

      <p v-if="message" class="hero-badge">{{ message }}</p>
      <p v-if="error" class="hero-badge" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>

      <div class="timeline" style="margin-top: 16px;">
        <div v-for="entry in order.timeline" :key="`${entry.at}-${entry.label}`" class="timeline-item">
          <span>{{ entry.label }}</span>
          <span class="meta">{{ formatRelativeTime(entry.at) }}</span>
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

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatOrderStatus, formatRelativeTime, statusToneClass, formatDemandStatus } from '@/utils/format'

const store = useCampusHubStore()
const router = useRouter()
const activeTab = ref<'published' | 'accepted'>('published')

const visibleOrders = computed(() => {
  const currentUserId = store.currentUser?.id
  if (!currentUserId) return []

  if (activeTab.value === 'accepted') {
    return store.orders.filter((order) => order.serviceProviderId === currentUserId)
  }

  // published: include actual orders where current user is requester,
  // plus published demands by current user that have no order yet (as placeholders)
  const publishedOrders = store.orders.filter((order) => order.requesterId === currentUserId)

  const userDemands = store.demands.filter((d) => d.publisherId === currentUserId)
  const demandsWithoutOrder = userDemands.filter((d) => !store.orders.some((o) => o.demandId === d.id))

  const placeholders = demandsWithoutOrder.map((d) => ({
    id: `d-${d.id}`,
    demandId: d.id,
    demandTitle: d.title,
    requesterId: d.publisherId,
    requesterName: d.publisherName,
    requesterAvatar: d.publisherAvatar,
    serviceProviderId: '',
    serviceProviderName: '',
    serviceProviderAvatar: '',
    status: 'PENDING' as unknown as import('@/types/campushub').OrderStatus,
    note: '',
    proofSubmitted: false,
    proofImageCount: 0,
    createdAt: d.createdAt,
    updatedAt: d.updatedAt,
    completedAt: '',
    timeline: [],
    isPlaceholder: true
  }))

  return [...publishedOrders, ...placeholders]
})

function otherPartyName(order: (typeof store.orders)[number] & { isPlaceholder?: boolean }): string {
  if (activeTab.value === 'published') {
    return order.isPlaceholder ? '未被接单' : order.serviceProviderName
  }
  return order.requesterName
}

function openOrder(orderId: string): void {
  // if placeholder id (starts with d-), open demand instead
  if (orderId.startsWith('d-')) {
    const demandId = orderId.replace(/^d-/, '')
    router.push(`/demands/${demandId}`)
    return
  }
  router.push(`/orders/${orderId}`)
}

function startOrder(orderId: string): void {
  store.startOrder(orderId)
}

function completeOrder(orderId: string): void {
  store.completeOrder(orderId)
}

function cancelOrder(orderId: string): void {
  store.cancelOrder(orderId)
}

onMounted(() => {
  void store.fetchOrders()
})
</script>

<template>
  <div class="page-grid">
    <section class="panel">
      <div class="page-head">
        <div>
          <p class="eyebrow">我的订单</p>
          <h1 class="page-title">订单列表</h1>
          <p class="page-summary">查看我发布和我接下的订单，按状态执行操作。</p>
        </div>
      </div>

      <div class="segment-row">
        <button type="button" class="button" :class="activeTab === 'published' ? 'primary' : 'secondary'" @click="activeTab = 'published'">我发布的订单</button>
        <button type="button" class="button" :class="activeTab === 'accepted' ? 'primary' : 'secondary'" @click="activeTab = 'accepted'">我接的订单</button>
      </div>
    </section>

    <div v-if="!store.currentUser" class="empty-state">
      <strong>请先登录查看订单</strong>
    </div>

    <section v-else class="order-grid">
      <div v-if="!visibleOrders.length" class="empty-state">
        <strong>暂无订单</strong>
      </div>

      <article v-for="order in visibleOrders" :key="order.id" class="list-card order-card" @click="openOrder(order.id)">
        <div class="status-row">
          <template v-if="(order as any).isPlaceholder">
            <span class="chip" :class="statusToneClass('PENDING')">{{ formatDemandStatus('PENDING') }}</span>
            <span class="chip">发布单</span>
          </template>
          <template v-else>
            <span class="chip" :class="statusToneClass(order.status)">{{ formatOrderStatus(order.status) }}</span>
            <span class="chip">{{ activeTab === 'published' ? '发布单' : '接单单' }}</span>
          </template>
        </div>

        <div class="card-head">
          <h3>{{ order.demandTitle }}</h3>
          <span class="meta">{{ formatRelativeTime(order.createdAt) }}</span>
        </div>

        <div class="meta">对方：{{ otherPartyName(order) }}</div>

        <div class="card-actions">
          <button
            v-if="order.status === 'ACCEPTED' && activeTab === 'published'"
            type="button"
            class="button secondary"
            @click.stop="cancelOrder(order.id)"
          >
            取消订单
          </button>
          <button
            v-if="order.status === 'ACCEPTED' && activeTab === 'accepted'"
            type="button"
            class="button secondary"
            @click.stop="startOrder(order.id)"
          >
            开始执行
          </button>
          <button
            v-if="order.status === 'IN_PROGRESS' && activeTab === 'accepted'"
            type="button"
            class="button primary"
            @click.stop="completeOrder(order.id)"
          >
            确认完成
          </button>
        </div>
      </article>
    </section>
  </div>
</template>
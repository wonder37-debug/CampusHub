<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import SkeletonCard from '@/components/SkeletonCard.vue'
import { useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatOrderStatus, formatRelativeTime, statusToneClass, formatDemandStatus, formatMoney, formatDateTime } from '@/utils/format'

const store = useCampusHubStore()
const router = useRouter()
const activeTab = ref<'published' | 'accepted'>('published')
const loadingOrders = ref(false)

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
    requesterCreditScore: d.publisher?.creditScore ?? 0,
    serviceProviderId: '',
    serviceProviderName: '',
    serviceProviderAvatar: '',
    serviceProviderCreditScore: 0,
    // keep demand status for placeholders (could be EXPIRED)
    status: d.status as unknown as import('@/types/campushub').OrderStatus,
    demandStatus: d.status,
    note: '',
    proofSubmitted: false,
    proofImageCount: 0,
    createdAt: d.createdAt,
    updatedAt: d.updatedAt,
    // include demand fields so the UI can show reward/time/location for placeholders
    demandStartTime: d.startTime,
    demandEndTime: d.endTime,
    demandReward: d.reward,
    demandLocation: d.location,
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

async function startOrder(orderId: string): Promise<void> {
  await store.startOrder(orderId)
}

async function completeOrder(orderId: string): Promise<void> {
  if (!window.confirm('确认完成此订单？此操作不可撤销。')) return
  await store.completeOrder(orderId)
}

async function cancelOrder(orderId: string): Promise<void> {
  await store.cancelOrder(orderId)
}

onMounted(() => {
  loadingOrders.value = true
  void (async () => {
    try {
      await store.fetchOrders()
    } finally {
      loadingOrders.value = false
    }
  })()
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
      <p><a href="/auth" @click.prevent="router.push('/auth')" style="text-decoration: underline; cursor: pointer; color: var(--primary);">点击这里登录</a></p>
    </div>

    <section v-else class="order-grid">
      <div v-if="loadingOrders" class="order-grid">
        <SkeletonCard v-for="n in 4" :key="n" />
      </div>

      <div v-else-if="!visibleOrders.length" class="empty-state" style="--empty-icon:'📦'">
        <strong>暂无订单</strong>
        <p>发布需求并等同学接单后，订单会出现在这里。</p>
      </div>

      <article v-for="order in visibleOrders" :key="order.id" class="list-card order-card" @click="openOrder(order.id)">
        <div class="status-row">
          <template v-if="(order as any).isPlaceholder">
            <span class="chip" :class="statusToneClass((order as any).demandStatus || 'PENDING')">{{ formatDemandStatus((order as any).demandStatus || 'PENDING') }}</span>
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

        <div class="meta">
          <span v-if="order.demandReward">报酬：{{ formatMoney(order.demandReward) }}</span>
          <span v-if="order.demandStartTime || order.demandEndTime" style="margin-left:12px">
            时间：{{ formatDateTime(order.demandStartTime || '') }} - {{ formatDateTime(order.demandEndTime || '') }}
          </span>
          <span v-if="order.demandLocation" style="display:block;margin-top:6px">地点：{{ order.demandLocation }}</span>
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
            v-if="order.status === 'IN_PROGRESS' && activeTab === 'accepted' && !(order as any).completionHint"
            type="button"
            class="button primary"
            @click.stop="completeOrder(order.id)"
          >
            提交完成确认
          </button>
          <button
            v-if="order.status === 'IN_PROGRESS' && activeTab === 'published' && (order as any).completionHint"
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
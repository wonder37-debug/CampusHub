<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import SkeletonCard from '@/components/SkeletonCard.vue'
import { useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import type { DemandRecord, DemandStatus, OrderRecord, OrderStatus } from '@/types/campushub'
import { formatOrderStatus, formatRelativeTime, statusToneClass, formatDemandStatus, formatMoney, formatDateTime } from '@/utils/format'
import { useConfirm } from '@/composables/useDialog'

const store = useCampusHubStore()
const router = useRouter()
const activeTab = ref<'published' | 'accepted'>('published')
const loadingOrders = ref(false)
const refreshing = ref(false)

const PAGE_SIZE = 10
const page = ref(1)

// reset page when switching tabs
function switchTab(tab: 'published' | 'accepted'): void {
  page.value = 1
  activeTab.value = tab
}

async function refreshOrders(): Promise<void> {
  refreshing.value = true
  try {
    await Promise.all([
      store.fetchOrders({ page: 1, size: 100, all: true }),
      store.fetchDemands({ page: 1, size: 100, all: true, includeOwn: true })
    ])
  } finally {
    refreshing.value = false
  }
}

type PublishedOrderItem = OrderRecord & {
  demandStatus?: never
  isPlaceholder?: false
}

type DemandPlaceholderOrder = Omit<OrderRecord, 'status'> & {
  status: DemandStatus
  demandStatus: DemandStatus
  demandReviewReason?: string | null
  isPlaceholder: true
}

type OrderListItem = PublishedOrderItem | DemandPlaceholderOrder

const PRIORITY_DEMAND_STATUSES = new Set<DemandStatus>(['REVIEWING', 'PENDING'])

function createDemandPlaceholder(demand: DemandRecord): DemandPlaceholderOrder {
  return {
    id: `d-${demand.id}`,
    demandId: demand.id,
    demandTitle: demand.title,
    requesterId: demand.publisherId,
    requesterName: demand.publisherName,
    requesterAvatar: demand.publisherAvatar,
    requesterCreditScore: demand.publisher?.creditScore ?? 0,
    serviceProviderId: '',
    serviceProviderName: '',
    serviceProviderAvatar: '',
    serviceProviderCreditScore: 0,
    status: demand.status,
    demandStatus: demand.status,
    note: '',
    proofSubmitted: false,
    proofImageCount: 0,
    createdAt: demand.createdAt,
    updatedAt: demand.updatedAt,
    demandStartTime: demand.startTime,
    demandEndTime: demand.endTime,
    demandReward: demand.reward,
    demandLocation: demand.location,
    demandReviewReason: demand.reviewReason,
    completedAt: '',
    timeline: [],
    isPlaceholder: true
  }
}

function isDemandPlaceholder(order: OrderListItem): order is DemandPlaceholderOrder {
  return order.isPlaceholder === true
}

function compareOrderItems(left: OrderListItem, right: OrderListItem): number {
  const leftPriority = isDemandPlaceholder(left) && PRIORITY_DEMAND_STATUSES.has(left.demandStatus)
  const rightPriority = isDemandPlaceholder(right) && PRIORITY_DEMAND_STATUSES.has(right.demandStatus)
  if (leftPriority !== rightPriority) {
    return leftPriority ? -1 : 1
  }

  return right.createdAt.localeCompare(left.createdAt)
}

function displayStatusLabel(order: OrderListItem): string {
  return isDemandPlaceholder(order) ? formatDemandStatus(order.demandStatus) : formatOrderStatus(order.status as OrderStatus)
}

function displayStatusTone(order: OrderListItem): string {
  return statusToneClass(isDemandPlaceholder(order) ? order.demandStatus : order.status)
}

function orderHint(order: OrderListItem): string {
  if (isDemandPlaceholder(order)) {
    return ''
  }
  if (order.status === 'IN_ARBITRATION') {
    return order.completionHint || '当前订单正在等待管理员仲裁处理。'
  }
  return order.completionHint || ''
}

const visibleOrders = computed<OrderListItem[]>(() => {
  const currentUserId = store.currentUser?.id
  if (!currentUserId) return []

  if (activeTab.value === 'accepted') {
    return store.orders
      .filter((order) => order.serviceProviderId === currentUserId)
      .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
  }

  // published: include actual orders where current user is requester,
  // plus published demands by current user that have no order yet (as placeholders)
  const publishedOrders = store.orders.filter((order) => order.requesterId === currentUserId) as PublishedOrderItem[]
  const orderedDemandIds = new Set(publishedOrders.map((order) => order.demandId))

  const userDemands = store.demands.filter((demand) => demand.publisherId === currentUserId)
  const demandsWithoutOrder = userDemands.filter((demand) => !orderedDemandIds.has(demand.id))
  const placeholders = demandsWithoutOrder.map(createDemandPlaceholder)

  return [...publishedOrders, ...placeholders].sort(compareOrderItems)
})

const slicedOrders = computed(() => visibleOrders.value.slice(0, page.value * PAGE_SIZE))
const hasMore = computed(() => slicedOrders.value.length < visibleOrders.value.length)
const allLoaded = computed(() => !hasMore.value && visibleOrders.value.length > 0)

function loadMore(): void {
  page.value++
}

function otherPartyName(order: OrderListItem): string {
  if (activeTab.value === 'published') {
    if (isDemandPlaceholder(order)) {
      if (order.demandStatus === 'REVIEWING') return '待管理员审核'
      if (order.demandStatus === 'CANCELLED' && order.demandReviewReason) return '审核未通过'
      if (order.demandStatus === 'CANCELLED') return '已撤回'
      return '未被接单'
    }
    return order.serviceProviderName
  }
  return order.requesterName
}

function openOrder(order: OrderListItem): void {
  if (isDemandPlaceholder(order)) {
    router.push(`/demands/${order.demandId}`)
    return
  }
  router.push(`/orders/${order.id}`)
}

async function startOrder(orderId: string): Promise<void> {
  await store.startOrder(orderId)
}

async function completeOrder(orderId: string): Promise<void> {
  if (!await useConfirm('确认完成', '确认完成此订单？此操作不可撤销。', { danger: true })) return
  await store.completeOrder(orderId)
}

async function cancelOrder(orderId: string): Promise<void> {
  await store.cancelOrder(orderId)
}

onMounted(() => {
  loadingOrders.value = true
  void (async () => {
    try {
      await Promise.all([
        store.fetchOrders({ page: 1, size: 100, all: true }),
        store.fetchDemands({ page: 1, size: 100, all: true, includeOwn: true })
      ])
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
        <button type="button" class="button primary" :disabled="refreshing" @click="refreshOrders">
          {{ refreshing ? '刷新中...' : '↻ 刷新' }}
        </button>
      </div>

      <div class="segment-row">
        <button type="button" class="button" :class="activeTab === 'published' ? 'primary' : 'secondary'" @click="switchTab('published')">我发布的订单</button>
        <button type="button" class="button" :class="activeTab === 'accepted' ? 'primary' : 'secondary'" @click="switchTab('accepted')">我接的订单</button>
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

      <article v-for="order in slicedOrders" :key="order.id" class="list-card order-card" @click="openOrder(order)">
        <div class="status-row">
          <span class="chip" :class="displayStatusTone(order)">{{ displayStatusLabel(order) }}</span>
          <span class="chip">{{ activeTab === 'published' ? '发布单' : '接单单' }}</span>
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
        <div v-if="orderHint(order)" class="meta" style="margin-top: 8px;">
          <span class="chip is-warning">{{ orderHint(order) }}</span>
        </div>

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

      <div v-if="hasMore" class="list-card load-more-panel" @click="loadMore">
        <h3>加载更多订单</h3>
        <p class="subtle">当前显示 {{ slicedOrders.length }} / {{ visibleOrders.length }} 条</p>
      </div>
      <div v-if="allLoaded" class="load-more-done">📋 已展示全部 {{ visibleOrders.length }} 条订单</div>
    </section>
  </div>
</template>

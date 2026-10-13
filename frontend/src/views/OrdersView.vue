<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import type { OrderRecord } from '@/types/campushub'
import { formatRelativeTime, formatMoney, formatDateTime, formatDemandCategory, formatDemandStatus, statusToneClass, formatInteractionMode } from '@/utils/format'
import { handleError } from '@/utils/errorHandler'
import {
  generateOrderTodos,
  generateDemandPublisherTodos,
  buildPublishedDemandItems,
  buildParticipatedResponseItems,
  buildOrderListItems,
  type TodoItem,
  type PublishedDemandItem,
  type ParticipatedResponseItem,
  type OrderListItem
} from '@/utils/workbenchRules'
import SkeletonCard from '@/components/SkeletonCard.vue'

const store = useCampusHubStore()
const router = useRouter()

const activeTab = ref<'todo' | 'published' | 'participated' | 'orders'>('todo')
const loading = ref(false)
const refreshing = ref(false)
const error = ref('')
const message = ref('')
const submittingOrderId = ref('')
const pendingResponseCounts = ref<Map<string, number>>(new Map())
const myResponsesError = ref(false)
const orderFilter = ref<'all' | 'requester' | 'provider'>('all')

const PAGE_SIZE = 10
const page = ref(1)

function switchTab(tab: typeof activeTab.value): void {
  activeTab.value = tab
  page.value = 1
}

async function loadPendingResponseCounts(): Promise<void> {
  const uid = store.currentUser?.id
  if (!uid) return
  const targets = store.demands.filter(
    (d) => d.publisherId === uid
      && d.status === 'PENDING'
      && (d.interactionMode === 'SELECT_ONE' || d.interactionMode === 'SELECT_MANY' || d.interactionMode === 'HELP')
  )
  const counts = new Map<string, number>()
  await Promise.all(
    targets.map(async (d) => {
      const c = await store.fetchPendingResponseCount(d.id)
      counts.set(d.id, c)
    })
  )
  pendingResponseCounts.value = counts
}

async function loadWorkbench(): Promise<void> {
  loading.value = true
  error.value = ''
  myResponsesError.value = false
  try {
    await Promise.all([
      store.fetchOrders({ page: 1, size: 100, all: true }),
      store.fetchDemands({ page: 1, size: 100, all: true, includeOwn: true }),
      store.fetchMyResponses()
    ])
    if (store.ordersFetchError && store.demandFetchError) {
      error.value = '数据加载失败，请检查网络后重试。'
    } else if (store.ordersFetchError) {
      error.value = store.ordersFetchError
    } else if (store.demandFetchError) {
      error.value = store.demandFetchError
    }
    if (store.myResponsesFetchError) {
      myResponsesError.value = true
    }
    await loadPendingResponseCounts()
  } catch (e) {
    error.value = handleError(e, '数据加载失败')
  } finally {
    loading.value = false
  }
}

async function refreshAll(): Promise<void> {
  refreshing.value = true
  await loadWorkbench()
  refreshing.value = false
}

const todoItems = computed<TodoItem[]>(() => {
  const uid = store.currentUser?.id
  if (!uid) return []
  const orderTodos = generateOrderTodos(store.orders, uid)
  const demandTodos = generateDemandPublisherTodos(store.demands, uid, pendingResponseCounts.value)
  return [...orderTodos, ...demandTodos]
})

const publishedItems = computed<PublishedDemandItem[]>(() => {
  const uid = store.currentUser?.id
  if (!uid) return []
  return buildPublishedDemandItems(store.demands, uid)
})

const participatedItems = computed<ParticipatedResponseItem[]>(() => {
  return buildParticipatedResponseItems(store.myResponses)
})

const orderItems = computed<OrderListItem[]>(() => {
  const uid = store.currentUser?.id
  if (!uid) return []
  return buildOrderListItems(store.orders, uid, orderFilter.value)
})

const slicedItems = computed<any[]>(() => {
  const list = activeTab.value === 'todo' ? todoItems.value
    : activeTab.value === 'published' ? publishedItems.value
    : activeTab.value === 'participated' ? participatedItems.value
    : orderItems.value
  return list.slice(0, page.value * PAGE_SIZE)
})

const totalCount = computed(() => {
  switch (activeTab.value) {
    case 'todo': return todoItems.value.length
    case 'published': return publishedItems.value.length
    case 'participated': return participatedItems.value.length
    case 'orders': return orderItems.value.length
  }
})

const hasMore = computed(() => slicedItems.value.length < totalCount.value)
const allLoaded = computed(() => !hasMore.value && totalCount.value > 0)

function loadMore(): void {
  page.value++
}

function todoCategoryLabel(cat: string): string {
  switch (cat) {
    case 'order_provider': return '接单任务'
    case 'order_requester': return '需求确认'
    case 'demand_publisher': return '需求处理'
    case 'arbitration': return '仲裁'
    default: return ''
  }
}

function openTodo(todo: TodoItem): void {
  router.push(todo.route)
}

function openDemand(demandId: string): void {
  router.push(`/demands/${demandId}`)
}

function openOrder(orderId: string): void {
  router.push(`/orders/${orderId}`)
}

async function startOrder(order: OrderRecord): Promise<void> {
  if (submittingOrderId.value) return
  message.value = ''
  error.value = ''
  submittingOrderId.value = order.id
  try {
    await store.startOrder(order.id)
    message.value = '订单已进入进行中状态。'
    await refreshAll()
  } catch (e) {
    error.value = handleError(e, '操作失败')
  } finally {
    submittingOrderId.value = ''
  }
}

onMounted(() => {
  void loadWorkbench()
})
</script>

<template>
  <div class="page-grid">
    <section class="panel">
      <div class="page-head">
        <div>
          <p class="eyebrow">个人工作台</p>
          <h1 class="page-title">我的互助</h1>
          <p class="page-summary">待办优先 · 查看你的需求、参与记录和正式订单。</p>
        </div>
        <button type="button" class="button primary" :disabled="refreshing" @click="refreshAll">
          {{ refreshing ? '刷新中...' : '↻ 刷新' }}
        </button>
      </div>

      <div class="segment-row">
        <button type="button" class="button" :class="activeTab === 'todo' ? 'primary' : 'secondary'" @click="switchTab('todo')">
          待我处理
          <span v-if="todoItems.length > 0" class="tab-badge">{{ todoItems.length }}</span>
        </button>
        <button type="button" class="button" :class="activeTab === 'published' ? 'primary' : 'secondary'" @click="switchTab('published')">我发布的需求</button>
        <button type="button" class="button" :class="activeTab === 'participated' ? 'primary' : 'secondary'" @click="switchTab('participated')">我参与的互助</button>
        <button type="button" class="button" :class="activeTab === 'orders' ? 'primary' : 'secondary'" @click="switchTab('orders')">正式订单</button>
      </div>
    </section>

    <p v-if="message" class="hero-badge">{{ message }}</p>
    <p v-if="error" class="hero-badge" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>

    <div v-if="!store.currentUser" class="empty-state">
      <strong>请先登录</strong>
      <p><a href="/auth" @click.prevent="router.push('/auth')" style="text-decoration: underline; cursor: pointer; color: var(--primary);">点击这里登录</a></p>
    </div>

    <section v-else-if="loading" class="order-grid">
      <SkeletonCard v-for="n in 4" :key="n" />
    </section>

    <section v-else class="order-grid">
      <!-- ========== 待我处理 ========== -->
      <template v-if="activeTab === 'todo'">
        <div v-if="!todoItems.length" class="empty-state" style="--empty-icon:'✓'">
          <strong>当前没有待处理事项</strong>
          <p>有新响应、接单任务或确认需求时，待办会出现在这里。</p>
        </div>
        <article
          v-for="todo in slicedItems"
          :key="todo.id"
          class="list-card todo-card"
          tabindex="0"
          role="link"
          :aria-label="`${todo.actionLabel}：${todo.title}`"
          @click="openTodo(todo)"
          @keydown.enter="openTodo(todo)"
        >
          <div class="status-row">
            <span class="chip is-warning">{{ todoCategoryLabel(todo.category) }}</span>
            <span class="chip">{{ todo.statusLabel }}</span>
          </div>
          <div class="card-head">
            <h3>{{ todo.title }}</h3>
            <span class="meta">{{ todo.actionLabel }}</span>
          </div>
          <p class="subtle">{{ todo.description }}</p>
          <div class="card-actions">
            <button type="button" class="button primary" @click.stop="openTodo(todo)">{{ todo.actionLabel }} →</button>
          </div>
        </article>
      </template>

      <!-- ========== 我发布的需求 ========== -->
      <template v-else-if="activeTab === 'published'">
        <div v-if="!publishedItems.length" class="empty-state" style="--empty-icon:'📋'">
          <strong>还没有发布过需求</strong>
          <p><a href="/demands/new" @click.prevent="router.push('/demands/new')" style="text-decoration: underline; cursor: pointer; color: var(--primary);">发布第一个需求</a></p>
        </div>
        <article
          v-for="item in slicedItems"
          :key="item.demand.id"
          class="list-card"
          tabindex="0"
          role="link"
          :aria-label="`查看需求：${item.demand.title}`"
          @click="openDemand(item.demand.id)"
          @keydown.enter="openDemand(item.demand.id)"
        >
          <div class="status-row">
            <span class="chip" :class="statusToneClass(item.demand.status)">{{ item.statusLabel }}</span>
            <span class="chip">{{ formatDemandCategory(item.demand.category) }}</span>
            <span v-if="item.demand.interactionMode === 'SELECT_MANY'" class="chip">{{ formatInteractionMode(item.demand.interactionMode) }}</span>
          </div>
          <div class="card-head">
            <h3>{{ item.demand.title }}</h3>
            <span class="meta">{{ formatRelativeTime(item.demand.createdAt) }}</span>
          </div>
          <div class="meta">
            <span v-if="item.demand.reward">报酬：{{ formatMoney(item.demand.reward) }}</span>
            <span v-if="item.demand.startTime || item.demand.endTime" style="margin-left:12px">
              时间：{{ formatDateTime(item.demand.startTime || '') }} - {{ formatDateTime(item.demand.endTime || '') }}
            </span>
            <span v-if="item.demand.location" style="display:block;margin-top:6px">地点：{{ item.demand.location }}</span>
          </div>
          <div v-if="item.hint" class="meta" style="margin-top:6px">
            <span class="chip is-neutral">{{ item.hint }}</span>
          </div>
          <div v-if="item.demand.reviewReason" class="meta" style="color: var(--danger);">审核说明：{{ item.demand.reviewReason }}</div>
        </article>
      </template>

      <!-- ========== 我参与的互助 ========== -->
      <template v-else-if="activeTab === 'participated'">
        <div v-if="myResponsesError" class="empty-state" style="--empty-icon:'⚠'">
          <strong>参与记录加载失败</strong>
          <p>请检查网络后刷新重试。</p>
          <button type="button" class="button secondary" style="margin-top:12px" @click="refreshAll">重新加载</button>
        </div>
        <div v-else-if="!participatedItems.length" class="empty-state" style="--empty-icon:'🤝'">
          <strong>还没有参与记录</strong>
          <p>去<a href="/demands" @click.prevent="router.push('/demands')" style="text-decoration: underline; cursor: pointer; color: var(--primary);">需求大厅</a>接单或报名，参与记录会出现在这里。</p>
        </div>
        <article
          v-for="item in slicedItems"
          :key="item.response.id"
          class="list-card"
          tabindex="0"
          role="link"
          :aria-label="`查看参与记录：${item.response.demandTitle}`"
          @click="openDemand(item.response.demandId)"
          @keydown.enter="openDemand(item.response.demandId)"
        >
          <div class="status-row">
            <span class="chip" :class="item.response.status === 'SELECTED' ? 'is-success' : item.response.status === 'PENDING' ? 'is-warning' : 'is-neutral'">{{ item.statusLabel }}</span>
            <span class="chip">{{ item.response.demandCategory ? formatDemandCategory(item.response.demandCategory as any) : '需求' }}</span>
            <span v-if="item.response.demandStatus" class="chip is-neutral">需求{{ formatDemandStatus(item.response.demandStatus as any) }}</span>
          </div>
          <div class="card-head">
            <h3>{{ item.response.demandTitle }}</h3>
            <span class="meta">{{ formatRelativeTime(item.response.createdAt) }}</span>
          </div>
          <p v-if="item.response.content" class="subtle" style="margin-top:6px; white-space: pre-wrap;">{{ item.response.content }}</p>
          <div v-if="item.hint" class="meta" style="margin-top:6px">
            <span class="chip is-neutral">{{ item.hint }}</span>
          </div>
        </article>
      </template>

      <!-- ========== 正式订单 ========== -->
      <template v-else>
        <div v-if="!orderItems.length" class="empty-state" style="--empty-icon:'📦'">
          <strong>暂无正式订单</strong>
          <p>接单或发布需求被接单后，正式订单会出现在这里。</p>
        </div>
        <div v-if="orderItems.length > 0" class="segment-row" style="margin-bottom:12px">
          <button type="button" class="button" :class="orderFilter === 'all' ? 'primary' : 'secondary'" @click="orderFilter = 'all'">全部</button>
          <button type="button" class="button" :class="orderFilter === 'requester' ? 'primary' : 'secondary'" @click="orderFilter = 'requester'">我发布的</button>
          <button type="button" class="button" :class="orderFilter === 'provider' ? 'primary' : 'secondary'" @click="orderFilter = 'provider'">我接的</button>
        </div>
        <article
          v-for="item in slicedItems"
          :key="item.order.id"
          class="list-card order-card"
          tabindex="0"
          role="link"
          :aria-label="`查看订单：${item.order.demandTitle}`"
          @click="openOrder(item.order.id)"
          @keydown.enter="openOrder(item.order.id)"
        >
          <div class="status-row">
            <span class="chip" :class="statusToneClass(item.order.status)">{{ item.statusLabel }}</span>
            <span class="chip">{{ item.role === 'requester' ? '我发布的' : '我接的' }}</span>
          </div>
          <div class="card-head">
            <h3>{{ item.order.demandTitle }}</h3>
            <span class="meta">{{ formatRelativeTime(item.order.createdAt) }}</span>
          </div>
          <div class="meta">
            <span v-if="item.order.demandReward">报酬：{{ formatMoney(item.order.demandReward) }}</span>
            <span v-if="item.order.demandStartTime || item.order.demandEndTime" style="margin-left:12px">
              时间：{{ formatDateTime(item.order.demandStartTime || '') }} - {{ formatDateTime(item.order.demandEndTime || '') }}
            </span>
            <span v-if="item.order.demandLocation" style="display:block;margin-top:6px">地点：{{ item.order.demandLocation }}</span>
          </div>
          <div class="meta">对方：{{ item.role === 'requester' ? item.order.serviceProviderName : item.order.requesterName }}</div>
          <div v-if="item.hint" class="meta" style="margin-top:8px">
            <span class="chip is-warning">{{ item.hint }}</span>
          </div>
          <div class="card-actions">
            <button
              v-if="item.order.status === 'ACCEPTED' && item.role === 'provider'"
              type="button"
              class="button secondary"
              :disabled="submittingOrderId === item.order.id"
              @click.stop="startOrder(item.order)"
            >
              {{ submittingOrderId === item.order.id ? '处理中...' : '开始执行' }}
            </button>
            <button
              v-if="item.order.status === 'IN_PROGRESS' && item.role === 'provider' && !item.order.proofSubmitted"
              type="button"
              class="button primary"
              @click.stop="openOrder(item.order.id)"
            >
              上传凭证并完成
            </button>
            <button
              v-if="item.order.status === 'IN_PROGRESS' && item.role === 'requester' && item.order.proofSubmitted"
              type="button"
              class="button primary"
              @click.stop="openOrder(item.order.id)"
            >
              确认完成
            </button>
          </div>
        </article>
      </template>

      <div v-if="hasMore" class="list-card load-more-panel" @click="loadMore">
        <h3>加载更多</h3>
        <p class="subtle">当前显示 {{ slicedItems.length }} / {{ totalCount }} 条</p>
      </div>
      <div v-if="allLoaded" class="load-more-done">📋 已展示全部 {{ totalCount }} 条记录</div>
    </section>
  </div>
</template>

<style scoped>
.tab-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 20px;
  margin-left: 6px;
  padding: 0 6px;
  font-size: 12px;
  font-weight: 700;
  color: #fff;
  background: rgba(181, 71, 71, 0.85);
  border-radius: 10px;
}

.todo-card {
  cursor: pointer;
  border-left: 4px solid var(--primary, #1f5f53);
  transition: background 0.18s ease, border-color 0.18s ease;
}

.todo-card:hover {
  background: #fbfcfb;
}

.todo-card:focus-visible {
  outline: 3px solid #88ad98;
  outline-offset: 3px;
}
</style>

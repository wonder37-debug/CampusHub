<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatNotificationType, formatRelativeTime } from '@/utils/format'
import { categorizeNotification, type NotificationCategory } from '@/utils/workbenchRules'

const store = useCampusHubStore()
const router = useRouter()

const PAGE_SIZE = 10
const page = ref(1)
const loading = ref(false)
const loadError = ref(false)
const refreshing = ref(false)
const markingAllRead = ref(false)
const actionError = ref('')
const activeCategory = ref<'all' | NotificationCategory>('all')

const allNotifications = computed(() => store.currentUserNotifications)

const filteredNotifications = computed(() => {
  if (activeCategory.value === 'all') return allNotifications.value
  return allNotifications.value.filter((n) => categorizeNotification(n.type) === activeCategory.value)
})

const visibleNotifications = computed(() => filteredNotifications.value.slice(0, page.value * PAGE_SIZE))
const hasMore = computed(() => visibleNotifications.value.length < filteredNotifications.value.length)
const allLoaded = computed(() => !hasMore.value && filteredNotifications.value.length > 0)

const categoryTabs = [
  { value: 'all' as const, label: '全部' },
  { value: 'interaction' as const, label: '互动' },
  { value: 'order_fulfillment' as const, label: '订单履约' },
  { value: 'system' as const, label: '系统' }
]

function switchCategory(cat: typeof activeCategory.value): void {
  activeCategory.value = cat
  page.value = 1
}

function loadMore(): void {
  page.value++
}

async function refreshNotifications(): Promise<void> {
  refreshing.value = true
  page.value = 1
  loadError.value = false
  await store.fetchNotifications()
  if (store.notificationsFetchError) {
    loadError.value = true
  }
  refreshing.value = false
}

async function markAllRead(): Promise<void> {
  markingAllRead.value = true
  actionError.value = ''
  try {
    await store.markAllNotificationsRead()
  } catch {
    actionError.value = '标记已读失败，请稍后重试。'
  } finally {
    markingAllRead.value = false
  }
}

function iconFor(type: string): string {
  if (type === 'ORDER_ACCEPTED') return '🧩'
  if (type === 'REVIEW_RECEIVED' || type === 'RESPONSE_REVIEW_RECEIVED') return '⭐'
  if (type === 'REVIEW_REQUEST' || type === 'DEMAND_REJECTED') return '📣'
  if (type === 'DEMAND_APPROVED') return '✅'
  if (type === 'ORDER_ARBITRATION_REQUESTED') return '⚖️'
  if (type === 'ORDER_ARBITRATION_RESOLVED') return '📘'
  if (type === 'ORDER_DELETED') return '🗑️'
  if (type === 'DEMAND_RESPONSE_RECEIVED') return '💬'
  if (type === 'DEMAND_RESPONSE_SELECTED') return '📣'
  if (type === 'DEMAND_RESPONSE_REJECTED') return '🗒️'
  if (type === 'DEMAND_ANSWER_ACCEPTED') return '✅'
  if (type === 'TEAM_UP_COMPLETED') return '🎉'
  if (type === 'DEMAND_CLOSED') return '🔒'
  if (type === 'PENDING_REVIEW') return '📝'
  return '🔔'
}

async function openNotification(notification: any): Promise<void> {
  try {
    await store.markNotificationRead(notification.id)
  } catch {
    // ignore mark-read error but continue navigation
  }

  const relatedId = String(notification.relatedId ?? '')
  const type = String(notification.type ?? '')
  const targetType = String(notification.targetType ?? '').toUpperCase()
  const actionHint = String(notification.actionHint ?? '').toUpperCase()
  const targetId = String(notification.targetId ?? relatedId)
  const isAdmin = store.currentUser?.role === 'ADMIN'

  if (actionHint === 'REVIEW_DEMAND' || (isAdmin && type === 'REVIEW_REQUEST')) {
    router.push(`/admin?tab=review&demandId=${encodeURIComponent(targetId || relatedId)}`)
    return
  }

  if (type === 'ORDER_ARBITRATION_REQUESTED' || type === 'ORDER_ARBITRATION_RESOLVED') {
    if (isAdmin) {
      router.push('/admin?tab=arbitration')
    } else {
      router.push(`/orders/${encodeURIComponent(relatedId)}`)
    }
    return
  }

  if (actionHint === 'VIEW_ORDER_REVIEWS' && targetType === 'ORDER') {
    router.push(`/orders/${encodeURIComponent(targetId || relatedId)}?tab=reviews`)
    return
  }

  if (actionHint === 'VIEW_ORDER' || targetType === 'ORDER') {
    router.push(`/orders/${encodeURIComponent(targetId || relatedId)}`)
    return
  }

  if (actionHint === 'VIEW_DEMAND' || targetType === 'DEMAND') {
    router.push(`/demands/${encodeURIComponent(targetId || relatedId)}`)
    return
  }

  if (type === 'DEMAND_REJECTED' || type === 'DEMAND_APPROVED') {
    router.push(`/demands/${encodeURIComponent(targetId || relatedId)}`)
    return
  }

  if (type === 'ORDER_ACCEPTED' || type === 'STATUS_CHANGED') {
    router.push(`/orders/${encodeURIComponent(relatedId)}`)
    return
  }

  if (type === 'REVIEW_RECEIVED') {
    router.push(`/orders/${encodeURIComponent(relatedId)}`)
    return
  }

  if (type === 'RESPONSE_REVIEW_RECEIVED') {
    router.push(`/demands/${encodeURIComponent(targetId || relatedId)}`)
    return
  }

  // 管理员删除订单：跳转关联需求（订单已删除，需求保留取消状态）
  if (type === 'ORDER_DELETED') {
    router.push(`/demands/${encodeURIComponent(targetId || relatedId)}`)
    return
  }

  if (type === 'PENDING_REVIEW') {
    if (targetType === 'ORDER') {
      router.push(`/orders/${encodeURIComponent(targetId || relatedId)}?tab=reviews`)
    } else {
      router.push(`/demands/${encodeURIComponent(targetId || relatedId)}`)
    }
    return
  }

  router.push('/notifications')
}

onMounted(() => {
  loading.value = true
  loadError.value = false
  void (async () => {
    await store.fetchNotifications()
    if (store.notificationsFetchError) {
      loadError.value = true
    }
    loading.value = false
  })()
})
</script>

<template>
  <div class="page-grid">
    <section class="panel">
      <div class="page-head">
        <div style="flex:1">
          <p class="eyebrow">消息中心</p>
          <h1 class="page-title">通知</h1>
          <p class="page-summary">接单、状态变更、评价和系统消息会在这里显示。点击通知可查看相关详情。</p>
        </div>
        <button type="button" class="button primary" :disabled="refreshing" @click="refreshNotifications">
          {{ refreshing ? '刷新中...' : '↻ 刷新' }}
        </button>
        <button type="button" class="button secondary" :disabled="markingAllRead || !allNotifications.some(n => !n.isRead)" @click="markAllRead">
          {{ markingAllRead ? '处理中...' : '全部标记已读' }}
        </button>
      </div>

      <p v-if="actionError" class="hero-badge" role="alert" style="background: rgba(181,71,71,0.14); color: var(--danger);">{{ actionError }}</p>

      <div v-if="store.currentUser" class="segment-row">
        <button
          v-for="tab in categoryTabs"
          :key="tab.value"
          type="button"
          class="button"
          :class="activeCategory === tab.value ? 'primary' : 'secondary'"
          @click="switchCategory(tab.value)"
        >
          {{ tab.label }}
        </button>
      </div>
    </section>

    <section v-if="!store.currentUser" class="notification-grid">
      <div class="empty-state" style="--empty-icon:'🔔'">
        <strong>请先登录查看通知</strong>
        <p><a href="/auth" @click.prevent="router.push('/auth')" style="text-decoration: underline; cursor: pointer; color: var(--primary);">点击这里登录</a></p>
      </div>
    </section>

    <section v-else-if="loading" class="notification-grid" aria-busy="true" aria-label="正在加载通知">
      <div v-for="n in 3" :key="n" class="list-card" style="opacity:0.5" aria-hidden="true">
        <div class="status-row"><span class="badge is-neutral">🔔</span><span class="chip">加载中</span></div>
        <div class="card-head"><h3>正在加载通知...</h3></div>
      </div>
    </section>

    <section v-else-if="loadError" class="notification-grid">
      <div class="empty-state" style="--empty-icon:'⚠'">
        <strong>通知加载失败</strong>
        <p>请检查网络后重试。</p>
        <button type="button" class="button secondary" style="margin-top:12px" @click="refreshNotifications">重新加载</button>
      </div>
    </section>

    <section v-else class="notification-grid">
      <div v-if="!filteredNotifications.length" class="empty-state" style="--empty-icon:'🔔'">
        <strong v-if="activeCategory === 'all'">暂无通知</strong>
        <strong v-else>该分类下暂无通知</strong>
        <p v-if="activeCategory === 'all'">接单、评价和订单状态变更会在这里通知你。</p>
        <p v-else>切换到"全部"可以查看所有通知。</p>
      </div>

      <article
        v-for="notification in visibleNotifications"
        :key="notification.id"
        class="list-card notification-card"
        :class="notification.isRead ? 'is-read' : 'is-unread'"
        tabindex="0"
        role="link"
        :aria-label="`查看通知：${notification.title || notification.content}`"
        @click="openNotification(notification)"
        @keydown.enter="openNotification(notification)"
      >
        <div class="status-row">
          <span class="badge is-neutral">{{ iconFor(notification.type) }}</span>
          <span class="chip">{{ formatNotificationType(notification.type) }}</span>
          <span class="chip" :class="notification.isRead ? 'is-success' : 'is-warning'">
            {{ notification.isRead ? '已读' : '未读' }}
          </span>
        </div>

        <div class="card-head">
          <h3 :style="notification.isRead ? 'font-weight: 500;' : 'font-weight: 700;'">
            {{ notification.title || notification.content }}
          </h3>
          <span class="meta">{{ formatRelativeTime(notification.createdAt) }}</span>
        </div>

        <p
          v-if="notification.type === 'DEMAND_REJECTED'
            || notification.type === 'DEMAND_APPROVED'
            || notification.type === 'ORDER_ARBITRATION_REQUESTED'
            || notification.type === 'ORDER_ARBITRATION_RESOLVED'"
          class="subtle"
          style="margin-top: 8px; white-space: pre-wrap;"
        >
          {{ notification.content }}
        </p>
        <p v-else-if="notification.targetTitle || notification.relatedName" class="subtle" style="margin-top: 6px;">
          {{ notification.targetTitle || notification.relatedName }}
        </p>

        <div class="meta">
          <span v-if="!notification.isRead" class="unread-dot"></span>
          <span v-else>点击可查看相关详情</span>
        </div>
      </article>

      <button v-if="hasMore" type="button" class="list-card load-more-panel" @click="loadMore">
        <span class="load-more-title">加载更多通知</span>
        <span class="load-more-count">当前显示 {{ visibleNotifications.length }} / {{ filteredNotifications.length }} 条</span>
      </button>
      <div v-if="allLoaded" class="load-more-done">📋 已展示全部 {{ filteredNotifications.length }} 条通知</div>
    </section>
  </div>
</template>

<style scoped>
.notification-card {
  cursor: pointer;
  border-left: 4px solid transparent;
  transition: background 0.2s ease, border-color 0.2s ease, opacity 0.2s ease;
}

.notification-card:focus-visible {
  outline: 3px solid #88ad98;
  outline-offset: 3px;
}

.is-unread {
  background: linear-gradient(135deg, rgba(232, 244, 255, 0.92), rgba(215, 236, 255, 0.85));
  border-left-color: #3b82f6;
  box-shadow: 0 2px 10px rgba(59, 130, 246, 0.08);
}

.is-unread:hover {
  background: linear-gradient(135deg, rgba(220, 238, 255, 0.96), rgba(205, 228, 255, 0.92));
}

.is-read {
  background: linear-gradient(135deg, rgba(248, 248, 250, 0.95), rgba(242, 242, 244, 0.88));
  border-left-color: #c4c4c4;
  opacity: 0.82;
}

.is-read:hover {
  opacity: 1;
  background: linear-gradient(135deg, rgba(246, 246, 248, 0.98), rgba(240, 240, 242, 0.92));
}

.unread-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.18);
}
</style>

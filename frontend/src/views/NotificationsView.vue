<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatNotificationType, formatRelativeTime } from '@/utils/format'

const store = useCampusHubStore()
const router = useRouter()

const PAGE_SIZE = 10
const page = ref(1)

const allNotifications = computed(() => store.currentUserNotifications)
const visibleNotifications = computed(() => allNotifications.value.slice(0, page.value * PAGE_SIZE))
const hasMore = computed(() => visibleNotifications.value.length < allNotifications.value.length)
const allLoaded = computed(() => !hasMore.value && allNotifications.value.length > 0)
const refreshing = ref(false)

function loadMore(): void {
  page.value++
}

async function refreshNotifications(): Promise<void> {
  refreshing.value = true
  page.value = 1
  try {
    await store.fetchNotifications()
  } finally {
    refreshing.value = false
  }
}

function iconFor(type: string): string {
  if (type === 'ORDER_ACCEPTED') return '🧩'
  if (type === 'REVIEW_RECEIVED') return '⭐'
  if (type === 'REVIEW_REQUEST' || type === 'DEMAND_REJECTED') return '📣'
  if (type === 'DEMAND_APPROVED') return '✅'
  if (type === 'ORDER_ARBITRATION_REQUESTED') return '⚖️'
  if (type === 'ORDER_ARBITRATION_RESOLVED') return '📘'
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

  // fallback: stay on notifications
  router.push('/notifications')
}

onMounted(() => {
  void store.fetchNotifications()
})
</script>

<template>
  <div class="page-grid">
    <section class="panel">
      <div class="page-head">
        <div style="flex:1">
          <h1 class="page-title">消息</h1>
          <p class="page-summary">接单、状态变更和评价会在这里显示。</p>
        </div>
        <button type="button" class="button primary" :disabled="refreshing" @click="refreshNotifications">
          {{ refreshing ? '刷新中...' : '↻ 刷新' }}
        </button>
        <button type="button" class="button secondary" @click="store.markAllNotificationsRead">全部标记已读</button>
      </div>
    </section>

    <section class="notification-grid">
      <div v-if="!store.currentUser" class="empty-state" style="--empty-icon:'🔔'">
        <strong>请先登录查看消息</strong>
        <p><a href="/auth" @click.prevent="router.push('/auth')" style="text-decoration: underline; cursor: pointer; color: var(--primary);">点击这里登录</a></p>
      </div>

      <div v-else-if="!allNotifications.length" class="empty-state" style="--empty-icon:'🔔'">
        <strong>暂无消息</strong>
        <p>接单、评价和订单状态会在这里通知你。</p>
      </div>

      <article
        v-for="notification in visibleNotifications"
        :key="notification.id"
        class="list-card notification-card"
        :class="notification.isRead ? 'is-read' : 'is-unread'"
        @click="openNotification(notification)"
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
          <span v-else>点击可查看相关订单或需求详情</span>
        </div>
      </article>

      <div v-if="hasMore" class="list-card load-more-panel" @click="loadMore">
        <h3>加载更多消息</h3>
        <p class="subtle">当前显示 {{ visibleNotifications.length }} / {{ allNotifications.length }} 条</p>
      </div>
      <div v-if="allLoaded" class="load-more-done">📋 已展示全部 {{ allNotifications.length }} 条消息</div>
    </section>
  </div>
</template>

<style scoped>
.notification-card {
  cursor: pointer;
  border-left: 4px solid transparent;
  transition: background 0.2s ease, border-color 0.2s ease, opacity 0.2s ease;
}

/* 未读：柔和蓝色调背景 + 左侧强调色条 */
.is-unread {
  background: linear-gradient(135deg, rgba(232, 244, 255, 0.92), rgba(215, 236, 255, 0.85));
  border-left-color: #3b82f6;
  box-shadow: 0 2px 10px rgba(59, 130, 246, 0.08);
}

.is-unread:hover {
  background: linear-gradient(135deg, rgba(220, 238, 255, 0.96), rgba(205, 228, 255, 0.92));
}

/* 已读：柔和灰调背景 + 淡化视觉权重 */
.is-read {
  background: linear-gradient(135deg, rgba(248, 248, 250, 0.95), rgba(242, 242, 244, 0.88));
  border-left-color: #c4c4c4;
  opacity: 0.82;
}

.is-read:hover {
  opacity: 1;
  background: linear-gradient(135deg, rgba(246, 246, 248, 0.98), rgba(240, 240, 242, 0.92));
}

/* 未读圆点指示器 */
.unread-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.18);
}
</style>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatNotificationType, formatRelativeTime } from '@/utils/format'

const store = useCampusHubStore()
const router = useRouter()

const notifications = computed(() => store.currentUserNotifications)

function iconFor(type: string): string {
  if (type === 'ORDER_ACCEPTED') return '🧩'
  if (type === 'REVIEW_RECEIVED') return '⭐'
  if (type === 'REVIEW_REQUEST' || type === 'DEMAND_REJECTED') return '📣'
  if (type === 'DEMAND_APPROVED') return '✅'
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
        <button type="button" class="button secondary" @click="store.markAllNotificationsRead">全部标记已读</button>
      </div>
    </section>

    <section class="notification-grid">
      <div v-if="!store.currentUser" class="empty-state" style="--empty-icon:'🔔'">
        <strong>请先登录查看消息</strong>
        <p><a href="/auth" @click.prevent="router.push('/auth')" style="text-decoration: underline; cursor: pointer; color: var(--primary);">点击这里登录</a></p>
      </div>

      <div v-else-if="!notifications.length" class="empty-state" style="--empty-icon:'🔔'">
        <strong>暂无消息</strong>
        <p>接单、评价和订单状态会在这里通知你。</p>
      </div>

      <article
        v-for="notification in notifications"
        :key="notification.id"
        class="list-card notification-card"
        :class="{ faded: notification.isRead }"
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

        <p v-if="notification.type === 'DEMAND_REJECTED' || notification.type === 'DEMAND_APPROVED'" class="subtle" style="margin-top: 8px; white-space: pre-wrap;">
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
    </section>
  </div>
</template>
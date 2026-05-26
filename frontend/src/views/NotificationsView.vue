<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatNotificationType, formatRelativeTime } from '@/utils/format'

const store = useCampusHubStore()
const router = useRouter()

const notifications = computed(() => store.currentUserNotifications)

function goBack(): void {
  router.back()
}

function iconFor(type: string): string {
  if (type === 'ORDER_ACCEPTED') return '🧩'
  if (type === 'REVIEW_RECEIVED') return '⭐'
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

  if (type === 'ORDER_ACCEPTED' || type === 'STATUS_CHANGED') {
    router.push(`/demands/${relatedId}`)
    return
  }

  if (type === 'REVIEW_RECEIVED') {
    router.push(`/orders/${relatedId}`)
    return
  }

  if (type === 'REVIEW_REQUEST') {
    router.push(`/admin?tab=review&demandId=${relatedId}`)
    return
  }

  router.push('/notifications')
}

onMounted(() => {
  void store.fetchNotifications()
})
</script>

<template>
  <div class="page-grid">
    <section class="panel page-head">
      <div class="page-head">
        <button type="button" class="button secondary" @click="goBack">返回</button>
        <div>
          <p class="eyebrow">消息</p>
          <h1 class="page-title">消息</h1>
          <p class="page-summary">接单、状态变更和评价会在这里显示。</p>
        </div>
        <button type="button" class="button secondary" @click="store.markAllNotificationsRead">全部标记已读</button>
      </div>
    </section>

    <section class="notification-grid">
      <div v-if="!store.currentUser" class="empty-state">
        <strong>请先登录查看消息</strong>
      </div>

      <div v-else-if="!notifications.length" class="empty-state">
        <strong>暂无消息</strong>
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
          <h3 :style="notification.isRead ? 'font-weight: 500;' : 'font-weight: 700;'">{{ notification.content }}</h3>
          <span class="meta">{{ formatRelativeTime(notification.createdAt) }}</span>
        </div>

        <div class="meta">
          <span v-if="!notification.isRead" class="unread-dot"></span>
          点击可查看相关订单或需求详情
        </div>
      </article>
    </section>
  </div>
</template>
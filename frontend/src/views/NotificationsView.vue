<script setup lang="ts">
import { computed, ref } from 'vue'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatDateTime, formatNotificationType, statusToneClass } from '@/utils/format'

const store = useCampusHubStore()
const unreadOnly = ref(false)

const notifications = computed(() => {
  const items = store.currentUserNotifications
  return unreadOnly.value ? items.filter((notification) => !notification.isRead) : items
})
</script>

<template>
  <div class="page-grid">
    <section class="panel">
      <div class="page-head">
        <div>
          <p class="eyebrow">NOT-05</p>
          <h1 class="page-title">通知中心</h1>
          <p class="page-summary">这里汇总接单、状态变更、评价和系统消息，并支持未读筛选和一键已读。</p>
        </div>
        <button type="button" class="button secondary" @click="store.markAllNotificationsRead">全部标记已读</button>
      </div>

      <label class="chip" style="width: fit-content;">
        <input v-model="unreadOnly" type="checkbox" style="margin: 0 8px 0 0;" />
        只看未读通知
      </label>
    </section>

    <section class="notification-grid">
      <article v-for="notification in notifications" :key="notification.id" class="list-card">
        <div class="status-row">
          <span class="chip" :class="statusToneClass(notification.type)">{{ formatNotificationType(notification.type) }}</span>
          <span class="chip" :class="notification.isRead ? 'is-success' : 'is-warning'">
            {{ notification.isRead ? '已读' : '未读' }}
          </span>
        </div>

        <div class="card-head">
          <h3>{{ notification.content }}</h3>
          <span class="meta">{{ formatDateTime(notification.createdAt) }}</span>
        </div>

        <div class="card-actions">
          <button v-if="!notification.isRead" type="button" class="button primary" @click="store.markNotificationRead(notification.id)">标记已读</button>
          <span v-else class="chip is-success">已处理</span>
        </div>
      </article>
    </section>

    <div v-if="!notifications.length" class="empty-state">
      <strong>没有符合条件的通知</strong>
      <p>可以切换账号后体验不同身份收到的消息。</p>
    </div>
  </div>
</template>
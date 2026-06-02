<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatUserRole } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const store = useCampusHubStore()

const navigation = computed<Array<{ label: string; to: string; requiresAuth?: boolean }>>(() => {
  const base: Array<{ label: string; to: string; requiresAuth?: boolean }> = [
    { label: '首页', to: '/demands' },
    { label: '消息', to: '/notifications', requiresAuth: true },
    { label: '我的', to: '/profile', requiresAuth: true }
  ]
  // 非管理员用户显示订单入口
  if (store.currentUser?.role !== 'ADMIN') {
    base.splice(1, 0, { label: '订单', to: '/orders', requiresAuth: true })
  }
  if (store.currentUser?.role === 'ADMIN') {
    // 管理员额外显示“管理后台”入口，插入到“我的”之前
    const myIndex = base.findIndex((b) => b.label === '我的')
    const insertIndex = myIndex >= 0 ? myIndex : base.length
    base.splice(insertIndex, 0, { label: '管理后台', to: '/admin', requiresAuth: true })
  }
  return base
})

const roleLabel = computed(() => (store.currentUser ? formatUserRole(store.currentUser.role) : '未登录'))

async function onNavClick(item: { label: string; to: string; requiresAuth?: boolean }, e: Event): Promise<void> {
  e.preventDefault()
  if (!item.requiresAuth) {
    router.push(item.to)
    return
  }

  // 如果已经登录则直接跳转
  if (store.currentUser) {
    router.push(item.to)
    return
  }

  // 如果存在 token，但 currentUser 尚未 hydrate，等待初始化完成再判断
  if (store.token) {
    try {
      await store.initializeFromStorage()
    } catch {
      // ignore
    }
  }

  if (store.currentUser) {
    router.push(item.to)
  } else {
    router.push({ path: '/auth', query: { redirect: item.to } })
  }
}

function onStatusClick(): void {
  if (!store.currentUser) {
  router.push({ path: '/auth', query: { redirect: route.fullPath } })
  return
  }
  router.push('/profile')
}
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand-block">
        <div class="brand-mark">CH</div>
        <h1 class="brand-title">CampusHub</h1>
      </div>

      <nav class="nav-pills" aria-label="主导航">
        <RouterLink
          v-for="item in navigation"
          :key="item.to"
          :to="item.to"
          class="nav-pill"
          :class="{ active: route.path === item.to || route.path.startsWith(`${item.to}/`) }"
          @click.prevent="onNavClick(item, $event)"
        >
          {{ item.label }}
        </RouterLink>
      </nav>

      <div class="topbar-tools">
        <div class="status-chip" role="button" tabindex="0" @click="onStatusClick" @keydown.enter="onStatusClick" style="cursor: pointer;">
          <span>{{ roleLabel }}</span>
          <strong>{{ store.currentUser?.nickname ?? '访客' }}</strong>
        </div>
        <RouterLink class="nav-button ghost" to="/notifications">未读消息 {{ store.unreadNotificationCount }}</RouterLink>
      </div>
    </header>

    <main class="content-shell">
      <RouterView />
    </main>
  </div>
</template>

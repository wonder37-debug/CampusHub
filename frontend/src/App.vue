<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatUserRole } from '@/utils/format'

const route = useRoute()
const store = useCampusHubStore()

const navigation = [
  { label: '首页', to: '/demands' },
  { label: '订单', to: '/orders' },
  { label: '消息', to: '/notifications' },
  { label: '我的', to: '/profile' }
]

const roleLabel = computed(() => (store.currentUser ? formatUserRole(store.currentUser.role) : '未登录'))
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand-block">
        <div class="brand-mark">CH</div>
        <div>
          <p class="eyebrow">CampusHub</p>
          <h1 class="brand-title">校园互助平台</h1>
        </div>
      </div>

      <nav class="nav-pills" aria-label="主导航">
        <RouterLink
          v-for="item in navigation"
          :key="item.to"
          :to="item.to"
          class="nav-pill"
          :class="{ active: route.path === item.to || route.path.startsWith(`${item.to}/`) }"
        >
          {{ item.label }}
        </RouterLink>
      </nav>

      <div class="topbar-tools">
        <div class="status-chip">
          <span>{{ roleLabel }}</span>
          <strong>{{ store.currentUser?.nickname ?? '访客' }}</strong>
        </div>
        <RouterLink class="nav-button ghost" to="/notifications">未读 {{ store.unreadNotificationCount }}</RouterLink>
      </div>
    </header>

    <main class="content-shell">
      <RouterView />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'

const route = useRoute()
const store = useCampusHubStore()

const navigation = [
  { label: '首页', to: '/' },
  { label: '认证', to: '/auth' },
  { label: '需求', to: '/demands' },
  { label: '订单', to: '/orders' },
  { label: '通知', to: '/notifications' },
  { label: '资料', to: '/profile' },
  { label: '后台', to: '/admin' },
  { label: '关于', to: '/about' }
]

const roleLabel = computed(() => {
  if (!store.currentUser) {
    return '未登录'
  }

  return store.currentUser.role === 'admin' ? '管理员' : '学生'
})

function switchAccount(userId: string): void {
  store.switchAccount(userId)
}
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
          <strong>{{ store.currentUser?.nickname ?? '演示访客' }}</strong>
        </div>
        <RouterLink class="nav-button ghost" to="/notifications">
          未读 {{ store.unreadNotificationCount }}
        </RouterLink>
      </div>
    </header>

    <section class="demo-strip">
      <div class="demo-copy">
        <p class="eyebrow">P3 / P4 对齐</p>
        <h2>按接口规范和 Sprint 看板搭建的展示层。</h2>
        <p>
          当前页面基于演示数据仓库运行，可切换身份查看注册、发布需求、接单、评价、通知和后台审核流程。
        </p>
      </div>

      <div class="account-switcher">
        <span class="switcher-label">切换演示身份</span>
        <button
          v-for="account in store.accountOptions"
          :key="account.id"
          type="button"
          class="account-chip"
          @click="switchAccount(account.id)"
        >
          {{ account.nickname }}
        </button>
      </div>
    </section>

    <main class="content-shell">
      <RouterView />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatUserRole } from '@/utils/format'

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

  return formatUserRole(store.currentUser.role)
})

const showHomeStrip = computed(() => route.path === '/')

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
          <strong>{{ store.currentUser?.nickname ?? '访客' }}</strong>
        </div>
        <RouterLink class="nav-button ghost" to="/notifications">
          未读 {{ store.unreadNotificationCount }}
        </RouterLink>
      </div>
    </header>

    <section v-if="showHomeStrip" class="demo-strip">
      <div class="demo-copy">
        <p class="eyebrow">校园互助平台</p>
        <h2>围绕需求、接单、评价与管理闭环打造的产品页面。</h2>
        <p>
          当前页面已完成需求发布、接单、通知和后台审核等核心路径，切换账号即可体验不同角色的完整流程。
        </p>
      </div>

      <div class="account-switcher">
        <span class="switcher-label">切换账号视角</span>
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

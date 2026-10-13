<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
import { ArrowLeft, LayoutDashboard, ShieldCheck } from '@lucide/vue'

import { useCampusHubStore } from '@/stores/campusHub'

const store = useCampusHubStore()
</script>

<template>
  <div class="admin-shell">
    <aside class="admin-rail">
      <RouterLink to="/admin" class="admin-brand" aria-label="CampusHub 管理后台">
        <span class="admin-brand-mark" aria-hidden="true"><ShieldCheck :size="22" /></span>
        <span><strong>CampusHub</strong><small>管理中心</small></span>
      </RouterLink>

      <p class="admin-rail-label">工作区</p>
      <nav class="admin-navigation" aria-label="管理导航">
        <RouterLink to="/admin" class="admin-nav-link" aria-current="page">
          <LayoutDashboard :size="18" aria-hidden="true" />
          <span>管理看板</span>
        </RouterLink>
      </nav>

      <div class="admin-rail-bottom">
        <div class="admin-identity">
          <span class="admin-identity-dot" aria-hidden="true"></span>
          <span><strong>{{ store.currentUser?.nickname ?? '管理员' }}</strong><small>管理员账号</small></span>
        </div>
        <RouterLink to="/demands" class="admin-back-link">
          <ArrowLeft :size="16" aria-hidden="true" />
          返回社区
        </RouterLink>
      </div>
    </aside>

    <div class="admin-workspace">
      <header class="admin-topbar">
        <span>CampusHub <span aria-hidden="true">/</span> 管理后台</span>
        <span class="admin-access-label"><ShieldCheck :size="15" aria-hidden="true" /> 管理员专用</span>
      </header>
      <main class="admin-content">
        <RouterView v-slot="{ Component }">
          <Transition name="fade" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </main>
    </div>
  </div>
</template>

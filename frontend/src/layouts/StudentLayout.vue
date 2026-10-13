<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { Bell, BookOpenText, Compass, Plus, ShieldCheck, HandHelping } from '@lucide/vue'

import {
  Avatar,
  AvatarFallback,
  AvatarImage
} from '@/components/ui/avatar'
import { useCampusHubStore } from '@/stores/campusHub'

const route = useRoute()
const store = useCampusHubStore()
const isAdmin = computed(() => store.currentUser?.role === 'ADMIN')
const navigation = computed(() => [
  { label: '发现', to: '/demands', icon: Compass },
  ...(!isAdmin.value && store.currentUser ? [{ label: '我的互助', to: '/orders', icon: HandHelping }] : []),
  ...(store.currentUser ? [{ label: '消息', to: '/notifications', icon: Bell, count: store.unreadNotificationCount }] : []),
  ...(!isAdmin.value ? [{ label: '发布需求', to: '/demands/new', icon: Plus }] : [])
])

const initials = computed(() => store.currentUser?.nickname?.slice(0, 1) ?? '同')

function isActive(path: string): boolean {
  return route.path === path || route.path.startsWith(`${path}/`)
}
</script>

<template>
  <div class="app-shell community-shell">
    <header class="community-topbar">
      <RouterLink to="/demands" class="community-brand" aria-label="CampusHub 发现">
        <span class="community-brand-mark" aria-hidden="true">
          <BookOpenText :size="22" :stroke-width="1.8" />
        </span>
        <span class="community-brand-copy">
          <strong>CampusHub</strong>
          <span>南京大学 · 校园互助</span>
        </span>
      </RouterLink>

      <nav class="community-nav" aria-label="主导航">
        <RouterLink
          v-for="item in navigation"
          :key="item.to"
          :to="item.to"
          class="community-nav-link"
          :class="{ 'is-active': isActive(item.to) }"
          :aria-current="isActive(item.to) ? 'page' : undefined"
        >
          <component :is="item.icon" :size="17" :stroke-width="1.9" aria-hidden="true" />
          <span>{{ item.label }}</span>
          <span v-if="'count' in item && item.count" class="community-nav-count">
            {{ item.count > 99 ? '99+' : item.count }}
          </span>
        </RouterLink>
      </nav>

      <div class="community-account">
        <RouterLink
          v-if="store.currentUser"
          to="/profile"
          class="community-account-link"
          aria-label="打开个人中心"
        >
          <Avatar class="community-account-avatar">
            <AvatarImage :src="store.currentUser.avatarUrl" :alt="store.currentUser.nickname" />
            <AvatarFallback>{{ initials }}</AvatarFallback>
          </Avatar>
          <span class="community-account-copy">
            <strong>{{ store.currentUser.nickname }}</strong>
            <span>{{ isAdmin ? '管理员' : '个人中心' }}</span>
          </span>
        </RouterLink>
        <RouterLink v-else to="/auth" class="community-signin">登录 / 注册</RouterLink>
        <RouterLink v-if="isAdmin" to="/admin" class="community-admin-entry">
          <ShieldCheck :size="16" aria-hidden="true" />
          管理后台
        </RouterLink>
      </div>
    </header>

    <main class="community-content">
      <RouterView v-slot="{ Component }">
        <Transition name="fade" mode="out-in">
          <component :is="Component" />
        </Transition>
      </RouterView>
    </main>

    <footer class="community-footer">
      <span>CampusHub · 南京大学校园互助</span>
      <div>
        <RouterLink to="/about">关于</RouterLink>
        <RouterLink to="/help">帮助中心</RouterLink>
        <RouterLink to="/legal">用户协议与隐私</RouterLink>
      </div>
    </footer>
  </div>
</template>

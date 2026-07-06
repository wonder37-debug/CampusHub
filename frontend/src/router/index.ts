import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'Home',
    component: () => import('@/views/DemandListView.vue')
  },
  {
    path: '/auth',
    name: 'Auth',
    component: () => import('@/views/AuthView.vue')
  },
  {
    path: '/demands',
    name: 'DemandList',
    component: () => import('@/views/DemandListView.vue')
  },
  {
    path: '/demands/new',
    name: 'DemandPublish',
    component: () => import('@/views/DemandPublishView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/demands/:id',
    name: 'DemandDetail',
    component: () => import('@/views/DemandDetailView.vue'),
    props: true
  },
  {
    path: '/orders',
    name: 'Orders',
    component: () => import('@/views/OrdersView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/orders/:id',
    name: 'OrderDetail',
    component: () => import('@/views/OrderDetailView.vue'),
    props: true
  },
  {
    path: '/notifications',
    name: 'Notifications',
    component: () => import('@/views/NotificationsView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/profile',
    name: 'Profile',
    component: () => import('@/views/ProfileView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/profile/edit',
    name: 'ProfileEdit',
    component: () => import('@/views/ProfileEditView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/admin',
    name: 'Admin',
    component: () => import('@/views/AdminView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/about',
    name: 'About',
    component: () => import('@/views/AboutView.vue')
  },
  {
    path: '/help',
    name: 'HelpCenter',
    component: () => import('@/views/HelpCenterView.vue')
  },
  {
    path: '/legal',
    name: 'Legal',
    component: () => import('@/views/LegalView.vue')
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFoundView.vue')
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior() {
    return { top: 0 }
  }
})

// 全局路由守卫：未登录用户访问受保护页面时跳转到 /auth 并带上 redirect 查询
router.beforeEach(async (to) => {
  const requiresAuth = to.meta?.requiresAuth as boolean | undefined
  if (!requiresAuth) return true
  try {
    const { useCampusHubStore } = await import('@/stores/campusHub')
    const store = useCampusHubStore()
    if (!store.currentUser) {
      return { path: '/auth', query: { redirect: to.fullPath } }
    }
    return true
  } catch {
    // 如果无法获取 store，则保守处理，允许路由继续（避免阻塞应用）
    return true
  }
})
// 额外：管理员不允许访问 /orders，自动重定向到 /admin
router.beforeResolve(async (to) => {
  if (to.path !== '/orders') return true
  try {
    const { useCampusHubStore } = await import('@/stores/campusHub')
    const store = useCampusHubStore()
    if (store.currentUser?.role === 'ADMIN') {
      return { path: '/admin' }
    }
  } catch {
    // ignore
  }
  return true
})

export default router

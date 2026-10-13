import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { getRouteRedirect } from '@/router/access'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/demands'
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
    meta: { requiresAuth: true },
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
    path: '/users/:id',
    name: 'UserProfile',
    component: () => import('@/views/UserProfileView.vue'),
    meta: { requiresAuth: true },
    props: true
  },
  {
    path: '/users/:id/demands',
    name: 'UserDemands',
    component: () => import('@/views/UserDemandsView.vue'),
    meta: { requiresAuth: true },
    props: true
  },
  {
    path: '/admin',
    name: 'Admin',
    component: () => import('@/views/AdminView.vue'),
    meta: { requiresAuth: true, adminOnly: true }
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

declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    adminOnly?: boolean
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior() {
    return { top: 0 }
  }
})

// 全局路由守卫：未登录用户访问受保护页面时跳转到 /auth 并带上 redirect 查询
router.beforeEach(async (to) => {
  try {
    const { useCampusHubStore } = await import('@/stores/campusHub')
    const store = useCampusHubStore()
    return getRouteRedirect(to, store.currentUser)
  } catch {
    return getRouteRedirect(to, null)
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

const pageTitle: Record<string, string> = {
  Auth: '登录',
  DemandList: '需求大厅',
  DemandPublish: '发布需求',
  DemandDetail: '需求详情',
  Orders: '我的互助',
  OrderDetail: '订单详情',
  Notifications: '消息中心',
  Profile: '个人中心',
  ProfileEdit: '编辑资料',
  UserProfile: '用户主页',
  UserDemands: '用户需求',
  Admin: '管理后台',
  About: '关于',
  HelpCenter: '帮助中心',
  Legal: '法律信息',
  NotFound: '页面未找到'
}
router.afterEach((to) => {
  const title = pageTitle[String(to.name ?? '')]
  document.title = title ? `${title} - CampusHub` : 'CampusHub - 南京大学校园互助平台'
})

export default router

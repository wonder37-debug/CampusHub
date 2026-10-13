import type { RouteLocationNormalized } from 'vue-router'

type UserRole = { role: string } | null

export function getRouteRedirect(
  route: Pick<RouteLocationNormalized, 'fullPath' | 'meta'>,
  user: UserRole
): true | { path: string; query?: Record<string, string> } {
  if (route.meta.requiresAuth && !user) {
    return { path: '/auth', query: { redirect: route.fullPath } }
  }

  if (route.meta.adminOnly && user?.role !== 'ADMIN') {
    return user ? { path: '/demands' } : { path: '/auth', query: { redirect: route.fullPath } }
  }

  return true
}

import { describe, expect, it } from 'vitest'

import { getRouteRedirect } from '@/router/access'

describe('route access', () => {
  const adminRoute = { fullPath: '/admin', meta: { requiresAuth: true, adminOnly: true } }

  it('requires authentication before entering the admin area', () => {
    expect(getRouteRedirect(adminRoute, null)).toEqual({
      path: '/auth',
      query: { redirect: '/admin' }
    })
  })

  it('sends authenticated students away from the admin area', () => {
    expect(getRouteRedirect(adminRoute, { role: 'USER' })).toEqual({ path: '/demands' })
  })

  it('allows administrators into the admin area', () => {
    expect(getRouteRedirect(adminRoute, { role: 'ADMIN' })).toBe(true)
  })
})

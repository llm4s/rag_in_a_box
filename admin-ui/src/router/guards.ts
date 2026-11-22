import type { RouteLocationNormalized, RouteLocationRaw, Router } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    /** Reachable without authentication (e.g. the login page). */
    public?: boolean
    /** Only admins may open this page; others are sent to the dashboard. */
    requiresAdmin?: boolean
  }
}

export const LOGIN_ROUTE = 'login'
export const HOME_ROUTE = 'dashboard'

/**
 * Validate a `redirect` query value: only internal router paths (no
 * protocol-relative / absolute URLs, must match a route, never the login page).
 */
export function safeRedirect(router: Router, raw: unknown): string | null {
  const value = Array.isArray(raw) ? raw[0] : raw
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) {
    return null
  }
  try {
    const resolved = router.resolve(value)
    if (resolved.matched.length === 0 || resolved.name === LOGIN_ROUTE) return null
    return resolved.fullPath
  } catch {
    return null
  }
}

/** Global navigation guard: authentication and admin-only pages. */
export function createAuthGuard(router: Router) {
  return async (to: RouteLocationNormalized): Promise<true | RouteLocationRaw> => {
    const auth = useAuthStore()
    await auth.ensureInitialized()

    if (to.name === LOGIN_ROUTE) {
      // Nothing to log into, or already logged in: go where the user wanted
      if (!auth.authRequired || auth.user) {
        return safeRedirect(router, to.query.redirect) ?? { name: HOME_ROUTE }
      }
      return true
    }

    if (to.meta.public) return true

    if (!auth.isAuthenticated) {
      return { name: LOGIN_ROUTE, query: { redirect: to.fullPath } }
    }
    if (to.meta.requiresAdmin && !auth.isAdmin) {
      return { name: HOME_ROUTE }
    }
    return true
  }
}

/**
 * Handler for 401 responses (registered via `setUnauthorizedHandler` in main.ts):
 * reset auth state and send the user to the login page, remembering where they were.
 * Concurrent 401s are collapsed into one redirect, and nothing happens when
 * already on the login page or when the server turns out not to require auth.
 */
export function createUnauthorizedHandler(router: Router) {
  let inFlight: Promise<void> | null = null
  return (): Promise<void> => {
    if (inFlight) return inFlight
    inFlight = (async () => {
      const auth = useAuthStore()
      const loginRequired = await auth.handleUnauthorized()
      const current = router.currentRoute.value
      if (!loginRequired || current.name === LOGIN_ROUTE) return
      const redirect = current.matched.length > 0 ? current.fullPath : undefined
      await router.push({ name: LOGIN_ROUTE, query: redirect ? { redirect } : {} })
    })().finally(() => {
      inFlight = null
    })
    return inFlight
  }
}

/**
 * Global "not authenticated" notification.
 *
 * The API layer (axios client and the fetch-based SSE streams) calls
 * `notifyUnauthorized()` on a 401. What happens next (resetting auth state,
 * redirecting to the login page) is decided by a handler registered from
 * `main.ts`, which keeps the API modules free of router / store imports and
 * avoids circular dependencies.
 */

type UnauthorizedHandler = () => void | Promise<void>

let handler: UnauthorizedHandler | null = null

export function setUnauthorizedHandler(fn: UnauthorizedHandler | null): void {
  handler = fn
}

/**
 * Auth endpoints report their own 401s to the caller (e.g. a bad password on
 * the login form, or `/auth/me` while establishing a session) and must never
 * trigger the global redirect.
 */
export function isAuthEndpoint(url: string | undefined): boolean {
  if (!url) return false
  const path = url.replace(/^https?:\/\/[^/]+/, '').replace(/^\/api\/v1/, '')
  return path.startsWith('/auth/') || path.startsWith('/oauth/')
}

/** Report a 401 for `url`; ignored for auth endpoints or when no handler is registered. */
export function notifyUnauthorized(url?: string): void {
  if (!handler || isAuthEndpoint(url)) return
  try {
    void Promise.resolve(handler()).catch((e) => console.error('[Auth] unauthorized handler failed:', e))
  } catch (e) {
    console.error('[Auth] unauthorized handler failed:', e)
  }
}

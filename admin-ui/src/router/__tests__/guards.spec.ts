import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { defineComponent } from 'vue'
import type { AuthMe, AuthModeResponse } from '@/types/api'
import type { AppError } from '@/composables/useApiError'
import { getAuthToken, setAuthToken } from '@/api/credentials'

vi.mock('@/api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/auth')>()
  return { ...actual, getAuthMode: vi.fn(), login: vi.fn(), getMe: vi.fn(), oauthLogout: vi.fn() }
})

import * as authApi from '@/api/auth'
import { createAuthGuard, createUnauthorizedHandler, safeRedirect } from '../guards'
import { useAuthStore } from '@/stores/auth'

const api = vi.mocked(authApi)
const Page = defineComponent({ template: '<div />' })

const unauthorized: AppError = { code: 'UNAUTHORIZED', message: 'no', statusCode: 401, retryable: false }

function basicMode(authRequired = true): AuthModeResponse {
  return { mode: authRequired ? 'basic' : 'open', authRequired, methods: authRequired ? ['password'] : [], oauthLoginUrl: null }
}

function principal(isAdmin: boolean): AuthMe {
  return {
    id: 'user:1', username: 'alice', role: isAdmin ? 'admin' : 'user', isAdmin, authMethod: 'password',
    groups: [], scopes: null, collections: null, userId: 1,
  }
}

function makeRouter(): Router {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'dashboard', component: Page },
      { path: '/login', name: 'login', component: Page, meta: { public: true } },
      { path: '/documents', name: 'documents', component: Page },
      { path: '/users', name: 'users', component: Page, meta: { requiresAdmin: true } },
    ],
  })
  router.beforeEach(createAuthGuard(router))
  return router
}

describe('router auth guard', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
    vi.spyOn(console, 'warn').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('lets everything through when auth is not required', async () => {
    api.getAuthMode.mockResolvedValue(basicMode(false))
    api.getMe.mockRejectedValue(unauthorized)
    const router = makeRouter()
    await router.push('/users')
    expect(router.currentRoute.value.fullPath).toBe('/users')
  })

  it('lets everything through when /auth/mode is unavailable', async () => {
    api.getAuthMode.mockRejectedValue({ code: 'NOT_FOUND', message: 'x', statusCode: 404, retryable: false })
    const router = makeRouter()
    await router.push('/users')
    expect(router.currentRoute.value.fullPath).toBe('/users')
  })

  it('sends unauthenticated users to login with a redirect', async () => {
    api.getAuthMode.mockResolvedValue(basicMode())
    api.getMe.mockRejectedValue(unauthorized)
    const router = makeRouter()
    await router.push('/documents?page=2')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/documents?page=2')
  })

  it('sends authenticated users away from the login page', async () => {
    api.getAuthMode.mockResolvedValue(basicMode())
    api.getMe.mockResolvedValue(principal(false))
    const router = makeRouter()
    await router.push('/login?redirect=/documents')
    expect(router.currentRoute.value.fullPath).toBe('/documents')
  })

  it('redirects to the dashboard when login is not needed', async () => {
    api.getAuthMode.mockResolvedValue(basicMode(false))
    api.getMe.mockRejectedValue(unauthorized)
    const router = makeRouter()
    await router.push('/login')
    expect(router.currentRoute.value.name).toBe('dashboard')
  })

  it('keeps non-admins out of admin-only pages', async () => {
    api.getAuthMode.mockResolvedValue(basicMode())
    api.getMe.mockResolvedValue(principal(false))
    const router = makeRouter()
    await router.push('/users')
    expect(router.currentRoute.value.name).toBe('dashboard')
  })

  it('lets admins into admin-only pages', async () => {
    api.getAuthMode.mockResolvedValue(basicMode())
    api.getMe.mockResolvedValue(principal(true))
    const router = makeRouter()
    await router.push('/users')
    expect(router.currentRoute.value.name).toBe('users')
  })
})

describe('safeRedirect', () => {
  const router = makeRouter()

  it('accepts internal router paths', () => {
    expect(safeRedirect(router, '/documents?page=2')).toBe('/documents?page=2')
  })

  it('rejects external, protocol-relative, unknown and login targets', () => {
    expect(safeRedirect(router, 'https://evil.example')).toBeNull()
    expect(safeRedirect(router, '//evil.example')).toBeNull()
    expect(safeRedirect(router, '/\\evil.example')).toBeNull()
    expect(safeRedirect(router, '/nope')).toBeNull()
    expect(safeRedirect(router, '/login')).toBeNull()
    expect(safeRedirect(router, undefined)).toBeNull()
  })
})

describe('401 handler', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('clears credentials and redirects to login, remembering the route', async () => {
    setAuthToken('jwt')
    api.getAuthMode.mockResolvedValue(basicMode())
    api.getMe.mockResolvedValue(principal(true))
    const router = makeRouter()
    await router.push('/documents')
    expect(useAuthStore().isAuthenticated).toBe(true)

    const handler = createUnauthorizedHandler(router)
    await Promise.all([handler(), handler()])

    expect(getAuthToken()).toBeNull()
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/documents')
    // concurrent 401s collapse into a single mode re-check
    expect(api.getAuthMode).toHaveBeenCalledTimes(2)
  })

  it('does nothing when already on the login page', async () => {
    api.getAuthMode.mockResolvedValue(basicMode())
    api.getMe.mockRejectedValue(unauthorized)
    const router = makeRouter()
    await router.push('/login')
    const push = vi.spyOn(router, 'push')
    await createUnauthorizedHandler(router)()
    expect(push).not.toHaveBeenCalled()
  })

  it('does not redirect when the server does not require auth', async () => {
    api.getAuthMode.mockResolvedValue(basicMode(false))
    api.getMe.mockRejectedValue(unauthorized)
    const router = makeRouter()
    await router.push('/documents')
    await createUnauthorizedHandler(router)()
    expect(router.currentRoute.value.name).toBe('documents')
  })
})

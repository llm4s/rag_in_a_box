import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import type { AuthMe, AuthModeResponse } from '@/types/api'
import type { AppError } from '@/composables/useApiError'
import { getApiKey, getAuthToken, setApiKey, setAuthToken } from '@/api/credentials'

vi.mock('@/api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/auth')>()
  return {
    ...actual,
    getAuthMode: vi.fn(),
    login: vi.fn(),
    getMe: vi.fn(),
    oauthLogout: vi.fn(),
  }
})

import * as authApi from '@/api/auth'
import { useAuthStore } from '../auth'

const api = vi.mocked(authApi)

function httpError(status: number, message = 'error'): AppError {
  return { code: status === 401 ? 'UNAUTHORIZED' : 'SERVER_ERROR', message, statusCode: status, retryable: false }
}

function modeOf(partial: Partial<AuthModeResponse>): AuthModeResponse {
  return { mode: 'basic', authRequired: true, methods: ['password'], oauthLoginUrl: null, ...partial }
}

function me(partial: Partial<AuthMe> = {}): AuthMe {
  return {
    id: 'user:1',
    username: 'alice',
    role: 'user',
    isAdmin: false,
    authMethod: 'password',
    groups: [],
    scopes: null,
    collections: null,
    userId: 1,
    ...partial,
  }
}

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
    vi.spyOn(console, 'warn').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  describe('mode handling', () => {
    it('open mode without auth: authenticated and admin, no login needed', async () => {
      api.getAuthMode.mockResolvedValue(modeOf({ mode: 'open', authRequired: false, methods: [] }))
      api.getMe.mockResolvedValue(me({ authMethod: 'open', isAdmin: true, role: 'admin', username: 'anonymous' }))
      const auth = useAuthStore()
      await auth.ensureInitialized()
      expect(auth.authRequired).toBe(false)
      expect(auth.isAuthenticated).toBe(true)
      expect(auth.isAdmin).toBe(true)
    })

    it('caches the mode and initialises only once', async () => {
      api.getAuthMode.mockResolvedValue(modeOf({}))
      api.getMe.mockRejectedValue(httpError(401))
      const auth = useAuthStore()
      await Promise.all([auth.ensureInitialized(), auth.ensureInitialized()])
      await auth.ensureInitialized()
      await auth.loadMode()
      expect(api.getAuthMode).toHaveBeenCalledTimes(1)
      expect(api.getMe).toHaveBeenCalledTimes(1)
    })

    it('falls back to open mode when /auth/mode fails, without calling /auth/me', async () => {
      api.getAuthMode.mockRejectedValue(httpError(404))
      const auth = useAuthStore()
      await auth.ensureInitialized()
      expect(auth.modeFallback).toBe(true)
      expect(auth.authRequired).toBe(false)
      expect(auth.isAuthenticated).toBe(true)
      expect(auth.isAdmin).toBe(true)
      expect(api.getMe).not.toHaveBeenCalled()
    })

    it('auth required and no session: not authenticated, not admin', async () => {
      api.getAuthMode.mockResolvedValue(modeOf({}))
      api.getMe.mockRejectedValue(httpError(401))
      const auth = useAuthStore()
      await auth.ensureInitialized()
      expect(auth.isAuthenticated).toBe(false)
      expect(auth.isAdmin).toBe(false)
    })

    it('restores a session from a stored token', async () => {
      setAuthToken('jwt')
      api.getAuthMode.mockResolvedValue(modeOf({}))
      api.getMe.mockResolvedValue(me({ isAdmin: true, role: 'admin' }))
      const auth = useAuthStore()
      await auth.ensureInitialized()
      expect(auth.isAuthenticated).toBe(true)
      expect(auth.isAdmin).toBe(true)
      expect(auth.username).toBe('alice')
    })

    it('drops a stale stored token when /auth/me returns 401', async () => {
      setAuthToken('expired')
      api.getAuthMode.mockResolvedValue(modeOf({}))
      api.getMe.mockRejectedValue(httpError(401))
      const auth = useAuthStore()
      await auth.ensureInitialized()
      expect(getAuthToken()).toBeNull()
      expect(auth.isAuthenticated).toBe(false)
    })
  })

  describe('login flows', () => {
    beforeEach(async () => {
      api.getAuthMode.mockResolvedValue(modeOf({ methods: ['password', 'api-key'] }))
      api.getMe.mockRejectedValueOnce(httpError(401))
      await useAuthStore().ensureInitialized()
    })

    it('password login stores the JWT and loads the principal', async () => {
      api.login.mockResolvedValue({ token: 'jwt-1', username: 'alice', role: 'user', expiresIn: 3600 })
      api.getMe.mockResolvedValue(me())
      const auth = useAuthStore()
      await auth.loginWithPassword('alice', 'secret')
      expect(api.login).toHaveBeenCalledWith({ username: 'alice', password: 'secret' })
      expect(getAuthToken()).toBe('jwt-1')
      expect(auth.isAuthenticated).toBe(true)
      expect(auth.isAdmin).toBe(false)
    })

    it('password login reports bad credentials and stores nothing', async () => {
      api.login.mockRejectedValue(httpError(401, 'Unauthorized'))
      const auth = useAuthStore()
      await expect(auth.loginWithPassword('alice', 'wrong')).rejects.toMatchObject({
        message: 'Invalid username or password',
      })
      expect(getAuthToken()).toBeNull()
      expect(auth.isAuthenticated).toBe(false)
    })

    it('API key login validates the key via /auth/me', async () => {
      api.getMe.mockResolvedValue(me({ authMethod: 'api-key', isAdmin: true, role: 'admin', username: 'api-key' }))
      const auth = useAuthStore()
      await auth.loginWithApiKey('  key-123 ')
      expect(getApiKey()).toBe('key-123')
      expect(auth.isAuthenticated).toBe(true)
      expect(auth.isAdmin).toBe(true)
    })

    it('API key login clears an invalid key', async () => {
      api.getMe.mockRejectedValue(httpError(401))
      const auth = useAuthStore()
      await expect(auth.loginWithApiKey('bad')).rejects.toMatchObject({ message: 'Invalid API key' })
      expect(getApiKey()).toBeNull()
      expect(auth.isAuthenticated).toBe(false)
    })

    it('SSO login navigates to the server login URL with a relative redirect', async () => {
      const auth = useAuthStore()
      auth.mode = modeOf({ mode: 'oauth', methods: ['sso'], oauthLoginUrl: '/api/v1/oauth/login' })
      const assign = vi.fn()
      vi.stubGlobal('location', { ...window.location, assign })
      try {
        auth.loginWithSso('/admin/documents?x=1')
        expect(assign).toHaveBeenCalledWith('/api/v1/oauth/login?redirect_after=%2Fadmin%2Fdocuments%3Fx%3D1')
      } finally {
        vi.unstubAllGlobals()
      }
    })
  })

  describe('logout', () => {
    it('basic mode: clears the token without calling the server', async () => {
      setAuthToken('jwt')
      api.getAuthMode.mockResolvedValue(modeOf({}))
      api.getMe.mockResolvedValue(me())
      const auth = useAuthStore()
      await auth.ensureInitialized()
      await auth.logout()
      expect(getAuthToken()).toBeNull()
      expect(auth.user).toBeNull()
      expect(auth.isAuthenticated).toBe(false)
      expect(api.oauthLogout).not.toHaveBeenCalled()
    })

    it('SSO session: calls /oauth/logout to clear the cookie', async () => {
      api.getAuthMode.mockResolvedValue(modeOf({ mode: 'oauth', methods: ['sso'], oauthLoginUrl: '/api/v1/oauth/login' }))
      api.getMe.mockResolvedValue(me({ authMethod: 'oidc' }))
      api.oauthLogout.mockResolvedValue()
      const auth = useAuthStore()
      await auth.ensureInitialized()
      await auth.logout()
      expect(api.oauthLogout).toHaveBeenCalledTimes(1)
      expect(auth.isAuthenticated).toBe(false)
    })
  })

  describe('handleUnauthorized', () => {
    it('clears credentials, re-reads the mode and reports whether login is required', async () => {
      setApiKey('stale')
      api.getAuthMode.mockResolvedValue(modeOf({ mode: 'open', methods: ['api-key'] }))
      api.getMe.mockResolvedValue(me({ authMethod: 'api-key' }))
      const auth = useAuthStore()
      await auth.ensureInitialized()
      expect(auth.isAuthenticated).toBe(true)

      await expect(auth.handleUnauthorized()).resolves.toBe(true)
      expect(getApiKey()).toBeNull()
      expect(auth.isAuthenticated).toBe(false)
      expect(api.getAuthMode).toHaveBeenCalledTimes(2)
    })

    it('returns false when the server no longer requires auth', async () => {
      api.getAuthMode.mockResolvedValue(modeOf({ mode: 'open', authRequired: false, methods: [] }))
      api.getMe.mockResolvedValue(me({ authMethod: 'open' }))
      const auth = useAuthStore()
      await auth.ensureInitialized()
      await expect(auth.handleUnauthorized()).resolves.toBe(false)
      expect(auth.isAuthenticated).toBe(true)
    })
  })
})

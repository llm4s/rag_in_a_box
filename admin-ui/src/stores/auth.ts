import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as authApi from '@/api/auth'
import { clearCredentials, getApiKey, getAuthToken, setApiKey, setAuthToken } from '@/api/credentials'
import { isHttpStatus, toAppError } from '@/composables/useApiError'
import type { AuthMe, AuthMethodName, AuthModeResponse } from '@/types/api'

/**
 * Used when `/auth/mode` cannot be loaded (network error, older backend, e2e
 * runs without a backend): behave like open mode rather than blocking the UI.
 * Any later 401 resets this and re-asks the server.
 */
export const FALLBACK_MODE: AuthModeResponse = Object.freeze({
  mode: 'open',
  authRequired: false,
  methods: [],
  oauthLoginUrl: null,
}) as AuthModeResponse

export const useAuthStore = defineStore('auth', () => {
  const mode = ref<AuthModeResponse | null>(null)
  /** True when `mode` is FALLBACK_MODE because `/auth/mode` failed. */
  const modeFallback = ref(false)
  const user = ref<AuthMe | null>(null)
  const initialized = ref(false)

  let modePromise: Promise<AuthModeResponse> | null = null
  let initPromise: Promise<void> | null = null

  const authRequired = computed(() => mode.value?.authRequired ?? false)
  const methods = computed<AuthMethodName[]>(() => mode.value?.methods ?? [])
  /** Authenticated, or no authentication required at all. */
  const isAuthenticated = computed(() => user.value !== null || (initialized.value && !authRequired.value))
  /** Without auth everything is allowed; otherwise trust the server's `isAdmin`. */
  const isAdmin = computed(() => (authRequired.value ? user.value?.isAdmin === true : true))
  const username = computed(() => user.value?.username ?? null)

  function hasMethod(method: AuthMethodName): boolean {
    return methods.value.includes(method)
  }

  /** Load (and cache) `/auth/mode`. Never rejects: falls back to open mode. */
  function loadMode(force = false): Promise<AuthModeResponse> {
    if (mode.value && !force) return Promise.resolve(mode.value)
    if (modePromise) return modePromise
    modePromise = authApi
      .getAuthMode()
      .then((m) => {
        mode.value = {
          mode: m.mode,
          authRequired: m.authRequired === true,
          methods: Array.isArray(m.methods) ? m.methods : [],
          oauthLoginUrl: m.oauthLoginUrl ?? null,
        }
        modeFallback.value = false
        return mode.value
      })
      .catch((e) => {
        console.warn('[Auth] Could not load /auth/mode, assuming open mode:', toAppError(e).message)
        mode.value = { ...FALLBACK_MODE, methods: [] }
        modeFallback.value = true
        return mode.value
      })
      .finally(() => {
        modePromise = null
      })
    return modePromise
  }

  /** Load the current principal; returns null (and clears stale credentials) on 401. */
  async function fetchMe(): Promise<AuthMe | null> {
    try {
      user.value = await authApi.getMe()
    } catch (e) {
      user.value = null
      if (isHttpStatus(e, 401) && (getAuthToken() || getApiKey())) clearCredentials()
    }
    return user.value
  }

  /** Establish auth state once (mode + principal). Used by the router guard. */
  function ensureInitialized(): Promise<void> {
    if (initialized.value) return Promise.resolve()
    if (!initPromise) {
      initPromise = (async () => {
        await loadMode()
        // Without a reachable auth API there is no principal to ask for. In
        // open mode the principal is informational only (failures are ignored).
        if (!modeFallback.value) await fetchMe()
        initialized.value = true
      })().finally(() => {
        initPromise = null
      })
    }
    return initPromise
  }

  async function loginWithPassword(usernameInput: string, password: string): Promise<AuthMe> {
    let token: string
    try {
      token = (await authApi.login({ username: usernameInput, password })).token
    } catch (e) {
      const err = toAppError(e)
      if (err.statusCode === 401) err.message = 'Invalid username or password'
      throw err
    }
    clearCredentials()
    setAuthToken(token)
    return completeLogin()
  }

  async function loginWithApiKey(apiKey: string): Promise<AuthMe> {
    const key = apiKey.trim()
    if (!key) throw toAppError('Please enter an API key', false)
    clearCredentials()
    setApiKey(key)
    return completeLogin('Invalid API key')
  }

  /** Validate the freshly stored credential via `/auth/me`. */
  async function completeLogin(invalidMessage = 'Authentication failed'): Promise<AuthMe> {
    try {
      const me = await authApi.getMe()
      user.value = me
      initialized.value = true
      return me
    } catch (e) {
      clearCredentials()
      user.value = null
      const err = toAppError(e)
      if (err.statusCode === 401) err.message = invalidMessage
      throw err
    }
  }

  /**
   * Start SSO: full-page navigation to the server's OAuth login, which sets an
   * HttpOnly session cookie and redirects back to `redirectAfter` (a relative
   * path including the `/admin` base, e.g. `/admin/documents`).
   */
  function loginWithSso(redirectAfter?: string): void {
    const url = authApi.buildSsoLoginUrl(mode.value?.oauthLoginUrl, redirectAfter)
    window.location.assign(url)
  }

  async function logout(): Promise<void> {
    const usedSso = user.value?.authMethod === 'oidc' || (mode.value?.mode === 'oauth' && !getAuthToken() && !getApiKey())
    clearCredentials()
    user.value = null
    if (usedSso) {
      try {
        await authApi.oauthLogout()
      } catch (e) {
        console.warn('[Auth] SSO logout failed:', toAppError(e).message)
      }
    }
  }

  /**
   * Called on any 401 from the API: drop stale credentials / principal and
   * re-check the server mode. Returns whether login is required (i.e. whether
   * the caller should redirect to the login page).
   */
  async function handleUnauthorized(): Promise<boolean> {
    clearCredentials()
    user.value = null
    const m = await loadMode(true)
    initialized.value = true
    return m.authRequired
  }

  return {
    mode,
    modeFallback,
    user,
    initialized,
    authRequired,
    methods,
    isAuthenticated,
    isAdmin,
    username,
    hasMethod,
    loadMode,
    fetchMe,
    ensureInitialized,
    loginWithPassword,
    loginWithApiKey,
    loginWithSso,
    logout,
    handleUnauthorized,
  }
})

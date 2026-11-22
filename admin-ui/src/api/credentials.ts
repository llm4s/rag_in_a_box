/**
 * Single source of truth for the admin UI's API credentials.
 *
 * Every request path (the axios client, and the fetch-based SSE streams in
 * query.ts / chat.ts) reads credentials from here, so a login flow only needs
 * to call `setApiKey` / `setAuthToken` / `clearCredentials`.
 *
 * Earlier versions read three different localStorage keys (`ragbox_api_key`,
 * `api_key`, `auth_token`); the legacy names are still read as a fallback and
 * migrated on first access.
 */

const API_KEY = 'ragbox_api_key'
const AUTH_TOKEN = 'ragbox_auth_token'
const LEGACY_API_KEY = 'api_key'
const LEGACY_AUTH_TOKEN = 'auth_token'

function storage(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null
  } catch {
    // Access can throw in sandboxed iframes / privacy modes
    return null
  }
}

function read(key: string, legacyKey: string): string | null {
  const store = storage()
  if (!store) return null
  const value = store.getItem(key)
  if (value) return value
  const legacy = store.getItem(legacyKey)
  if (legacy) {
    store.setItem(key, legacy)
    store.removeItem(legacyKey)
  }
  return legacy
}

function write(key: string, value: string | null): void {
  const store = storage()
  if (!store) return
  if (value) store.setItem(key, value)
  else store.removeItem(key)
}

export function getApiKey(): string | null {
  return read(API_KEY, LEGACY_API_KEY)
}

export function setApiKey(value: string | null): void {
  write(API_KEY, value)
}

export function getAuthToken(): string | null {
  return read(AUTH_TOKEN, LEGACY_AUTH_TOKEN)
}

export function setAuthToken(value: string | null): void {
  write(AUTH_TOKEN, value)
}

export function clearCredentials(): void {
  const store = storage()
  if (!store) return
  for (const key of [API_KEY, AUTH_TOKEN, LEGACY_API_KEY, LEGACY_AUTH_TOKEN]) {
    store.removeItem(key)
  }
}

/** Headers to attach to every backend request. */
export function getAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = {}
  const token = getAuthToken()
  if (token) headers['Authorization'] = `Bearer ${token}`
  const apiKey = getApiKey()
  if (apiKey) headers['X-API-Key'] = apiKey
  return headers
}

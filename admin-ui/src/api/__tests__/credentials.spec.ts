import { describe, it, expect, beforeEach } from 'vitest'
import { getApiKey, setApiKey, setAuthToken, clearCredentials, getAuthHeaders } from '../credentials'

describe('credentials', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('returns no headers when nothing is stored', () => {
    expect(getAuthHeaders()).toEqual({})
  })

  it('builds API key and bearer headers from one place', () => {
    setApiKey('key-123')
    setAuthToken('tok-456')
    expect(getAuthHeaders()).toEqual({
      'X-API-Key': 'key-123',
      Authorization: 'Bearer tok-456'
    })
  })

  it('migrates legacy storage keys', () => {
    localStorage.setItem('api_key', 'legacy-key')
    localStorage.setItem('auth_token', 'legacy-token')
    expect(getAuthHeaders()).toEqual({
      'X-API-Key': 'legacy-key',
      Authorization: 'Bearer legacy-token'
    })
    expect(localStorage.getItem('api_key')).toBeNull()
    expect(localStorage.getItem('ragbox_api_key')).toBe('legacy-key')
  })

  it('clears every known key', () => {
    setApiKey('k')
    localStorage.setItem('auth_token', 't')
    clearCredentials()
    expect(getApiKey()).toBeNull()
    expect(getAuthHeaders()).toEqual({})
  })
})

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { AxiosError, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import client from '../client'
import { isAuthEndpoint, notifyUnauthorized, setUnauthorizedHandler } from '../unauthorized'

function failingAdapter(status: number): AxiosAdapter {
  return (config: InternalAxiosRequestConfig) =>
    Promise.reject(
      new AxiosError('failed', 'ERR_BAD_REQUEST', config, null, {
        status,
        statusText: '',
        data: {},
        headers: {},
        config,
      })
    )
}

describe('401 notification', () => {
  const handler = vi.fn()
  const originalAdapter = client.defaults.adapter

  beforeEach(() => {
    handler.mockReset()
    setUnauthorizedHandler(handler)
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    setUnauthorizedHandler(null)
    client.defaults.adapter = originalAdapter
    vi.restoreAllMocks()
  })

  it('recognises auth endpoints (relative, prefixed and absolute)', () => {
    expect(isAuthEndpoint('/auth/login')).toBe(true)
    expect(isAuthEndpoint('/auth/me')).toBe(true)
    expect(isAuthEndpoint('/api/v1/auth/mode')).toBe(true)
    expect(isAuthEndpoint('/oauth/logout')).toBe(true)
    expect(isAuthEndpoint('http://host/api/v1/auth/me')).toBe(true)
    expect(isAuthEndpoint('/documents')).toBe(false)
    expect(isAuthEndpoint('/api/v1/query/stream')).toBe(false)
    expect(isAuthEndpoint(undefined)).toBe(false)
  })

  it('notifies the handler on a 401 from a regular endpoint', async () => {
    client.defaults.adapter = failingAdapter(401)
    await expect(client.get('/documents')).rejects.toMatchObject({ statusCode: 401 })
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('does not notify for auth endpoints (no redirect loops on failed login)', async () => {
    client.defaults.adapter = failingAdapter(401)
    await expect(client.post('/auth/login', {})).rejects.toMatchObject({ statusCode: 401 })
    await expect(client.get('/auth/me')).rejects.toMatchObject({ statusCode: 401 })
    expect(handler).not.toHaveBeenCalled()
  })

  it('does not notify on 403', async () => {
    client.defaults.adapter = failingAdapter(403)
    await expect(client.get('/users')).rejects.toMatchObject({ statusCode: 403 })
    expect(handler).not.toHaveBeenCalled()
  })

  it('is a no-op without a registered handler', () => {
    setUnauthorizedHandler(null)
    expect(() => notifyUnauthorized('/documents')).not.toThrow()
  })
})

import { describe, it, expect } from 'vitest'
import type { AxiosError, AxiosResponse } from 'axios'
import {
  transformAxiosError,
  isAppError,
  getErrorMessage,
  ErrorCodes,
  type AppError
} from '../useApiError'

// Helper to create mock AxiosError
function createAxiosError(status: number, data?: Record<string, unknown>): AxiosError {
  return {
    response: {
      status,
      data,
      statusText: '',
      headers: {},
      config: {} as AxiosResponse['config']
    } as AxiosResponse,
    isAxiosError: true,
    name: 'AxiosError',
    message: 'Request failed',
    toJSON: () => ({})
  } as AxiosError
}

function createNetworkError(code?: string, message?: string): AxiosError {
  return {
    response: undefined,
    code,
    message: message || 'Network Error',
    isAxiosError: true,
    name: 'AxiosError',
    toJSON: () => ({})
  } as AxiosError
}

describe('transformAxiosError', () => {
  it('transforms network error correctly', () => {
    const error = createNetworkError()
    const result = transformAxiosError(error)

    expect(result.code).toBe(ErrorCodes.NETWORK)
    expect(result.retryable).toBe(true)
  })

  it('transforms timeout error correctly', () => {
    const error = createNetworkError('ECONNABORTED', 'timeout of 5000ms exceeded')
    const result = transformAxiosError(error)

    expect(result.code).toBe(ErrorCodes.TIMEOUT)
    expect(result.retryable).toBe(true)
  })

  it('transforms 400 validation error', () => {
    const error = createAxiosError(400, { error: 'Invalid email format' })
    const result = transformAxiosError(error)

    expect(result.code).toBe(ErrorCodes.VALIDATION)
    expect(result.message).toBe('Invalid email format')
    expect(result.retryable).toBe(false)
    expect(result.statusCode).toBe(400)
  })

  it('transforms 401 unauthorized error', () => {
    const error = createAxiosError(401)
    const result = transformAxiosError(error)

    expect(result.code).toBe(ErrorCodes.UNAUTHORIZED)
    expect(result.retryable).toBe(false)
  })

  it('transforms 403 forbidden error', () => {
    const error = createAxiosError(403)
    const result = transformAxiosError(error)

    expect(result.code).toBe(ErrorCodes.FORBIDDEN)
    expect(result.retryable).toBe(false)
  })

  it('transforms 404 not found error', () => {
    const error = createAxiosError(404)
    const result = transformAxiosError(error)

    expect(result.code).toBe(ErrorCodes.NOT_FOUND)
    expect(result.retryable).toBe(false)
  })

  it('transforms 409 conflict error', () => {
    const error = createAxiosError(409, { message: 'Document already exists' })
    const result = transformAxiosError(error)

    expect(result.code).toBe(ErrorCodes.CONFLICT)
    expect(result.message).toBe('Document already exists')
    expect(result.retryable).toBe(false)
  })

  it('transforms 429 rate limited error', () => {
    const error = createAxiosError(429)
    const result = transformAxiosError(error)

    expect(result.code).toBe(ErrorCodes.RATE_LIMITED)
    expect(result.retryable).toBe(true)
  })

  it('transforms 500 server error', () => {
    const error = createAxiosError(500)
    const result = transformAxiosError(error)

    expect(result.code).toBe(ErrorCodes.SERVER_ERROR)
    expect(result.retryable).toBe(true)
  })

  it('transforms 502 bad gateway as server error', () => {
    const error = createAxiosError(502)
    const result = transformAxiosError(error)

    expect(result.code).toBe(ErrorCodes.SERVER_ERROR)
    expect(result.retryable).toBe(true)
  })

  it('transforms unknown status as unknown error', () => {
    const error = createAxiosError(418) // I'm a teapot
    const result = transformAxiosError(error)

    expect(result.code).toBe(ErrorCodes.UNKNOWN)
    expect(result.retryable).toBe(false)
  })

  it('includes details from server response', () => {
    const error = createAxiosError(400, {
      error: 'Validation failed',
      fields: ['name', 'email']
    })
    const result = transformAxiosError(error)

    expect(result.details).toEqual({
      error: 'Validation failed',
      fields: ['name', 'email']
    })
  })
})

describe('isAppError', () => {
  it('returns true for valid AppError', () => {
    const appError: AppError = {
      code: 'TEST_ERROR',
      message: 'Test message',
      retryable: true
    }
    expect(isAppError(appError)).toBe(true)
  })

  it('returns false for regular Error', () => {
    expect(isAppError(new Error('Test'))).toBe(false)
  })

  it('returns false for string', () => {
    expect(isAppError('Error message')).toBe(false)
  })

  it('returns false for null', () => {
    expect(isAppError(null)).toBe(false)
  })

  it('returns false for object missing required properties', () => {
    expect(isAppError({ code: 'TEST' })).toBe(false)
    expect(isAppError({ code: 'TEST', message: 'Test' })).toBe(false)
  })
})

describe('getErrorMessage', () => {
  it('extracts message from AppError', () => {
    const appError: AppError = {
      code: 'TEST_ERROR',
      message: 'App error message',
      retryable: false
    }
    expect(getErrorMessage(appError)).toBe('App error message')
  })

  it('extracts message from Error', () => {
    expect(getErrorMessage(new Error('Error message'))).toBe('Error message')
  })

  it('returns string directly', () => {
    expect(getErrorMessage('String error')).toBe('String error')
  })

  it('returns default message for unknown types', () => {
    expect(getErrorMessage(undefined)).toBe('An unexpected error occurred')
    expect(getErrorMessage({})).toBe('An unexpected error occurred')
    expect(getErrorMessage(123)).toBe('An unexpected error occurred')
  })
})

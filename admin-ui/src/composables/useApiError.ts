import type { AxiosError } from 'axios'

export interface AppError {
  code: string
  message: string
  statusCode?: number
  retryable: boolean
  details?: Record<string, unknown>
}

// Error codes for categorization
export const ErrorCodes = {
  NETWORK: 'NETWORK_ERROR',
  TIMEOUT: 'TIMEOUT_ERROR',
  UNAUTHORIZED: 'UNAUTHORIZED',
  FORBIDDEN: 'FORBIDDEN',
  NOT_FOUND: 'NOT_FOUND',
  VALIDATION: 'VALIDATION_ERROR',
  CONFLICT: 'CONFLICT',
  RATE_LIMITED: 'RATE_LIMITED',
  SERVER_ERROR: 'SERVER_ERROR',
  UNKNOWN: 'UNKNOWN_ERROR'
} as const

// Human-friendly error messages
const errorMessages: Record<string, string> = {
  [ErrorCodes.NETWORK]: 'Unable to connect to the server. Please check your network connection.',
  [ErrorCodes.TIMEOUT]: 'The request timed out. Please try again.',
  [ErrorCodes.UNAUTHORIZED]: 'Authentication required. Please check your API key.',
  [ErrorCodes.FORBIDDEN]: 'You do not have permission to perform this action.',
  [ErrorCodes.NOT_FOUND]: 'The requested resource was not found.',
  [ErrorCodes.VALIDATION]: 'Please check your input and try again.',
  [ErrorCodes.CONFLICT]: 'This operation conflicts with the current state.',
  [ErrorCodes.RATE_LIMITED]: 'Too many requests. Please wait a moment and try again.',
  [ErrorCodes.SERVER_ERROR]: 'A server error occurred. Please try again later.',
  [ErrorCodes.UNKNOWN]: 'An unexpected error occurred.'
}

// Retryable error codes
const retryableCodes: Set<string> = new Set([
  ErrorCodes.NETWORK,
  ErrorCodes.TIMEOUT,
  ErrorCodes.RATE_LIMITED,
  ErrorCodes.SERVER_ERROR
])

function nonEmptyString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

/**
 * Build a human-readable message from a backend error body.
 *
 * The backend's `ErrorResponse` is `{ error, message, details? }` where `error`
 * is a machine code (e.g. `validation_failed`), so prefer `message` and append
 * `details` when present. Fall back to `error` only for bodies that carry a
 * human message there (e.g. middleware responses like `{ error: "Rate limit exceeded" }`).
 */
export function extractServerMessage(data: Record<string, unknown> | undefined): string | undefined {
  if (!data || typeof data !== 'object') return undefined
  const message = nonEmptyString(data.message)
  const details = nonEmptyString(data.details)
  if (message) {
    return details && details !== message ? `${message}: ${details}` : message
  }
  return nonEmptyString(data.error) ?? details
}

export function transformAxiosError(error: AxiosError): AppError {
  // Network error (no response)
  if (!error.response) {
    if (error.code === 'ECONNABORTED' || error.message?.includes('timeout')) {
      return {
        code: ErrorCodes.TIMEOUT,
        message: errorMessages[ErrorCodes.TIMEOUT],
        retryable: true
      }
    }
    return {
      code: ErrorCodes.NETWORK,
      message: errorMessages[ErrorCodes.NETWORK],
      retryable: true
    }
  }

  const status = error.response.status
  const data = error.response.data as Record<string, unknown> | undefined

  const serverMessage = extractServerMessage(data)

  let code: string
  let message: string

  switch (status) {
    case 400:
      code = ErrorCodes.VALIDATION
      message = serverMessage || errorMessages[ErrorCodes.VALIDATION]
      break
    case 401:
      code = ErrorCodes.UNAUTHORIZED
      message = serverMessage || errorMessages[ErrorCodes.UNAUTHORIZED]
      break
    case 403:
      code = ErrorCodes.FORBIDDEN
      message = serverMessage || errorMessages[ErrorCodes.FORBIDDEN]
      break
    case 404:
      code = ErrorCodes.NOT_FOUND
      message = serverMessage || errorMessages[ErrorCodes.NOT_FOUND]
      break
    case 409:
      code = ErrorCodes.CONFLICT
      message = serverMessage || errorMessages[ErrorCodes.CONFLICT]
      break
    case 429:
      code = ErrorCodes.RATE_LIMITED
      message = serverMessage || errorMessages[ErrorCodes.RATE_LIMITED]
      break
    case 500:
    case 502:
    case 503:
    case 504:
      code = ErrorCodes.SERVER_ERROR
      message = serverMessage || errorMessages[ErrorCodes.SERVER_ERROR]
      break
    default:
      code = ErrorCodes.UNKNOWN
      message = serverMessage || errorMessages[ErrorCodes.UNKNOWN]
  }

  return {
    code,
    message,
    statusCode: status,
    retryable: retryableCodes.has(code),
    details: data
  }
}

export function isAppError(error: unknown): error is AppError {
  return (
    typeof error === 'object' &&
    error !== null &&
    'code' in error &&
    'message' in error &&
    'retryable' in error
  )
}

/** Normalise anything thrown into an AppError (API errors pass through unchanged). */
export function toAppError(error: unknown, retryable = true): AppError {
  if (isAppError(error)) return error
  return {
    code: ErrorCodes.UNKNOWN,
    message: getErrorMessage(error),
    retryable
  }
}

/** True when `error` is an API error with the given HTTP status. */
export function isHttpStatus(error: unknown, status: number): boolean {
  return isAppError(error) && error.statusCode === status
}

export function getErrorMessage(error: unknown): string {
  if (isAppError(error)) {
    return error.message
  }
  if (error instanceof Error) {
    return error.message
  }
  if (typeof error === 'string') {
    return error
  }
  return 'An unexpected error occurred'
}

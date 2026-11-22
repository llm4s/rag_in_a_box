import axios, { type AxiosError } from 'axios'
import { transformAxiosError } from '@/composables/useApiError'
import { getAuthHeaders } from './credentials'
import { notifyUnauthorized } from './unauthorized'

const client = axios.create({
  baseURL: '/api/v1',
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 30000, // 30 second timeout
})

// Attach credentials (see ./credentials.ts) to every request
client.interceptors.request.use((config) => {
  for (const [name, value] of Object.entries(getAuthHeaders())) {
    config.headers[name] = value
  }
  return config
})

// Response interceptor for error handling
client.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    const appError = transformAxiosError(error)
    // Log for debugging
    console.error(`[API Error] ${appError.code}:`, appError.message, appError.details)
    // 401 = not authenticated / session expired: let the app reset auth state
    // and send the user to the login page. Auth endpoints are excluded (see
    // isAuthEndpoint) so login failures don't loop. 403 is left to the caller.
    if (error.response?.status === 401) {
      notifyUnauthorized(error.config?.url)
    }
    return Promise.reject(appError)
  }
)

export default client

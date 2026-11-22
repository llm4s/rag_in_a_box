import axios, { type AxiosError } from 'axios'
import { transformAxiosError } from '@/composables/useApiError'
import { getAuthHeaders } from './credentials'

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
    return Promise.reject(appError)
  }
)

export default client

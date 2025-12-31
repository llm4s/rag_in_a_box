import axios, { type AxiosError } from 'axios'
import { transformAxiosError } from '@/composables/useApiError'

const client = axios.create({
  baseURL: '/api/v1',
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 30000, // 30 second timeout
})

// Request interceptor for API key
client.interceptors.request.use((config) => {
  const apiKey = localStorage.getItem('ragbox_api_key')
  if (apiKey) {
    config.headers['X-API-Key'] = apiKey
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

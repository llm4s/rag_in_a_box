import axios from 'axios'

const client = axios.create({
  baseURL: '/api/v1',
  headers: {
    'Content-Type': 'application/json',
  },
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
  (error) => {
    if (error.response?.status === 401) {
      console.error('Authentication required')
    } else if (error.response?.status === 403) {
      console.error('Access denied')
    }
    return Promise.reject(error)
  }
)

export default client

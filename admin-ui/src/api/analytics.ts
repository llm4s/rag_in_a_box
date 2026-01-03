import client from './client'
import type {
  QueryLogListResponse,
  QueryAnalyticsSummary,
  QueryLogEntry,
  QueryFeedbackRequest,
  QueryFeedbackResponse
} from '@/types/api'

export interface QueryListParams {
  from?: string
  to?: string
  collection?: string
  page?: number
  pageSize?: number
}

export async function getQueryList(params: QueryListParams = {}): Promise<QueryLogListResponse> {
  const searchParams = new URLSearchParams()
  if (params.from) searchParams.set('from', params.from)
  if (params.to) searchParams.set('to', params.to)
  if (params.collection) searchParams.set('collection', params.collection)
  if (params.page) searchParams.set('page', params.page.toString())
  if (params.pageSize) searchParams.set('pageSize', params.pageSize.toString())

  const queryString = searchParams.toString()
  const url = `/analytics/queries${queryString ? `?${queryString}` : ''}`
  const response = await client.get<QueryLogListResponse>(url)
  return response.data
}

export async function getQuerySummary(from?: string, to?: string): Promise<QueryAnalyticsSummary> {
  const searchParams = new URLSearchParams()
  if (from) searchParams.set('from', from)
  if (to) searchParams.set('to', to)

  const queryString = searchParams.toString()
  const url = `/analytics/queries/summary${queryString ? `?${queryString}` : ''}`
  const response = await client.get<QueryAnalyticsSummary>(url)
  return response.data
}

export async function getQueryById(id: string): Promise<QueryLogEntry> {
  const response = await client.get<QueryLogEntry>(`/analytics/queries/${id}`)
  return response.data
}

export async function submitFeedback(feedback: QueryFeedbackRequest): Promise<QueryFeedbackResponse> {
  const response = await client.post<QueryFeedbackResponse>('/feedback', feedback)
  return response.data
}

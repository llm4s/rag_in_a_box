import client from './client'
import type {
  SuggestionsResponse,
  SuggestionsSummary,
  SuggestionType,
  SuggestionSeverity
} from '@/types/api'

export interface SuggestionsParams {
  from?: string
  to?: string
  collection?: string
  type?: SuggestionType
  severity?: SuggestionSeverity
}

/**
 * Get optimization suggestions based on query analytics.
 */
export async function getSuggestions(params: SuggestionsParams = {}): Promise<SuggestionsResponse> {
  const searchParams = new URLSearchParams()
  if (params.from) searchParams.set('from', params.from)
  if (params.to) searchParams.set('to', params.to)
  if (params.collection) searchParams.set('collection', params.collection)
  if (params.type) searchParams.set('type', params.type)
  if (params.severity) searchParams.set('severity', params.severity)

  const queryString = searchParams.toString()
  const url = `/analytics/suggestions${queryString ? `?${queryString}` : ''}`
  const response = await client.get<SuggestionsResponse>(url)
  return response.data
}

/**
 * Get suggestions summary (counts by severity).
 */
export async function getSuggestionsSummary(from?: string, to?: string): Promise<SuggestionsSummary> {
  const searchParams = new URLSearchParams()
  if (from) searchParams.set('from', from)
  if (to) searchParams.set('to', to)

  const queryString = searchParams.toString()
  const url = `/analytics/suggestions/summary${queryString ? `?${queryString}` : ''}`
  const response = await client.get<SuggestionsSummary>(url)
  return response.data
}

/**
 * Get suggestions for a specific collection.
 */
export async function getCollectionSuggestions(
  collectionName: string,
  from?: string,
  to?: string
): Promise<SuggestionsResponse> {
  const searchParams = new URLSearchParams()
  if (from) searchParams.set('from', from)
  if (to) searchParams.set('to', to)

  const queryString = searchParams.toString()
  const url = `/analytics/suggestions/collection/${encodeURIComponent(collectionName)}${queryString ? `?${queryString}` : ''}`
  const response = await client.get<SuggestionsResponse>(url)
  return response.data
}

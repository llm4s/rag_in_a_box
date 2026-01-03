import client from './client'
import type { QueryRequest, QueryResponse } from '@/types/api'

export async function executeQuery(request: QueryRequest): Promise<QueryResponse> {
  const response = await client.post<QueryResponse>('/query', request)
  return response.data
}

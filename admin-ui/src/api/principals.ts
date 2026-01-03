import client from './client'
import type { Principal, PrincipalListResponse } from '@/types/api'

export async function getUsers(limit = 100, offset = 0): Promise<PrincipalListResponse> {
  const response = await client.get<PrincipalListResponse>('/principals/users', {
    params: { limit, offset }
  })
  return response.data
}

export async function getGroups(limit = 100, offset = 0): Promise<PrincipalListResponse> {
  const response = await client.get<PrincipalListResponse>('/principals/groups', {
    params: { limit, offset }
  })
  return response.data
}

export async function createUser(externalId: string): Promise<Principal> {
  const response = await client.post<Principal>('/principals/users', { externalId })
  return response.data
}

export async function createGroup(externalId: string): Promise<Principal> {
  const response = await client.post<Principal>('/principals/groups', { externalId })
  return response.data
}

export async function deletePrincipal(type: 'users' | 'groups', externalId: string): Promise<void> {
  await client.delete(`/principals/${type}/${externalId}`)
}

export async function lookupPrincipal(externalId: string): Promise<Principal | null> {
  try {
    const response = await client.get<Principal>(`/principals/lookup/${externalId}`)
    return response.data
  } catch {
    return null
  }
}

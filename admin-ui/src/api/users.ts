import client from './client'
import type { User, UserListResponse, CreateUserRequest } from '@/types/api'

export async function getUsers(): Promise<UserListResponse> {
  const response = await client.get<UserListResponse>('/users')
  return response.data
}

export async function createUser(request: CreateUserRequest): Promise<User> {
  const response = await client.post<User>('/users', request)
  return response.data
}

export async function deleteUser(id: number): Promise<void> {
  await client.delete(`/users/${id}`)
}

export async function resetPassword(id: number, password: string): Promise<void> {
  await client.put(`/users/${id}/password`, { password })
}

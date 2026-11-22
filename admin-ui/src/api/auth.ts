import client from './client'
import type { AuthMe, AuthModeResponse, LoginRequest, LoginResponse } from '@/types/api'

/** Default SSO entry point when the server does not advertise one. */
export const DEFAULT_OAUTH_LOGIN_URL = '/api/v1/oauth/login'

/** `GET /auth/mode` – how (and whether) the UI must authenticate. */
export async function getAuthMode(): Promise<AuthModeResponse> {
  const response = await client.get<AuthModeResponse>('/auth/mode')
  return response.data
}

/** `POST /auth/login` – username/password login, returns a JWT. */
export async function login(request: LoginRequest): Promise<LoginResponse> {
  const response = await client.post<LoginResponse>('/auth/login', request)
  return response.data
}

/** `GET /auth/me` – the current principal (401 when not authenticated). */
export async function getMe(): Promise<AuthMe> {
  const response = await client.get<AuthMe>('/auth/me')
  return response.data
}

/** `POST /oauth/logout` – clears the SSO session cookie. */
export async function oauthLogout(): Promise<void> {
  await client.post('/oauth/logout')
}

/** True for a same-origin relative path such as `/admin/documents` (not `//host` or `/\host`). */
export function isRelativePath(path: string): boolean {
  return path.startsWith('/') && !path.startsWith('//') && !path.startsWith('/\\')
}

/** Build the SSO login URL, asking the server to send the browser back to `redirectAfter`. */
export function buildSsoLoginUrl(loginUrl: string | null | undefined, redirectAfter?: string): string {
  const base = loginUrl || DEFAULT_OAUTH_LOGIN_URL
  if (!redirectAfter || !isRelativePath(redirectAfter)) return base
  const sep = base.includes('?') ? '&' : '?'
  return `${base}${sep}redirect_after=${encodeURIComponent(redirectAfter)}`
}

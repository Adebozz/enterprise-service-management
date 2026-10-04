import { HttpResponse } from 'msw'
import type { ApiProblem, ErrorCode } from '@/api/errors'
import type { components } from '@/api/schema'
import type { AuthenticatedUser } from '@/auth/roles'
import type { TokenResponse } from '@/auth/session'

export const ada: AuthenticatedUser = {
  id: '0199b2c4-6a1e-7c3e-9f00-000000000001',
  email: 'ada@example.com',
  displayName: 'Ada Lovelace',
  role: 'AGENT',
}

export const profileOf = (user: AuthenticatedUser): components['schemas']['MyProfileResponse'] => ({
  ...user,
  teams: [{ id: '0199b2c4-6a1e-7c3e-9f00-0000000000aa', name: 'Network Team' }],
})

export const sessionFor = (user: AuthenticatedUser, accessToken = 'access-token-1'): TokenResponse => ({
  accessToken,
  tokenType: 'Bearer',
  expiresIn: 900,
  user,
})

/** An error response exactly as the backend sends it (RFC 9457 + our extensions). */
export function problem(status: number, code: ErrorCode, detail = 'Request failed') {
  const body: ApiProblem = {
    type: 'about:blank',
    title: 'Error',
    status,
    detail,
    instance: '/api/test',
    code,
    timestamp: '2026-10-03T09:00:00Z',
    correlationId: 'test-correlation-id',
    fieldErrors: null,
  }
  return HttpResponse.json(body, { status, headers: { 'Content-Type': 'application/problem+json' } })
}

import { delay, http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { onSessionEnded } from '@/auth/session'
import { tokenStore } from '@/auth/tokenStore'
import { ada, problem, profileOf, sessionFor } from '@/test/fixtures'
import { server } from '@/test/server'
import { api } from './client'
import { isApiError, unwrap } from './errors'

/** Responds 200 only to the given token, like the real API with an expired access token. */
function acceptsOnly(token: string, body: object) {
  return ({ request }: { request: Request }) =>
    request.headers.get('Authorization') === `Bearer ${token}`
      ? HttpResponse.json(body)
      : problem(401, 'AUTHENTICATION_REQUIRED')
}

describe('authenticated API client', () => {
  it('sends the in-memory access token', async () => {
    tokenStore.set('valid-token')
    server.use(http.get('/api/users/me', acceptsOnly('valid-token', profileOf(ada))))

    const { response } = await api.GET('/api/users/me')

    expect(response.status).toBe(200)
  })

  it('refreshes once for several simultaneous 401s and retries each request', async () => {
    tokenStore.set('expired-token')
    let refreshCalls = 0
    server.use(
      http.post('/api/auth/refresh', async () => {
        refreshCalls++
        await delay(25) // keep the refresh in flight while the second 401 arrives
        return HttpResponse.json(sessionFor(ada, 'fresh-token'))
      }),
      http.get('/api/users/me', acceptsOnly('fresh-token', profileOf(ada))),
      http.get('/api/teams', acceptsOnly('fresh-token', [])),
    )

    const [me, teams] = await Promise.all([api.GET('/api/users/me'), api.GET('/api/teams')])

    expect(refreshCalls).toBe(1)
    expect(me.response.status).toBe(200)
    expect(teams.response.status).toBe(200)
    expect(tokenStore.get()).toBe('fresh-token')
  })

  it('retries a POST with its original body after refreshing', async () => {
    tokenStore.set('expired-token')
    server.use(
      http.post('/api/auth/refresh', () => HttpResponse.json(sessionFor(ada, 'fresh-token'))),
      http.post('/api/tickets/:id/comments', async ({ request }) => {
        if (request.headers.get('Authorization') !== 'Bearer fresh-token') {
          return problem(401, 'AUTHENTICATION_REQUIRED')
        }
        const body = (await request.json()) as { body: string }
        return HttpResponse.json({ received: body.body }, { status: 201 })
      }),
    )

    const result = await api.POST('/api/tickets/{id}/comments', {
      params: { path: { id: 'ticket-1' } },
      body: { body: 'Still broken', visibility: 'PUBLIC' },
    })

    expect(result.response.status).toBe(201)
    expect(result.data).toEqual({ received: 'Still broken' })
  })

  it('ends the session without looping when the refresh fails', async () => {
    tokenStore.set('expired-token')
    const sessionEnded = vi.fn()
    const unsubscribe = onSessionEnded(sessionEnded)
    let protectedCalls = 0
    server.use(
      http.get('/api/users/me', () => {
        protectedCalls++
        return problem(401, 'AUTHENTICATION_REQUIRED')
      }),
    ) // default refresh handler answers 401

    const result = await api.GET('/api/users/me')
    unsubscribe()

    expect(result.response.status).toBe(401)
    expect(protectedCalls).toBe(1)
    expect(sessionEnded).toHaveBeenCalledOnce()
    expect(tokenStore.get()).toBeNull()
  })

  it('never tries to refresh when the login itself is rejected', async () => {
    let refreshCalls = 0
    server.use(
      http.post('/api/auth/login', () => problem(401, 'INVALID_CREDENTIALS', 'Invalid email or password')),
      http.post('/api/auth/refresh', () => {
        refreshCalls++
        return problem(401, 'REFRESH_TOKEN_INVALID')
      }),
    )

    const result = await api.POST('/api/auth/login', { body: { email: 'a@b.c', password: 'nope' } })

    expect(result.response.status).toBe(401)
    expect(refreshCalls).toBe(0)
  })

  it('turns problem responses into typed ApiErrors', async () => {
    tokenStore.set('valid-token')
    server.use(http.get('/api/tickets/:id', () => problem(404, 'RESOURCE_NOT_FOUND', 'Ticket x was not found')))

    const error: unknown = await api
      .GET('/api/tickets/{id}', { params: { path: { id: 'x' } } })
      .then(unwrap)
      .catch((e: unknown) => e)

    expect(isApiError(error)).toBe(true)
    expect(error).toMatchObject({
      status: 404,
      code: 'RESOURCE_NOT_FOUND',
      message: 'Ticket x was not found',
      correlationId: 'test-correlation-id',
    })
  })
})

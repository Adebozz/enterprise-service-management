import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { tokenStore } from '@/auth/tokenStore'
import { ada, problem, profileOf, sessionFor } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'
import { server } from '@/test/server'

describe('session lifecycle in the app', () => {
  it('sends anonymous visitors to the login page', async () => {
    const { router } = renderApp('/')

    expect(await screen.findByRole('heading', { name: 'Sign in to Service Desk' })).toBeVisible()
    expect(router.state.location.pathname).toBe('/login')
  })

  it('restores the session after a page reload through the refresh cookie', async () => {
    server.use(
      http.post('/api/auth/refresh', () => HttpResponse.json(sessionFor(ada))),
      http.get('/api/users/me', () => HttpResponse.json(profileOf(ada))),
    )

    renderApp('/')

    expect(await screen.findByRole('heading', { name: 'Welcome, Ada Lovelace' })).toBeVisible()
    expect(await screen.findByText('Network Team')).toBeVisible()
    expect(tokenStore.get()).toBe('access-token-1')
  })

  it('signs out: revokes the session on the server and returns to login', async () => {
    let logoutCalls = 0
    server.use(
      http.post('/api/auth/refresh', () => HttpResponse.json(sessionFor(ada))),
      http.get('/api/users/me', () => HttpResponse.json(profileOf(ada))),
      http.post('/api/auth/logout', () => {
        logoutCalls++
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const { user } = renderApp('/')

    await user.click(await screen.findByRole('button', { name: 'Account menu for Ada Lovelace' }))
    await user.click(within(await screen.findByRole('menu')).getByRole('menuitem', { name: /sign out/i }))

    expect(await screen.findByRole('heading', { name: 'Sign in to Service Desk' })).toBeVisible()
    expect(logoutCalls).toBe(1)
    expect(tokenStore.get()).toBeNull()
  })

  it('returns to login when the session expires while the app is in use', async () => {
    let refreshes = 0
    server.use(
      // First refresh restores the session at start-up; the next one finds it revoked.
      http.post('/api/auth/refresh', () =>
        ++refreshes === 1 ? HttpResponse.json(sessionFor(ada)) : problem(401, 'REFRESH_TOKEN_INVALID'),
      ),
      http.get('/api/users/me', () => problem(401, 'AUTHENTICATION_REQUIRED')),
    )

    renderApp('/')

    expect(await screen.findByRole('heading', { name: 'Sign in to Service Desk' })).toBeVisible()
    expect(refreshes).toBe(2)
  })

  it('shows a not-found page for unknown addresses', async () => {
    renderApp('/no/such/page')

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeVisible()
  })
})

import { screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { ada, problem, profileOf, sessionFor } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'
import { server } from '@/test/server'

describe('login page', () => {
  it('validates the form before calling the API', async () => {
    const { user } = renderApp('/login')

    await user.click(await screen.findByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Enter a valid email address')).toBeVisible()
    expect(screen.getByText('Enter your password')).toBeVisible()
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true')
  })

  it('explains rejected credentials without revealing which part was wrong', async () => {
    server.use(http.post('/api/auth/login', () => problem(401, 'INVALID_CREDENTIALS')))
    const { user } = renderApp('/login')

    await user.type(await screen.findByLabelText('Email'), 'ada@example.com')
    await user.type(screen.getByLabelText('Password'), 'wrong-password')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password.')
  })

  it('signs in and returns to the page the user originally asked for', async () => {
    server.use(
      http.post('/api/auth/login', async ({ request }) => {
        const body = (await request.json()) as { email: string; password: string }
        return body.password === 'correct-password'
          ? HttpResponse.json(sessionFor(ada))
          : problem(401, 'INVALID_CREDENTIALS')
      }),
      http.get('/api/users/me', () => HttpResponse.json(profileOf(ada))),
    )
    const { user, router } = renderApp('/') // not signed in: redirected to /login

    await user.type(await screen.findByLabelText('Email'), 'ada@example.com')
    await user.type(screen.getByLabelText('Password'), 'correct-password')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('heading', { name: 'Welcome, Ada Lovelace' })).toBeVisible()
    expect(router.state.location.pathname).toBe('/')
  })
})

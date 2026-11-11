import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { SlaPolicy } from '@/api/types'
import type { AuthenticatedUser } from '@/auth/roles'
import { problem } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'
import { server } from '@/test/server'
import { agent, signedInAs, staffContext } from '@/test/tickets'

const admin: AuthenticatedUser = {
  id: '0199b2c4-6a1e-7c3e-9f00-000000000301',
  email: 'ann@example.com',
  displayName: 'Ann Admin',
  role: 'ADMIN',
}

const p1: SlaPolicy = { id: 'p1', ticketType: 'INCIDENT', priority: 'P1', responseMinutes: 15, resolutionMinutes: 240, version: 0 }
const p2: SlaPolicy = { id: 'p2', ticketType: 'INCIDENT', priority: 'P2', responseMinutes: 60, resolutionMinutes: 480, version: 0 }

/** Policies held in memory, recording what the page sends. */
function policyBackend() {
  const state = { policies: [p1, p2], sent: [] as unknown[] }
  server.use(
    http.get('/api/admin/sla-policies', () => HttpResponse.json(state.policies)),
    http.put('/api/admin/sla-policies/:id', async ({ params, request }) => {
      const body = (await request.json()) as { responseMinutes: number; resolutionMinutes: number; version: number }
      state.sent.push({ id: params.id, ...body })
      if (body.responseMinutes > body.resolutionMinutes) {
        return problem(400, 'VALIDATION_FAILED', 'The response target can’t be longer than the resolution target')
      }
      state.policies = state.policies.map((p) => (p.id === params.id ? { ...p, ...body, version: p.version + 1 } : p))
      return HttpResponse.json(state.policies.find((p) => p.id === params.id))
    }),
  )
  return state
}

describe('SLA targets page', () => {
  it('lists targets with readable durations and saves a change', async () => {
    signedInAs(admin)
    staffContext(admin)
    const state = policyBackend()
    const { user } = renderApp('/admin/sla')

    const response = await screen.findByLabelText('Incident P2 first response, in minutes')
    expect(screen.getByText('4 h')).toBeVisible() // P1 resolution: 240 minutes

    await user.clear(response)
    await user.type(response, '30')
    await user.click(screen.getByRole('button', { name: 'Save Incident P2' }))

    expect(await screen.findByText('30 min')).toBeVisible()
    expect(state.sent).toEqual([{ id: 'p2', responseMinutes: 30, resolutionMinutes: 480, version: 0 }])
    expect(screen.getByRole('button', { name: 'Save Incident P2' })).toBeDisabled() // saved, nothing pending
  })

  it('shows why the server refused a change', async () => {
    signedInAs(admin)
    staffContext(admin)
    policyBackend()
    const { user } = renderApp('/admin/sla')

    const response = await screen.findByLabelText('Incident P1 first response, in minutes')
    await user.clear(response)
    await user.type(response, '999')
    await user.click(screen.getByRole('button', { name: 'Save Incident P1' }))

    const row = screen.getByRole('button', { name: 'Save Incident P1' }).closest('tr')
    expect(await within(row as HTMLElement).findByRole('alert')).toHaveTextContent('longer than the resolution target')
  })

  it('is only in the navigation for admins', async () => {
    signedInAs(admin)
    staffContext(admin)
    policyBackend()
    renderApp('/admin/sla')
    expect(await screen.findByRole('link', { name: 'SLA targets' })).toBeVisible()
  })

  it('is refused to agents', async () => {
    signedInAs(agent)
    staffContext(agent)

    renderApp('/admin/sla')

    expect(await screen.findByRole('heading', { name: 'You don’t have access to this page' })).toBeVisible()
    expect(screen.queryByRole('link', { name: 'SLA targets' })).not.toBeInTheDocument()
  })
})

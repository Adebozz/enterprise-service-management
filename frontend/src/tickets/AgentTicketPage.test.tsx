import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { AvailableTransition, Ticket } from '@/api/types'
import { renderApp } from '@/test/renderApp'
import { server } from '@/test/server'
import { aComment, aTicket, agent, signedInAs, staffContext } from '@/test/tickets'

/** In-memory ticket backend from the agent's point of view, recording what the UI sends. */
function backend(initial: Ticket, transitions: AvailableTransition[] = []) {
  const state = {
    ticket: initial,
    comments: [aComment({ id: 'n1', visibility: 'INTERNAL', body: 'Switch port 12 is flapping' })],
    sent: { transitions: [] as unknown[], assignments: [] as unknown[], comments: [] as unknown[] },
  }
  server.use(
    http.get('/api/tickets/:id', () => HttpResponse.json(state.ticket)),
    http.get('/api/tickets/:id/comments', () => HttpResponse.json(state.comments)),
    http.get('/api/tickets/:id/transitions', () => HttpResponse.json(transitions)),
    http.get('/api/teams/:id/members', () => HttpResponse.json([])),
    http.post('/api/tickets/:id/transitions', async ({ request }) => {
      const body = (await request.json()) as { targetStatus: string }
      state.sent.transitions.push(body)
      state.ticket = { ...state.ticket, status: body.targetStatus, version: state.ticket.version + 1 }
      return HttpResponse.json(state.ticket)
    }),
    http.put('/api/tickets/:id/assignment', async ({ request }) => {
      const body = (await request.json()) as { teamId: string; assigneeId?: string }
      state.sent.assignments.push(body)
      state.ticket = {
        ...state.ticket,
        assignee: body.assigneeId ? { id: body.assigneeId, name: 'Alex Agent' } : null,
        status: body.assigneeId ? 'ASSIGNED' : 'NEW',
        version: state.ticket.version + 1,
      }
      return HttpResponse.json({
        ticketId: state.ticket.id,
        reference: state.ticket.reference,
        status: state.ticket.status,
        assignedTeam: { id: body.teamId, name: body.teamId === 'team-hardware' ? 'Hardware Team' : 'Network Team' },
        assignee: state.ticket.assignee,
        version: state.ticket.version,
      })
    }),
    http.post('/api/tickets/:id/comments', async ({ request }) => {
      const body = (await request.json()) as { body: string; visibility: 'PUBLIC' | 'INTERNAL' }
      state.sent.comments.push(body)
      const created = aComment({ id: `c${state.comments.length + 1}`, author: { id: agent.id, name: agent.displayName }, ...body })
      state.comments.push(created)
      return HttpResponse.json(created, { status: 201 })
    }),
  )
  return state
}

describe('ticket page for support staff', () => {
  it('resolves with a required resolution code and notes', async () => {
    signedInAs(agent)
    staffContext(agent)
    const state = backend(aTicket({ status: 'IN_PROGRESS', assignee: { id: agent.id, name: agent.displayName }, version: 3 }), [
      { targetStatus: 'RESOLVED', label: 'Resolve', requirements: ['RESOLUTION'] },
    ])
    const { user } = renderApp('/tickets/ticket-1')

    await user.click(await screen.findByRole('button', { name: 'Resolve' }))
    const dialog = await screen.findByRole('dialog', { name: 'Resolve' })
    await user.click(within(dialog).getByRole('button', { name: 'Resolve' }))
    expect(await within(dialog).findByText('Choose how it was resolved')).toBeVisible()
    expect(within(dialog).getByText('Describe what was done')).toBeVisible()

    await user.selectOptions(within(dialog).getByLabelText('Resolution'), 'Workaround provided')
    await user.type(within(dialog).getByLabelText('Resolution notes'), 'Use the wired dock until the AP is replaced')
    await user.click(within(dialog).getByRole('button', { name: 'Resolve' }))

    await screen.findByText('Resolved')
    expect(state.sent.transitions).toEqual([
      { targetStatus: 'RESOLVED', version: 3, resolutionCode: 'WORKAROUND', notes: 'Use the wired dock until the AP is replaced' },
    ])
  })

  it('does not mark the ticket as one of "My tickets" in the navigation', async () => {
    signedInAs(agent)
    staffContext(agent)
    backend(aTicket())
    renderApp('/tickets/ticket-1')

    const nav = await screen.findByRole('navigation', { name: 'Main' })
    await screen.findByRole('heading', { level: 1 })
    expect(within(nav).getByRole('link', { name: 'My tickets' })).not.toHaveAttribute('aria-current')
  })

  it('takes an unassigned ticket from the team queue', async () => {
    signedInAs(agent)
    staffContext(agent)
    const state = backend(aTicket({ version: 0 }))
    const { user } = renderApp('/tickets/ticket-1')

    await user.click(await screen.findByRole('button', { name: 'Take it' }))

    expect(await screen.findByRole('button', { name: 'Release' })).toBeVisible()
    expect(state.sent.assignments).toEqual([{ teamId: 'team-network', assigneeId: agent.id, version: 0 }])
  })

  it('transfers to another team and returns to the queue with a notice', async () => {
    signedInAs(agent)
    staffContext(agent)
    const state = backend(aTicket())
    server.use(http.get('/api/tickets', () => HttpResponse.json({ content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 })))
    const { user, router } = renderApp('/tickets/ticket-1')

    await user.selectOptions(await screen.findByLabelText('Transfer to another team'), 'Hardware Team')
    await user.click(screen.getByRole('button', { name: 'Transfer' }))

    expect(await screen.findByText('INC-000042 was transferred to Hardware Team.')).toBeVisible()
    expect(router.state.location.pathname).toBe('/queue')
    expect(state.sent.assignments).toEqual([{ teamId: 'team-hardware', version: 0 }])
  })

  it('writes internal notes that are clearly marked as staff-only', async () => {
    signedInAs(agent)
    staffContext(agent)
    const state = backend(aTicket())
    const { user } = renderApp('/tickets/ticket-1')

    expect(await screen.findByText('Switch port 12 is flapping')).toBeVisible()
    expect(screen.getByText('Internal note · visible to support staff only')).toBeVisible()

    await user.click(screen.getByRole('radio', { name: 'Internal note' }))
    await user.type(screen.getByRole('textbox', { name: 'Internal note' }), 'Replaced the switch')
    await user.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText('Replaced the switch')).toBeVisible()
    expect(state.sent.comments).toEqual([{ body: 'Replaced the switch', visibility: 'INTERNAL' }])
  })

  it('shows a readable history built from the audit trail', async () => {
    signedInAs(agent)
    staffContext(agent)
    backend(aTicket())
    server.use(
      http.get('/api/tickets/:id/history', () =>
        HttpResponse.json([
          {
            occurredAt: '2026-10-05T09:00:00Z',
            actor: { id: agent.id, name: 'Alex Agent' },
            action: 'TICKET_ASSIGNMENT_CHANGED',
            oldValue: { teamId: 'team-network', assigneeId: null, status: 'NEW' },
            newValue: { teamId: 'team-network', assigneeId: agent.id, status: 'ASSIGNED' },
            metadata: null,
            names: { [agent.id]: 'Alex Agent', 'team-network': 'Network Team' },
          },
        ]),
      ),
    )

    renderApp('/tickets/ticket-1')

    const history = await screen.findByRole('region', { name: 'History' })
    expect(await within(history).findByText(/assigned it to Alex Agent/)).toBeVisible()
  })
})

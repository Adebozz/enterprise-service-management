import { screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { AvailableTransition, Ticket } from '@/api/types'
import { renderApp } from '@/test/renderApp'
import { server } from '@/test/server'
import { aSummary, aTicket, agent, incidentCategories, signedInAs, staffContext } from '@/test/tickets'

const aProblem = (overrides: Partial<Ticket> = {}): Ticket =>
  aTicket({
    id: 'problem-1',
    reference: 'PRB-000007',
    type: 'PROBLEM',
    title: 'VPN refuses connections at busy times',
    status: 'INVESTIGATING',
    assignee: { id: agent.id, name: agent.displayName },
    version: 4,
    incident: null,
    problem: {
      rootCause: null,
      workaround: null,
      knownErrorAt: null,
      fixNotes: null,
      reopenCount: 0,
      incidents: [{ id: 'ticket-1', reference: 'INC-000042', title: 'VPN error 809', status: 'IN_PROGRESS' }],
    },
    ...overrides,
  })

const page = (content: unknown[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 })

/** One ticket served at /api/tickets/:id, recording what the UI sends to the problem endpoints. */
function backend(ticket: Ticket, transitions: AvailableTransition[] = []) {
  const state = { ticket, sent: [] as unknown[] }
  server.use(
    http.get('/api/tickets/:id', () => HttpResponse.json(state.ticket)),
    http.get('/api/tickets/:id/comments', () => HttpResponse.json([])),
    http.get('/api/tickets/:id/transitions', () => HttpResponse.json(transitions)),
    http.get('/api/teams/:id/members', () => HttpResponse.json([])),
    http.put('/api/problems/:id/analysis', async ({ request }) => {
      const body = (await request.json()) as { rootCause: string; workaround: string }
      state.sent.push(body)
      state.ticket = {
        ...state.ticket,
        version: state.ticket.version + 1,
        problem: { ...state.ticket.problem!, rootCause: body.rootCause, workaround: body.workaround },
      }
      return new HttpResponse(null, { status: 204 })
    }),
    http.put('/api/incidents/:id/problem', async ({ request }) => {
      state.sent.push(await request.json())
      state.ticket = {
        ...state.ticket,
        version: state.ticket.version + 1,
        incident: {
          ...state.ticket.incident!,
          problem: { id: 'problem-1', reference: 'PRB-000007', title: 'VPN refuses connections', status: 'KNOWN_ERROR', workaround: 'Use vpn2' },
        },
      }
      return new HttpResponse(null, { status: 204 })
    }),
    http.delete('/api/incidents/:id/problem', ({ request }) => {
      state.sent.push(new URL(request.url).searchParams.get('version'))
      state.ticket = { ...state.ticket, incident: { ...state.ticket.incident!, problem: null } }
      return new HttpResponse(null, { status: 204 })
    }),
  )
  return state
}

describe('problem management', () => {
  it('lists open problems for support with a way to raise one', async () => {
    signedInAs(agent)
    staffContext(agent)
    let query: URLSearchParams | undefined
    server.use(
      http.get('/api/tickets', ({ request }) => {
        query = new URL(request.url).searchParams
        return HttpResponse.json(page([aSummary({ id: 'problem-1', reference: 'PRB-000007', type: 'PROBLEM', title: 'VPN refuses connections', status: 'KNOWN_ERROR' })]))
      }),
    )
    renderApp('/problems')

    expect(await screen.findByRole('link', { name: 'VPN refuses connections' })).toHaveAttribute('href', '/tickets/problem-1')
    expect(screen.getByText('Known error')).toBeVisible()
    expect(screen.getByRole('link', { name: 'Raise a problem' })).toHaveAttribute('href', '/problems/new')
    expect(query?.getAll('type')).toEqual(['PROBLEM'])
    expect(query?.get('open')).toBe('true')
  })

  it('records the analysis on a problem and lists the incidents it explains', async () => {
    signedInAs(agent)
    staffContext(agent)
    const state = backend(aProblem())
    const { user } = renderApp('/tickets/problem-1')

    expect(await screen.findByText('Not known yet.')).toBeVisible()
    expect(screen.getByText('Record the root cause and a workaround to mark this a known error.')).toBeVisible()
    expect(screen.getByRole('link', { name: 'VPN error 809' })).toHaveAttribute('href', '/tickets/ticket-1')
    // Problems have no service levels, and no requester to reply to: every message is an internal note.
    expect(screen.queryByRole('heading', { name: /service level/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: 'Reply to requester' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Internal note')).toBeVisible()

    await user.click(screen.getByRole('button', { name: 'Edit analysis' }))
    await user.type(screen.getByLabelText('Root cause'), 'Gateway licence is full')
    await user.type(screen.getByLabelText('Workaround'), 'Use vpn2')
    await user.click(screen.getByRole('button', { name: 'Save analysis' }))

    expect(await screen.findByText('Gateway licence is full')).toBeVisible()
    expect(state.sent).toEqual([{ rootCause: 'Gateway licence is full', workaround: 'Use vpn2', version: 4 }])
  })

  it('does not offer to edit another team’s analysis', async () => {
    signedInAs(agent)
    staffContext(agent, ['team-hardware'])
    backend(aProblem({ assignee: null, problem: { ...aProblem().problem!, rootCause: 'Licence', workaround: 'vpn2' } }))
    renderApp('/tickets/problem-1')

    expect(await screen.findByText('Licence')).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Edit analysis' })).not.toBeInTheDocument()
  })

  it('asks for the permanent fix when resolving', async () => {
    signedInAs(agent)
    staffContext(agent)
    let sent: unknown
    backend(aProblem({ status: 'KNOWN_ERROR' }), [{ targetStatus: 'RESOLVED', label: 'Resolve', requirements: ['FIX_NOTES'] }])
    server.use(
      http.post('/api/tickets/:id/transitions', async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json(aProblem({ status: 'RESOLVED' }))
      }),
    )
    const { user } = renderApp('/tickets/problem-1')

    await user.click(await screen.findByRole('button', { name: 'Resolve' }))
    const dialog = await screen.findByRole('dialog', { name: 'Resolve' })
    expect(within(dialog).getByText(/sent as a note to the open incidents/)).toBeVisible()
    await user.click(within(dialog).getByRole('button', { name: 'Resolve' }))
    expect(await within(dialog).findByText('Describe what was done')).toBeVisible()

    await user.type(within(dialog).getByLabelText('What permanently fixed it?'), 'Licence raised to 500 sessions')
    await user.click(within(dialog).getByRole('button', { name: 'Resolve' }))

    await waitFor(() => expect(sent).toEqual({ targetStatus: 'RESOLVED', version: 4, notes: 'Licence raised to 500 sessions' }))
  })

  it('links an incident to an open problem and shows its workaround', async () => {
    signedInAs(agent)
    staffContext(agent)
    const state = backend(aTicket({ status: 'IN_PROGRESS', version: 2 }))
    server.use(
      http.get('/api/tickets', () =>
        HttpResponse.json(
          page([
            aSummary({ id: 'problem-1', reference: 'PRB-000007', type: 'PROBLEM', title: 'VPN refuses connections', status: 'KNOWN_ERROR' }),
            aSummary({ id: 'problem-2', reference: 'PRB-000003', type: 'PROBLEM', title: 'Fixed already', status: 'RESOLVED' }),
          ]),
        ),
      ),
    )
    const { user } = renderApp('/tickets/ticket-1')

    const select = await screen.findByLabelText('Open problems')
    // A resolved problem takes no more incidents, so it isn't offered.
    expect(within(select).queryByText(/Fixed already/)).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Raise a new problem from this incident' })).toHaveAttribute(
      'href',
      '/problems/new?incident=ticket-1',
    )
    await user.selectOptions(select, 'PRB-000007 · VPN refuses connections')
    await user.click(screen.getByRole('button', { name: 'Link to problem' }))

    expect(await screen.findByText('Use vpn2')).toBeVisible()
    expect(state.sent).toEqual([{ problemId: 'problem-1', version: 2 }])

    await user.click(screen.getByRole('button', { name: 'Remove link' }))
    expect(await screen.findByLabelText('Open problems')).toBeVisible()
    expect(state.sent[1]).toBe('3')
  })

  it('raises a problem from an incident, which gets linked', async () => {
    signedInAs(agent)
    staffContext(agent)
    let sent: unknown
    server.use(
      http.get('/api/tickets/:id', () => HttpResponse.json(aTicket())),
      http.get('/api/categories', ({ request }) => {
        expect(new URL(request.url).searchParams.get('type')).toBe('PROBLEM')
        return HttpResponse.json(incidentCategories)
      }),
      http.post('/api/problems', async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json({ id: 'problem-1', reference: 'PRB-000007', type: 'PROBLEM', status: 'NEW', priority: 'P2' }, { status: 201 })
      }),
    )
    const { user, router } = renderApp('/problems/new?incident=ticket-1')

    expect(await screen.findByText(/which will be linked to it/)).toHaveTextContent('INC-000042')
    await user.type(screen.getByLabelText('Title'), 'VPN refuses connections')
    await user.type(screen.getByLabelText('What keeps happening?'), 'Error 809 at busy times')
    await user.selectOptions(await screen.findByLabelText('Category'), 'Network')
    await user.click(screen.getByRole('button', { name: 'Raise problem' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/tickets/problem-1'))
    expect(sent).toEqual({
      title: 'VPN refuses connections',
      description: 'Error 809 at busy times',
      categoryId: 'cat-network',
      impact: 'MEDIUM',
      urgency: 'MEDIUM',
      incidentId: 'ticket-1',
    })
  })
})

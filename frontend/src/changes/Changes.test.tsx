import { screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { AvailableTransition, ChangeDetails, ScheduledChange, Ticket } from '@/api/types'
import { renderApp } from '@/test/renderApp'
import { server } from '@/test/server'
import { aSummary, aTicket, agent, incidentCategories, signedInAs, staffContext } from '@/test/tickets'

const plan: ChangeDetails = {
  changeType: 'NORMAL',
  risk: 'MEDIUM',
  implementationPlan: 'Upload the licence and restart',
  backoutPlan: 'Restore the old licence',
  plannedStart: '2026-11-16T07:00:00Z',
  plannedEnd: '2026-11-16T09:00:00Z',
  implementedAt: null,
  outcome: null,
  reviewNotes: null,
  problem: { id: 'problem-1', reference: 'PRB-000001', title: 'VPN refuses connections', status: 'KNOWN_ERROR' },
  clashes: [],
}

const aChange = (overrides: Partial<Ticket> = {}, change: Partial<ChangeDetails> = {}): Ticket =>
  aTicket({
    id: 'change-1',
    reference: 'CHG-000001',
    type: 'CHANGE',
    title: 'Raise the VPN licence',
    status: 'ASSIGNED',
    assignee: { id: agent.id, name: agent.displayName },
    version: 3,
    incident: null,
    change: { ...plan, ...change },
    ...overrides,
  })

const scheduled = (overrides: Partial<ScheduledChange>): ScheduledChange => ({
  id: 'change-1',
  reference: 'CHG-000001',
  title: 'Raise the VPN licence',
  status: 'APPROVAL_PENDING',
  changeType: 'NORMAL',
  risk: 'MEDIUM',
  plannedStart: '2026-11-16T07:00:00Z',
  plannedEnd: '2026-11-16T09:00:00Z',
  team: { id: 'team-network', name: 'Network Team' },
  assignee: { id: agent.id, name: agent.displayName },
  ...overrides,
})

const page = (content: unknown[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 })

/** One change served at /api/tickets/:id, recording what the UI sends to the change endpoints. */
function backend(ticket: Ticket, transitions: AvailableTransition[] = []) {
  const state = { ticket, sent: [] as unknown[] }
  server.use(
    http.get('/api/tickets/:id', () => HttpResponse.json(state.ticket)),
    http.get('/api/tickets/:id/comments', () => HttpResponse.json([])),
    http.get('/api/tickets/:id/transitions', () => HttpResponse.json(transitions)),
    http.get('/api/tickets/:id/approval', () => HttpResponse.json({ code: 'RESOURCE_NOT_FOUND', status: 404 }, { status: 404 })),
    http.get('/api/teams/:id/members', () => HttpResponse.json([])),
    http.put('/api/changes/:id/plan', async ({ request }) => {
      const body = (await request.json()) as ChangeDetails
      state.sent.push(body)
      state.ticket = { ...state.ticket, version: state.ticket.version + 1, change: { ...state.ticket.change!, ...body } }
      return new HttpResponse(null, { status: 204 })
    }),
    http.put('/api/changes/:id/outcome', async ({ request }) => {
      const body = (await request.json()) as { outcome: ChangeDetails['outcome']; notes: string }
      state.sent.push(body)
      state.ticket = {
        ...state.ticket,
        version: state.ticket.version + 1,
        change: { ...state.ticket.change!, outcome: body.outcome, reviewNotes: body.notes },
      }
      return new HttpResponse(null, { status: 204 })
    }),
  )
  return state
}

describe('change management', () => {
  it('shows the schedule, flagging overlapping windows, and lists open changes', async () => {
    signedInAs(agent)
    staffContext(agent)
    let query: URLSearchParams | undefined
    server.use(
      http.get('/api/changes/schedule', () =>
        HttpResponse.json([
          scheduled({}),
          scheduled({
            id: 'change-2',
            reference: 'CHG-000002',
            title: 'Printer firmware',
            status: 'APPROVED',
            changeType: 'STANDARD',
            risk: 'LOW',
            plannedStart: '2026-11-16T08:00:00Z',
            plannedEnd: '2026-11-16T10:00:00Z',
          }),
          scheduled({ id: 'change-3', reference: 'CHG-000003', title: 'Next week', plannedStart: '2026-11-23T07:00:00Z', plannedEnd: '2026-11-23T08:00:00Z' }),
        ]),
      ),
      http.get('/api/tickets', ({ request }) => {
        query = new URL(request.url).searchParams
        return HttpResponse.json(page([aSummary({ id: 'change-9', reference: 'CHG-000009', type: 'CHANGE', title: 'Draft change', status: 'NEW' })]))
      }),
    )
    renderApp('/changes')

    const schedule = await screen.findByRole('list', { name: 'Change schedule' })
    const entries = within(schedule).getAllByRole('listitem')
    expect(entries).toHaveLength(3)
    expect(within(entries[0]!).getByText('Overlaps another change')).toBeVisible()
    expect(within(entries[1]!).getByText('Standard')).toBeVisible()
    expect(within(entries[2]!).queryByText('Overlaps another change')).not.toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Draft change' })).toHaveAttribute('href', '/tickets/change-9')
    expect(screen.getByRole('link', { name: 'Raise a change' })).toHaveAttribute('href', '/changes/new')
    expect(query?.getAll('type')).toEqual(['CHANGE'])
    expect(query?.get('open')).toBe('true')
  })

  it('shows the plan, the problem it fixes and clashing changes, and lets the owner edit the plan', async () => {
    signedInAs(agent)
    staffContext(agent)
    const state = backend(
      aChange({}, { clashes: [{ id: 'change-2', reference: 'CHG-000002', title: 'Printer firmware', status: 'APPROVED' }] }),
      [{ targetStatus: 'APPROVAL_PENDING', label: 'Submit for approval', requirements: [] }],
    )
    const { user } = renderApp('/tickets/change-1')

    expect(await screen.findByText('Upload the licence and restart')).toBeVisible()
    expect(screen.getByRole('link', { name: 'VPN refuses connections' })).toHaveAttribute('href', '/tickets/problem-1')
    expect(screen.getByText(/Another change is scheduled during this window/)).toBeVisible()
    expect(screen.getByRole('link', { name: 'Printer firmware' })).toHaveAttribute('href', '/tickets/change-2')
    expect(screen.getByRole('button', { name: 'Submit for approval' })).toBeVisible()
    // Changes are staff work: no service levels, and no requester to reply to.
    expect(screen.queryByRole('heading', { name: /service level/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: 'Reply to requester' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '← Changes' })).toHaveAttribute('href', '/changes')

    await user.click(screen.getByRole('button', { name: 'Edit plan' }))
    await user.selectOptions(screen.getByLabelText('Change type'), 'STANDARD')
    await user.click(screen.getByRole('button', { name: 'Save plan' }))
    expect(await screen.findByText('A standard change is low risk; choose a normal change for this risk')).toBeVisible()

    await user.selectOptions(screen.getByLabelText('Risk'), 'LOW')
    await user.clear(screen.getByLabelText('Backout plan'))
    await user.type(screen.getByLabelText('Backout plan'), 'Reinstall the old licence file')
    await user.click(screen.getByRole('button', { name: 'Save plan' }))

    expect(await screen.findByText('Reinstall the old licence file')).toBeVisible()
    expect(state.sent).toEqual([
      expect.objectContaining({
        changeType: 'STANDARD',
        risk: 'LOW',
        backoutPlan: 'Reinstall the old licence file',
        plannedStart: '2026-11-16T07:00:00.000Z',
        plannedEnd: '2026-11-16T09:00:00.000Z',
        version: 3,
      }),
    ])
  })

  it('fixes the plan once submitted', async () => {
    signedInAs(agent)
    staffContext(agent)
    backend(aChange({ status: 'APPROVAL_PENDING' }))
    renderApp('/tickets/change-1')

    expect(await screen.findByText('Upload the licence and restart')).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Edit plan' })).not.toBeInTheDocument()
  })

  it('records how the change went during its review', async () => {
    signedInAs(agent)
    staffContext(agent)
    const state = backend(aChange({ status: 'REVIEW' }, { implementedAt: '2026-11-16T07:05:00Z' }))
    const { user } = renderApp('/tickets/change-1')

    await user.click(await screen.findByRole('button', { name: 'Save outcome' }))
    expect(await screen.findByText('Choose how it went')).toBeVisible()

    await user.selectOptions(screen.getByLabelText('How did it go?'), 'BACKED_OUT')
    await user.type(screen.getByLabelText('What happened'), 'The new licence was rejected; rolled back.')
    await user.click(screen.getByRole('button', { name: 'Save outcome' }))

    expect(await screen.findByText('Backed out')).toBeVisible()
    expect(state.sent).toEqual([{ outcome: 'BACKED_OUT', notes: 'The new licence was rejected; rolled back.', version: 3 }])
  })

  it('raises a change to fix a problem, with its window', async () => {
    signedInAs(agent)
    staffContext(agent)
    let sent: Record<string, unknown> | undefined
    server.use(
      http.get('/api/tickets/:id', () =>
        HttpResponse.json(aTicket({ id: 'problem-1', reference: 'PRB-000001', type: 'PROBLEM', title: 'VPN refuses connections', incident: null })),
      ),
      http.get('/api/categories', ({ request }) => {
        expect(new URL(request.url).searchParams.get('type')).toBe('CHANGE')
        return HttpResponse.json(incidentCategories)
      }),
      http.post('/api/changes', async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ id: 'change-1', reference: 'CHG-000001', type: 'CHANGE', status: 'NEW', priority: 'P3' }, { status: 201 })
      }),
    )
    const { user, router } = renderApp('/changes/new?problem=problem-1')

    expect(await screen.findByText(/The permanent fix for/)).toHaveTextContent('PRB-000001')
    await user.type(screen.getByLabelText('Title'), 'Raise the VPN licence')
    await user.type(screen.getByLabelText('What will change, and why?'), 'More sessions')
    await user.selectOptions(await screen.findByLabelText('Category'), 'Network')
    await user.type(screen.getByLabelText('Starts'), '2026-11-16T07:00')
    await user.click(screen.getByRole('button', { name: 'Raise change' }))
    expect(await screen.findByText('Give both ends of the window')).toBeVisible()

    await user.type(screen.getByLabelText('Ends'), '2026-11-16T09:00')
    await user.click(screen.getByRole('button', { name: 'Raise change' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/tickets/change-1'))
    expect(sent).toEqual({
      title: 'Raise the VPN licence',
      description: 'More sessions',
      categoryId: 'cat-network',
      impact: 'MEDIUM',
      urgency: 'MEDIUM',
      changeType: 'NORMAL',
      risk: 'MEDIUM',
      plannedStart: new Date('2026-11-16T07:00').toISOString(),
      plannedEnd: new Date('2026-11-16T09:00').toISOString(),
      problemId: 'problem-1',
    })
  })

  it('lists a problem’s changes with a way to raise one', async () => {
    signedInAs(agent)
    staffContext(agent)
    server.use(
      http.get('/api/tickets/:id', () =>
        HttpResponse.json(
          aTicket({
            id: 'problem-1',
            reference: 'PRB-000001',
            type: 'PROBLEM',
            status: 'KNOWN_ERROR',
            incident: null,
            problem: {
              rootCause: 'Licence full',
              workaround: 'Use vpn2',
              knownErrorAt: '2026-11-12T09:00:00Z',
              fixNotes: null,
              reopenCount: 0,
              incidents: [],
              changes: [{ id: 'change-1', reference: 'CHG-000001', title: 'Raise the VPN licence', status: 'APPROVAL_PENDING' }],
            },
          }),
        ),
      ),
      http.get('/api/tickets/:id/comments', () => HttpResponse.json([])),
      http.get('/api/tickets/:id/transitions', () => HttpResponse.json([])),
      http.get('/api/teams/:id/members', () => HttpResponse.json([])),
    )
    renderApp('/tickets/problem-1')

    expect(await screen.findByRole('link', { name: 'Raise the VPN licence' })).toHaveAttribute('href', '/tickets/change-1')
    expect(screen.getByRole('link', { name: 'Raise a change to fix this' })).toHaveAttribute('href', '/changes/new?problem=problem-1')
  })
})

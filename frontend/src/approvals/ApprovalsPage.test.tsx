import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { Approval, PendingApproval } from '@/api/types'
import type { AuthenticatedUser } from '@/auth/roles'
import { problem } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'
import { server } from '@/test/server'
import { aComment, agent, aTicket, requester, signedInAs, staffContext } from '@/test/tickets'

const lead: AuthenticatedUser = { id: 'lead-1', email: 'leo@example.com', displayName: 'Leo Lead', role: 'TEAM_LEAD' }

const waiting: PendingApproval = {
  id: 'approval-1',
  version: 0,
  requestedAt: '2026-11-11T09:00:00Z',
  ticketId: 'ticket-7',
  reference: 'REQ-000007',
  title: 'Shared drive access',
  description: 'Which drive? SALES-Forecasts\nAccess level: Read and edit',
  priority: 'P3',
  requester: { id: 'sam', name: 'Sam Sales' },
  team: { id: 'team-identity', name: 'Identity & Access' },
}

function anApproval(overrides: Partial<Approval> = {}): Approval {
  return {
    id: 'approval-1',
    status: 'PENDING',
    requestedAt: '2026-11-11T09:00:00Z',
    decidedBy: null,
    decidedAt: null,
    comment: null,
    canDecide: false,
    version: 0,
    ...overrides,
  }
}

const catalogueRequest = aTicket({
  id: 'ticket-7',
  reference: 'REQ-000007',
  type: 'SERVICE_REQUEST',
  title: 'Shared drive access',
  status: 'APPROVAL_PENDING',
  incident: null,
  serviceRequest: { catalogueItemId: 'item-drive', answers: [], fulfilmentNotes: null },
})

describe('approvals', () => {
  it('lists what the team lead must decide and approves with an optional comment', async () => {
    signedInAs(lead)
    staffContext(lead)
    let inbox = [waiting]
    let sent: unknown
    server.use(
      http.get('/api/approvals', () => HttpResponse.json(inbox)),
      http.post('/api/approvals/:id/decision', async ({ request }) => {
        sent = await request.json()
        inbox = []
        return HttpResponse.json(anApproval({ status: 'APPROVED', version: 1 }))
      }),
    )
    const { user } = renderApp('/approvals')

    const card = (await screen.findByRole('heading', { name: 'Shared drive access' })).closest('li') as HTMLElement
    expect(within(card).getByText(/Sam Sales · Identity & Access/)).toBeVisible()
    expect(within(card).getByText(/Access level: Read and edit/)).toBeVisible()
    await user.click(within(card).getByRole('button', { name: 'Approve' }))

    expect(await screen.findByText('Nothing is waiting for your approval.')).toBeVisible()
    expect(sent).toEqual({ decision: 'APPROVE', version: 0 })
  })

  it('asks why before rejecting', async () => {
    signedInAs(lead)
    staffContext(lead)
    let sent: unknown
    server.use(
      http.get('/api/approvals', () => HttpResponse.json([waiting])),
      http.post('/api/approvals/:id/decision', async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json(anApproval({ status: 'REJECTED', version: 1 }))
      }),
    )
    const { user } = renderApp('/approvals')

    await user.click(await screen.findByRole('button', { name: 'Reject' }))
    expect(screen.getByText('Say why, so the requester knows what to do instead')).toBeVisible()
    expect(sent).toBeUndefined()

    await user.type(screen.getByLabelText('Comment for the requester'), 'Read access is enough')
    await user.click(screen.getByRole('button', { name: 'Reject' }))
    await screen.findByRole('heading', { name: 'Approvals' })
    expect(sent).toEqual({ decision: 'REJECT', comment: 'Read access is enough', version: 0 })
  })

  it('says so when someone else decided first', async () => {
    signedInAs(lead)
    staffContext(lead)
    server.use(
      http.get('/api/approvals', () => HttpResponse.json([waiting])),
      http.post('/api/approvals/:id/decision', () => problem(409, 'APPROVAL_ALREADY_DECIDED')),
    )
    const { user } = renderApp('/approvals')

    await user.click(await screen.findByRole('button', { name: 'Approve' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('perhaps someone else decided it')
  })

  it('is in the navigation for team leads, not agents', async () => {
    signedInAs(lead)
    staffContext(lead)
    server.use(http.get('/api/approvals', () => HttpResponse.json([])))
    renderApp('/approvals')
    expect(await screen.findByRole('link', { name: 'Approvals' })).toHaveAttribute('href', '/approvals')
  })

  it('keeps agents out of the inbox', async () => {
    signedInAs(agent)
    staffContext(agent)
    renderApp('/approvals')
    expect(await screen.findByRole('heading', { name: /permission|access/i })).toBeVisible()
    expect(screen.queryByRole('link', { name: 'Approvals' })).not.toBeInTheDocument()
  })

  it('shows the requester where their request stands and why it was rejected', async () => {
    signedInAs(requester)
    server.use(
      http.get('/api/tickets/:id', () => HttpResponse.json({ ...catalogueRequest, status: 'REJECTED' })),
      http.get('/api/tickets/:id/comments', () => HttpResponse.json([aComment()])),
      http.get('/api/tickets/:id/transitions', () => HttpResponse.json([])),
      http.get('/api/tickets/:id/approval', () =>
        HttpResponse.json(
          anApproval({
            status: 'REJECTED',
            decidedBy: { id: 'lead-1', name: 'Leo Lead' },
            decidedAt: '2026-11-11T10:00:00Z',
            comment: 'Read access is enough',
          }),
        ),
      ),
    )
    renderApp('/tickets/ticket-7')

    const panel = (await screen.findByRole('heading', { name: 'Approval' })).closest('[data-slot="card"]') as HTMLElement
    expect(within(panel).getByText('Rejected')).toBeVisible()
    expect(within(panel).getByText(/By Leo Lead/)).toBeVisible()
    expect(within(panel).getByText('“Read access is enough”')).toBeVisible()
    expect(within(panel).queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })

  it('lets the team lead decide from the ticket page', async () => {
    signedInAs(lead)
    staffContext(lead, ['team-identity'])
    server.use(
      http.get('/api/tickets/:id', () => HttpResponse.json(catalogueRequest)),
      http.get('/api/tickets/:id/comments', () => HttpResponse.json([])),
      http.get('/api/tickets/:id/transitions', () => HttpResponse.json([])),
      http.get('/api/tickets/:id/history', () => HttpResponse.json([])),
      http.get('/api/tickets/:id/sla', () => HttpResponse.json({ clocks: [] })),
      http.get('/api/tickets/:id/approval', () => HttpResponse.json(anApproval({ canDecide: true }))),
    )
    renderApp('/tickets/ticket-7')

    const panel = (await screen.findByRole('heading', { name: 'Approval' })).closest('[data-slot="card"]') as HTMLElement
    expect(within(panel).getByText('A team lead must approve this request before work starts.')).toBeVisible()
    expect(within(panel).getByRole('button', { name: 'Approve' })).toBeEnabled()
  })

  it('shows no approval panel on requests that need none', async () => {
    signedInAs(requester)
    server.use(
      http.get('/api/tickets/:id', () => HttpResponse.json({ ...catalogueRequest, status: 'SUBMITTED' })),
      http.get('/api/tickets/:id/comments', () => HttpResponse.json([])),
      http.get('/api/tickets/:id/transitions', () => HttpResponse.json([])),
      http.get('/api/tickets/:id/approval', () => problem(404, 'RESOURCE_NOT_FOUND')),
    )
    renderApp('/tickets/ticket-7')

    await screen.findByRole('heading', { name: 'Request details' })
    expect(screen.queryByRole('heading', { name: 'Approval' })).not.toBeInTheDocument()
  })
})

import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { AvailableTransition, Comment, Ticket } from '@/api/types'
import { problem } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'
import { server } from '@/test/server'
import { aComment, aTicket, requester, signedInAs } from '@/test/tickets'

/** A tiny in-memory backend for one ticket, so replies and transitions change what is returned. */
function ticketBackend(initial: Ticket, transitions: AvailableTransition[], comments: Comment[] = []) {
  const state = { ticket: initial, comments: [...comments], transitionRequests: [] as unknown[] }
  server.use(
    http.get('/api/tickets/:id', () => HttpResponse.json(state.ticket)),
    http.get('/api/tickets/:id/comments', () => HttpResponse.json(state.comments)),
    http.get('/api/tickets/:id/transitions', () => HttpResponse.json(state.ticket.status === 'CLOSED' ? [] : transitions)),
    http.post('/api/tickets/:id/comments', async ({ request }) => {
      const body = (await request.json()) as { body: string }
      const created = aComment({ id: `c-${state.comments.length + 1}`, author: { id: requester.id, name: requester.displayName }, body: body.body })
      state.comments.push(created)
      return HttpResponse.json(created, { status: 201 })
    }),
    http.post('/api/tickets/:id/transitions', async ({ request }) => {
      const body = (await request.json()) as { targetStatus: string; version: number }
      state.transitionRequests.push(body)
      if (body.version !== state.ticket.version) {
        return problem(409, 'CONCURRENT_MODIFICATION', 'Ticket was modified by someone else.')
      }
      state.ticket = { ...state.ticket, status: body.targetStatus, version: state.ticket.version + 1 }
      return HttpResponse.json(state.ticket)
    }),
  )
  return state
}

const confirmAndClose: AvailableTransition = { targetStatus: 'CLOSED', label: 'Confirm and close', requirements: [] }
const reopen: AvailableTransition = { targetStatus: 'IN_PROGRESS', label: 'Reopen', requirements: ['REASON'] }

describe('ticket page', () => {
  it('shows the ticket, its details and the public conversation', async () => {
    signedInAs(requester)
    ticketBackend(aTicket(), [], [aComment({ relatedStatus: 'WAITING_FOR_USER' })])

    renderApp('/tickets/ticket-1')

    expect(await screen.findByRole('heading', { name: 'Wi-Fi keeps dropping' })).toBeVisible()
    const facts = screen.getByRole('complementary', { name: 'Ticket details' })
    expect(within(facts).getByText('Network › Wi-Fi')).toBeVisible()
    expect(within(facts).getByText('Not yet assigned')).toBeVisible()
    expect(await screen.findByText('Which floor are you on?')).toBeVisible()
    expect(screen.getByText('Set the status to waiting for user')).toBeVisible()
  })

  it('posts a reply and shows it in the conversation', async () => {
    signedInAs(requester)
    const backend = ticketBackend(aTicket(), [])
    const { user } = renderApp('/tickets/ticket-1')

    await user.type(await screen.findByLabelText('Add a message'), 'Floor 3, near the kitchen')
    await user.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText('Floor 3, near the kitchen')).toBeVisible()
    expect(screen.getByLabelText('Add a message')).toHaveValue('')
    expect(backend.comments).toHaveLength(1)
  })

  it('offers exactly the moves the server allows and performs them with the current version', async () => {
    signedInAs(requester)
    const backend = ticketBackend(aTicket({ status: 'RESOLVED', version: 4 }), [confirmAndClose, reopen])
    const { user } = renderApp('/tickets/ticket-1')

    await user.click(await screen.findByRole('button', { name: 'Confirm and close' }))

    expect(await screen.findByText('This ticket is closed, so it no longer accepts messages.')).toBeVisible()
    expect(backend.transitionRequests).toEqual([{ targetStatus: 'CLOSED', version: 4 }])
    expect(screen.queryByRole('button', { name: 'Reopen' })).not.toBeInTheDocument()
  })

  it('asks for a reason before reopening', async () => {
    signedInAs(requester)
    const backend = ticketBackend(aTicket({ status: 'RESOLVED', version: 2 }), [confirmAndClose, reopen])
    const { user } = renderApp('/tickets/ticket-1')

    await user.click(await screen.findByRole('button', { name: 'Reopen' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reopen' })
    expect(within(dialog).getByRole('button', { name: 'Close' })).toBeVisible() // icon button has an accessible name
    await user.click(within(dialog).getByRole('button', { name: 'Reopen' }))
    expect(await within(dialog).findByText('Please give a reason')).toBeVisible()

    await user.type(within(dialog).getByLabelText('Reason'), 'Dropped again this morning')
    await user.click(within(dialog).getByRole('button', { name: 'Reopen' }))

    await screen.findByText('In progress')
    expect(backend.transitionRequests).toEqual([{ targetStatus: 'IN_PROGRESS', version: 2, reason: 'Dropped again this morning' }])
  })

  it('does not offer support-only moves that need inputs this screen doesn’t collect', async () => {
    signedInAs(requester)
    ticketBackend(aTicket({ status: 'IN_PROGRESS' }), [{ targetStatus: 'RESOLVED', label: 'Resolve', requirements: ['RESOLUTION'] }])

    renderApp('/tickets/ticket-1')

    await screen.findByRole('heading', { name: 'Wi-Fi keeps dropping' })
    expect(screen.queryByRole('button', { name: 'Resolve' })).not.toBeInTheDocument()
  })

  it('explains a concurrent change and reloads instead of overwriting it', async () => {
    signedInAs(requester)
    const backend = ticketBackend(aTicket({ status: 'RESOLVED', version: 1 }), [confirmAndClose])
    const { user } = renderApp('/tickets/ticket-1')
    await screen.findByRole('button', { name: 'Confirm and close' })
    backend.ticket = { ...backend.ticket, version: 2, title: 'Wi-Fi keeps dropping (updated by support)' } // someone else edits

    await user.click(screen.getByRole('button', { name: 'Confirm and close' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Someone else updated this ticket')
    expect(await screen.findByRole('heading', { name: 'Wi-Fi keeps dropping (updated by support)' })).toBeVisible()
  })

  it('treats an invisible ticket like a missing one', async () => {
    signedInAs(requester)
    server.use(
      http.get('/api/tickets/:id', () => problem(404, 'RESOURCE_NOT_FOUND')),
      http.get('/api/tickets/:id/transitions', () => problem(404, 'RESOURCE_NOT_FOUND')),
    )

    renderApp('/tickets/someone-elses')

    expect(await screen.findByRole('heading', { name: 'Ticket not found' })).toBeVisible()
  })
})

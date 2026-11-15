import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { Notification } from '@/api/types'
import { problem } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'
import { server } from '@/test/server'
import { requester, signedInAs } from '@/test/tickets'

function aNotification(overrides: Partial<Notification> = {}): Notification {
  return {
    id: 'n-1',
    ticketId: 'ticket-1',
    kind: 'COMMENTED',
    message: 'Nina Network replied on INC-000001: Wi-Fi keeps dropping',
    createdAt: '2026-11-15T09:00:00Z',
    read: false,
    ...overrides,
  }
}

/** The caller's inbox, with read marks that change what is returned. */
function inboxBackend(items: Notification[]) {
  const state = { items: [...items], readRequests: [] as string[] }
  const unread = () => state.items.filter((item) => !item.read).length
  server.use(
    http.get('/api/notifications', () => HttpResponse.json({ unreadCount: unread(), items: state.items })),
    http.get('/api/notifications/unread-count', () => HttpResponse.json({ count: unread() })),
    http.post('/api/notifications/:id/read', ({ params }) => {
      state.readRequests.push(String(params.id))
      state.items = state.items.map((item) => (item.id === params.id ? { ...item, read: true } : item))
      return new HttpResponse(null, { status: 204 })
    }),
    http.post('/api/notifications/read-all', () => {
      state.items = state.items.map((item) => ({ ...item, read: true }))
      return new HttpResponse(null, { status: 204 })
    }),
    // The ticket a notification opens (not found is enough to prove the navigation).
    http.get('/api/tickets/:id', () => problem(404, 'RESOURCE_NOT_FOUND')),
    http.get('/api/tickets/:id/transitions', () => HttpResponse.json([])),
  )
  return state
}

describe('notifications', () => {
  it('shows the unread count on the bell', async () => {
    signedInAs(requester)
    inboxBackend([aNotification(), aNotification({ id: 'n-2' }), aNotification({ id: 'n-3', read: true })])
    renderApp('/')

    expect(await screen.findByRole('link', { name: 'Notifications, 2 unread' })).toBeVisible()
  })

  it('lists notifications and marks all as read', async () => {
    signedInAs(requester)
    inboxBackend([aNotification(), aNotification({ id: 'n-2', message: 'Nina Network moved INC-000001 to resolved: Wi-Fi', read: true })])
    const { user } = renderApp('/notifications')

    const list = await screen.findByRole('list', { name: 'Notifications' })
    expect(within(list).getAllByRole('button')).toHaveLength(2)
    expect(within(list).getByText('Unread:', { exact: false })).toBeInTheDocument()
    expect(screen.getByText('1 unread')).toBeVisible()

    await user.click(screen.getByRole('button', { name: 'Mark all as read' }))

    expect(await screen.findByText('All caught up.')).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Mark all as read' })).not.toBeInTheDocument()
  })

  it('opening a notification marks it read and goes to its ticket', async () => {
    signedInAs(requester)
    const backend = inboxBackend([aNotification({ id: 'n-7', ticketId: 'ticket-9' })])
    const { user } = renderApp('/notifications')

    await user.click(await screen.findByRole('button', { name: /Nina Network replied/ }))

    expect(await screen.findByRole('heading', { name: 'Ticket not found' })).toBeVisible()
    expect(backend.readRequests).toEqual(['n-7'])
  })

  it('says so when there is nothing yet', async () => {
    signedInAs(requester)
    inboxBackend([])
    renderApp('/notifications')

    expect(await screen.findByText(/Nothing yet/)).toBeVisible()
    expect(screen.getByRole('link', { name: 'Notifications' })).toBeVisible()
  })
})

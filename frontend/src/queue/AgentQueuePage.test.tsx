import { screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { renderApp } from '@/test/renderApp'
import { server } from '@/test/server'
import { aSummary, agent, requester, signedInAs } from '@/test/tickets'

function queueHandler() {
  const requests: URLSearchParams[] = []
  server.use(
    http.get('/api/tickets', ({ request }) => {
      const params = new URL(request.url).searchParams
      requests.push(params)
      return HttpResponse.json({ content: [aSummary({ priority: 'P1', requester: { id: 'r', name: 'Rita Requester' } })], page: 0, size: 25, totalElements: 1, totalPages: 1 })
    }),
  )
  return requests
}

describe('agent queue', () => {
  it('opens on my open tickets, most urgent and oldest first', async () => {
    signedInAs(agent)
    const requests = queueHandler()

    renderApp('/queue')

    expect(await screen.findByRole('link', { name: 'Wi-Fi keeps dropping' })).toBeVisible()
    expect(within(screen.getByRole('table')).getByText('P1 · Critical')).toBeVisible()
    expect(requests[0]?.get('view')).toBe('MINE')
    expect(requests[0]?.get('open')).toBe('true')
    expect(requests[0]?.getAll('sort')).toEqual(['priority,asc', 'createdAt,asc'])
  })

  it('switches queue tabs through the URL', async () => {
    signedInAs(agent)
    const requests = queueHandler()
    const { user, router } = renderApp('/queue')

    await user.click(await screen.findByRole('button', { name: 'Unassigned' }))

    await screen.findByRole('button', { name: 'Unassigned', pressed: true })
    expect(router.state.location.search).toBe('?view=unassigned')
    expect(requests.at(-1)?.get('view')).toBe('UNASSIGNED')
  })

  it('searches on submit and then orders by relevance', async () => {
    signedInAs(agent)
    const requests = queueHandler()
    const { user, router } = renderApp('/queue?view=all')

    await user.type(await screen.findByLabelText('Search'), 'printer -office')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    await screen.findByRole('option', { name: 'Best match' })
    expect(router.state.location.search).toBe('?view=all&q=printer+-office')
    expect(requests.at(-1)?.get('q')).toBe('printer -office')
    expect(requests.at(-1)?.has('sort')).toBe(false) // relevance ranking from the server
  })

  it('filters by priority and type', async () => {
    signedInAs(agent)
    const requests = queueHandler()
    const { user } = renderApp('/queue?view=team')

    await user.selectOptions(await screen.findByLabelText('Priority'), 'P1 · Critical')
    await user.selectOptions(screen.getByLabelText('Type'), 'Service request')

    await screen.findByRole('link', { name: 'Wi-Fi keeps dropping' })
    expect(requests.at(-1)?.get('priority')).toBe('P1')
    expect(requests.at(-1)?.get('type')).toBe('SERVICE_REQUEST')
  })

  it('shows each ticket’s open SLA target and can sort by the next deadline', async () => {
    signedInAs(agent)
    const requests: URLSearchParams[] = []
    const dueAt = new Date(Date.now() + 20 * 60_000).toISOString()
    server.use(
      http.get('/api/tickets', ({ request }) => {
        requests.push(new URL(request.url).searchParams)
        return HttpResponse.json({
          content: [aSummary({ sla: { metric: 'RESPONSE', status: 'AT_RISK', dueAt } })],
          page: 0,
          size: 25,
          totalElements: 1,
          totalPages: 1,
        })
      }),
    )
    const { user } = renderApp('/queue?view=team')

    const table = await screen.findByRole('table')
    expect(await within(table).findByText('At risk')).toBeVisible()
    expect(within(table).getByText(/Response due in 20 minutes/)).toBeVisible()

    await user.selectOptions(screen.getByLabelText('Sort'), 'SLA due soonest')

    await waitFor(() => expect(requests.at(-1)?.getAll('sort')).toEqual(['slaDueAt,asc', 'priority,asc']))
  })

  it('is not offered to requesters', async () => {
    signedInAs(requester)

    renderApp('/queue')

    expect(await screen.findByRole('heading', { name: 'You don’t have access to this page' })).toBeVisible()
    expect(screen.queryByRole('link', { name: 'Queue' })).not.toBeInTheDocument()
  })
})

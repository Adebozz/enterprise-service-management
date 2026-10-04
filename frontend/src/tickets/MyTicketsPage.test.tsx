import { screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { renderApp } from '@/test/renderApp'
import { server } from '@/test/server'
import { aSummary, requester, signedInAs } from '@/test/tickets'

/** Records the query string of each list request, and answers with a page of tickets. */
function listHandler(totalPages = 1, content = [aSummary()]) {
  const requests: URLSearchParams[] = []
  server.use(
    http.get('/api/tickets', ({ request }) => {
      const params = new URL(request.url).searchParams
      requests.push(params)
      return HttpResponse.json({
        content,
        page: Number(params.get('page') ?? 0),
        size: 20,
        totalElements: content.length * totalPages,
        totalPages,
      })
    }),
  )
  return requests
}

describe('my tickets', () => {
  it('lists the open tickets the user raised, linking to each', async () => {
    signedInAs(requester)
    const requests = listHandler()

    renderApp('/tickets')

    const link = await screen.findByRole('link', { name: 'Wi-Fi keeps dropping' })
    expect(link).toHaveAttribute('href', '/tickets/ticket-1')
    expect(screen.getByText('P4 · Low')).toBeVisible()
    expect(requests[0]?.get('view')).toBe('REQUESTED')
    expect(requests[0]?.get('open')).toBe('true')
  })

  it('switching to All drops the open filter and is kept in the URL', async () => {
    signedInAs(requester)
    const requests = listHandler()
    const { user, router } = renderApp('/tickets')

    await user.click(await screen.findByRole('button', { name: 'All' }))

    await screen.findByRole('button', { name: 'All', pressed: true })
    expect(router.state.location.search).toBe('?show=all')
    expect(requests.at(-1)?.has('open')).toBe(false)
  })

  it('pages through results using the URL', async () => {
    signedInAs(requester)
    const requests = listHandler(3)
    const { user, router } = renderApp('/tickets')

    await user.click(await screen.findByRole('button', { name: 'Next' }))

    expect(await screen.findByText('Page 2 of 3')).toBeVisible()
    expect(router.state.location.search).toBe('?page=2')
    expect(requests.at(-1)?.get('page')).toBe('1') // API pages are zero-based
  })

  it('explains an empty list', async () => {
    signedInAs(requester)
    listHandler(0, [])

    renderApp('/tickets')

    expect(await screen.findByText('You have no open tickets.')).toBeVisible()
  })
})

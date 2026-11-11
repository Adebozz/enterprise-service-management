import { screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { ApiProblem } from '@/api/errors'
import { aCatalogueItem } from '@/test/catalogue'
import { renderApp } from '@/test/renderApp'
import { server } from '@/test/server'
import { aComment, aTicket, requester, signedInAs } from '@/test/tickets'

function withCatalogue() {
  server.use(
    http.get('/api/catalogue', () => HttpResponse.json([aCatalogueItem()])),
    http.get('/api/catalogue/:id', () => HttpResponse.json(aCatalogueItem())),
  )
}

describe('the service catalogue', () => {
  it('lists what can be ordered, with a way to ask for anything else', async () => {
    signedInAs(requester)
    withCatalogue()
    const { user, router } = renderApp('/')

    await user.click(await screen.findByRole('link', { name: /Request something/ }))

    expect(await screen.findByRole('link', { name: /New laptop/ })).toHaveAttribute('href', '/catalogue/item-laptop')
    expect(screen.getByRole('link', { name: /Something else/ })).toHaveAttribute('href', '/tickets/new/request')
    expect(router.state.location.pathname).toBe('/catalogue')
  })

  it('builds the form from the item and checks required answers first', async () => {
    signedInAs(requester)
    withCatalogue()
    const { user } = renderApp('/catalogue/item-laptop')

    expect(await screen.findByRole('heading', { name: 'New laptop' })).toBeVisible()
    expect(screen.getByLabelText('Needed by (optional)')).toHaveAttribute('type', 'date')
    expect(screen.getByLabelText('Why do you need it?')).toHaveAccessibleDescription('A sentence is enough.')

    await user.click(screen.getByRole('button', { name: 'Submit request' }))

    expect(await screen.findAllByText('This question needs an answer')).toHaveLength(2)
    expect(screen.getByLabelText('Which model?')).toHaveAttribute('aria-invalid', 'true')
  })

  it('orders the item and opens the new request', async () => {
    signedInAs(requester)
    withCatalogue()
    let submitted: unknown
    server.use(
      http.post('/api/catalogue/:id/requests', async ({ request }) => {
        submitted = await request.json()
        return HttpResponse.json({ id: 'ticket-9', reference: 'REQ-000009', type: 'SERVICE_REQUEST', status: 'SUBMITTED', priority: 'P4' }, { status: 201 })
      }),
      http.get('/api/tickets/:id', () =>
        HttpResponse.json(
          aTicket({
            id: 'ticket-9',
            type: 'SERVICE_REQUEST',
            title: 'New laptop',
            description: 'Which model?: Developer',
            status: 'SUBMITTED',
            incident: null,
            serviceRequest: { catalogueItemId: 'item-laptop', answers: [{ key: 'model', label: 'Which model?', value: 'Developer' }], fulfilmentNotes: null },
          }),
        ),
      ),
      http.get('/api/tickets/:id/comments', () => HttpResponse.json([aComment()])),
      http.get('/api/tickets/:id/transitions', () => HttpResponse.json([])),
    )
    const { user, router } = renderApp('/catalogue/item-laptop')

    await user.selectOptions(await screen.findByLabelText('Which model?'), 'Developer')
    await user.type(screen.getByLabelText('Why do you need it?'), 'Builds take too long')
    await user.type(screen.getByLabelText('Anything else? (optional)'), 'Desk 4')
    await user.click(screen.getByRole('button', { name: 'Submit request' }))

    expect(await screen.findByRole('heading', { name: 'Request details' })).toBeVisible()
    expect(router.state.location.pathname).toBe('/tickets/ticket-9')
    // Unanswered optional questions are not sent at all.
    expect(submitted).toEqual({ answers: { model: 'Developer', reason: 'Builds take too long' }, notes: 'Desk 4', urgency: 'MEDIUM' })
  })

  it('shows the server’s verdict on an answer next to its question', async () => {
    signedInAs(requester)
    withCatalogue()
    server.use(
      http.post('/api/catalogue/:id/requests', () => {
        const body: ApiProblem = {
          type: 'about:blank',
          title: 'Unprocessable Content',
          status: 422,
          detail: 'Some answers need attention',
          instance: '/api/catalogue/item-laptop/requests',
          code: 'CATALOGUE_ANSWERS_INVALID',
          timestamp: '2026-11-11T09:00:00Z',
          correlationId: 'c-1',
          fieldErrors: [{ field: 'answers.model', message: 'Choose one of the options' }],
        }
        return HttpResponse.json(body, { status: 422, headers: { 'Content-Type': 'application/problem+json' } })
      }),
    )
    const { user } = renderApp('/catalogue/item-laptop')

    await user.selectOptions(await screen.findByLabelText('Which model?'), 'Standard')
    await user.type(screen.getByLabelText('Why do you need it?'), 'New starter')
    await user.click(screen.getByRole('button', { name: 'Submit request' }))

    expect(await screen.findByText('Choose one of the options')).toBeVisible()
    expect(screen.getByLabelText('Which model?')).toHaveAccessibleDescription('Choose one of the options')
  })
})

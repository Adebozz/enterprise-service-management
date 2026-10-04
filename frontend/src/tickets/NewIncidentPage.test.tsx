import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { problem } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'
import { server } from '@/test/server'
import { aComment, aTicket, incidentCategories, requester, signedInAs } from '@/test/tickets'

function withCategories() {
  server.use(
    http.get('/api/categories', ({ request }) =>
      new URL(request.url).searchParams.get('type') === 'INCIDENT' ? HttpResponse.json(incidentCategories) : HttpResponse.json([]),
    ),
  )
}

describe('report a problem', () => {
  it('validates required fields before submitting', async () => {
    signedInAs(requester)
    withCategories()
    const { user } = renderApp('/tickets/new/incident')

    await user.click(await screen.findByRole('button', { name: 'Submit' }))

    expect(await screen.findByText('Give the problem a short title')).toBeVisible()
    expect(screen.getByText('Describe what is happening')).toBeVisible()
    expect(screen.getByText('Choose a category')).toBeVisible()
  })

  it('offers only the subcategories of the chosen category', async () => {
    signedInAs(requester)
    withCategories()
    const { user } = renderApp('/tickets/new/incident')
    const category = await screen.findByLabelText('Category')
    await screen.findByRole('option', { name: 'Network' })

    await user.selectOptions(category, 'Network')
    const subcategory = screen.getByLabelText('Subcategory (optional)')
    expect(within(subcategory).getAllByRole('option').map((o) => o.textContent)).toEqual(['Not sure / other', 'Wi-Fi', 'VPN'])

    await user.selectOptions(subcategory, 'VPN')
    await user.selectOptions(category, 'Hardware')
    expect(subcategory).toHaveValue('') // the VPN choice no longer applies
    expect(within(subcategory).getAllByRole('option').map((o) => o.textContent)).toEqual(['Not sure / other', 'Laptop'])
  })

  it('submits the incident and opens the new ticket', async () => {
    signedInAs(requester)
    withCategories()
    let submitted: unknown
    server.use(
      http.post('/api/incidents', async ({ request }) => {
        submitted = await request.json()
        return HttpResponse.json({ id: 'ticket-1', reference: 'INC-000042', type: 'INCIDENT', status: 'NEW', priority: 'P2' }, { status: 201 })
      }),
      http.get('/api/tickets/:id', () => HttpResponse.json(aTicket())),
      http.get('/api/tickets/:id/comments', () => HttpResponse.json([aComment()])),
      http.get('/api/tickets/:id/transitions', () => HttpResponse.json([])),
    )
    const { user, router } = renderApp('/tickets/new/incident')

    await user.type(await screen.findByLabelText('Title'), 'Wi-Fi keeps dropping')
    await user.type(screen.getByLabelText('What’s happening?'), 'Every few minutes')
    await screen.findByRole('option', { name: 'Network' })
    await user.selectOptions(screen.getByLabelText('Category'), 'Network')
    await user.selectOptions(screen.getByLabelText('Subcategory (optional)'), 'Wi-Fi')
    await user.selectOptions(screen.getByLabelText('Who is affected?'), 'My team or department')
    await user.selectOptions(screen.getByLabelText('How urgent is it?'), 'High')
    await user.click(screen.getByRole('button', { name: 'Submit' }))

    expect(await screen.findByRole('heading', { name: 'Wi-Fi keeps dropping' })).toBeVisible()
    expect(router.state.location.pathname).toBe('/tickets/ticket-1')
    expect(submitted).toEqual({
      title: 'Wi-Fi keeps dropping',
      description: 'Every few minutes',
      categoryId: 'cat-network',
      subcategoryId: 'cat-wifi',
      impact: 'MEDIUM',
      urgency: 'HIGH',
    })
  })

  it('shows the server’s category rejection next to the category field', async () => {
    signedInAs(requester)
    withCategories()
    server.use(http.post('/api/incidents', () => problem(422, 'INVALID_CATEGORY', 'Category is not available for INCIDENT')))
    const { user } = renderApp('/tickets/new/incident')

    await user.type(await screen.findByLabelText('Title'), 'Printer')
    await user.type(screen.getByLabelText('What’s happening?'), 'Jammed')
    await screen.findByRole('option', { name: 'Hardware' })
    await user.selectOptions(screen.getByLabelText('Category'), 'Hardware')
    await user.click(screen.getByRole('button', { name: 'Submit' }))

    expect(await screen.findByText('Category is not available for INCIDENT')).toBeVisible()
    expect(screen.getByLabelText('Category')).toHaveAttribute('aria-invalid', 'true')
  })
})

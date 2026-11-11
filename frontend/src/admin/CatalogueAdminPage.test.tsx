import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { AuthenticatedUser } from '@/auth/roles'
import { aCatalogueItem } from '@/test/catalogue'
import { renderApp } from '@/test/renderApp'
import { server } from '@/test/server'
import { signedInAs } from '@/test/tickets'

const admin: AuthenticatedUser = { id: 'admin-1', email: 'ada@example.com', displayName: 'Ada Admin', role: 'ADMIN' }

const requestCategories = [
  {
    id: 'cat-hardware',
    code: 'HARDWARE',
    name: 'Hardware',
    appliesTo: 'ANY',
    active: true,
    defaultTeamId: 'team-hw',
    parentId: null,
    subcategories: [{ id: 'cat-laptop', code: 'LAPTOP', name: 'Laptop', appliesTo: 'ANY', active: true, defaultTeamId: null, parentId: 'cat-hardware', subcategories: [] }],
  },
]

function withAdminCatalogue(items = [aCatalogueItem(), aCatalogueItem({ id: 'item-old', name: 'Fax machine', active: false, fields: [] })]) {
  server.use(
    http.get('/api/admin/catalogue', () => HttpResponse.json(items)),
    http.get('/api/catalogue/:id', ({ params }) => HttpResponse.json(items.find((i) => i.id === params.id))),
    http.get('/api/categories', () => HttpResponse.json(requestCategories)),
  )
}

describe('catalogue administration', () => {
  it('lists every item, withdrawn ones included', async () => {
    signedInAs(admin)
    withAdminCatalogue()
    renderApp('/admin/catalogue')

    const laptop = await screen.findByRole('row', { name: /New laptop/ })
    expect(within(laptop).getByRole('link', { name: 'New laptop' })).toHaveAttribute('href', '/admin/catalogue/item-laptop')
    expect(within(laptop).getByText('Available')).toBeVisible()
    expect(within(screen.getByRole('row', { name: /Fax machine/ })).getByText('Withdrawn')).toBeVisible()
    expect(screen.getByRole('link', { name: 'Catalogue' })).toBeVisible()
  })

  it('creates an item with questions, making keys from the labels', async () => {
    signedInAs(admin)
    withAdminCatalogue()
    let created: unknown
    server.use(
      http.post('/api/admin/catalogue', async ({ request }) => {
        created = await request.json()
        return HttpResponse.json(aCatalogueItem({ id: 'item-new' }), { status: 201 })
      }),
    )
    const { user, router } = renderApp('/admin/catalogue/new')

    await user.type(await screen.findByLabelText('Name'), 'Monitor')
    await user.type(screen.getByLabelText('Summary'), 'A second screen')
    await screen.findByRole('option', { name: 'Hardware' })
    await user.selectOptions(screen.getByLabelText('Category'), 'Hardware')
    await user.click(screen.getByRole('button', { name: 'Add question' }))
    await user.type(screen.getByRole('textbox', { name: 'Question 1' }), 'Screen size')
    await user.selectOptions(screen.getByLabelText('Answer type'), 'Choice from a list')
    await user.type(screen.getByLabelText('Options, one per line'), '24 inch{Enter}27 inch')
    await user.click(screen.getByLabelText('Required'))
    await user.click(screen.getByRole('button', { name: 'Add item' }))

    await screen.findByRole('heading', { name: 'Service catalogue' })
    expect(router.state.location.pathname).toBe('/admin/catalogue')
    expect(created).toEqual({
      name: 'Monitor',
      summary: 'A second screen',
      categoryId: 'cat-hardware',
      fields: [{ key: 'screenSize', label: 'Screen size', type: 'SELECT', required: true, options: ['24 inch', '27 inch'], helpText: null }],
    })
  })

  it('edits an item without losing its subcategory, and can withdraw it', async () => {
    signedInAs(admin)
    withAdminCatalogue()
    let updated: Record<string, unknown> | undefined
    server.use(
      http.put('/api/admin/catalogue/:id', async ({ request }) => {
        updated = (await request.json()) as Record<string, unknown>
        return HttpResponse.json(aCatalogueItem({ active: false, version: 1 }))
      }),
    )
    const { user } = renderApp('/admin/catalogue/item-laptop')

    expect(await screen.findByRole('heading', { name: 'Edit New laptop' })).toBeVisible()
    await screen.findByRole('option', { name: 'Laptop' })
    expect(screen.getByLabelText('Subcategory (optional)')).toHaveValue('cat-laptop')
    await user.click(screen.getByLabelText('Available to order'))
    await user.click(screen.getByRole('button', { name: 'Remove question 2' }))
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    await screen.findByRole('heading', { name: 'Service catalogue' })
    expect(updated).toMatchObject({ subcategoryId: 'cat-laptop', active: false, version: 0 })
    expect((updated?.fields as { key: string }[] | undefined)?.map((f) => f.key)).toEqual(['model', 'reason'])
  })

  it('needs at least two options for a choice before saving', async () => {
    signedInAs(admin)
    withAdminCatalogue()
    const { user } = renderApp('/admin/catalogue/new')

    await user.click(await screen.findByRole('button', { name: 'Add question' }))
    await user.type(screen.getByRole('textbox', { name: 'Question 1' }), 'Colour')
    await user.selectOptions(screen.getByLabelText('Answer type'), 'Choice from a list')
    await user.type(screen.getByLabelText('Options, one per line'), 'Black')
    await user.click(screen.getByRole('button', { name: 'Add item' }))

    expect(await screen.findByText('Give at least two options')).toBeVisible()
    expect(screen.getByText('Give the item a name')).toBeVisible()
  })
})

import { screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { Category, Team, TeamMember, User } from '@/api/types'
import type { AuthenticatedUser } from '@/auth/roles'
import { problem } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'
import { server } from '@/test/server'
import { agent, signedInAs, staffContext } from '@/test/tickets'

const admin: AuthenticatedUser = { id: 'u-admin', email: 'ann@example.com', displayName: 'Ann Admin', role: 'ADMIN' }
const at = '2026-10-01T00:00:00Z'

function aUser(overrides: Partial<User>): User {
  return {
    id: 'u-1',
    email: 'nina@example.com',
    displayName: 'Nina Network',
    role: 'AGENT',
    active: true,
    createdAt: at,
    updatedAt: at,
    version: 3,
    ...overrides,
  }
}

function aTeam(overrides: Partial<Team>): Team {
  return { id: 'team-network', name: 'Network Team', description: null, active: true, createdAt: at, updatedAt: at, version: 0, ...overrides }
}

function aCategory(overrides: Partial<Category>): Category {
  return {
    id: 'c-net',
    code: 'NETWORK',
    name: 'Network',
    parentId: null,
    defaultTeamId: 'team-network',
    appliesTo: 'INCIDENT',
    active: true,
    version: 0,
    subcategories: [],
    ...overrides,
  }
}

function signedInAdmin() {
  signedInAs(admin)
  staffContext(admin, [])
}

describe('administration', () => {
  it('groups the admin areas behind one navigation link, for admins only', async () => {
    signedInAdmin()
    const { user } = renderApp('/')

    await user.click(await screen.findByRole('link', { name: 'Admin' }))

    const areas = await screen.findByRole('navigation', { name: 'Administration' })
    expect(
      within(areas)
        .getAllByRole('link')
        .map((link) => link.textContent),
    ).toEqual(expect.arrayContaining([expect.stringContaining('People'), expect.stringContaining('Teams'), expect.stringContaining('Categories')]))
  })

  it('refuses the admin pages to agents', async () => {
    signedInAs(agent)
    staffContext(agent)
    renderApp('/admin/people')

    expect(await screen.findByRole('heading', { name: 'You don’t have access to this page' })).toBeVisible()
    expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument()
  })
})

describe('people', () => {
  function peopleBackend(initial: User[]) {
    const state = { users: [...initial], searches: [] as string[], patches: [] as unknown[], created: [] as unknown[] }
    server.use(
      http.get('/api/admin/users', ({ request }) => {
        const url = new URL(request.url)
        state.searches.push(url.search)
        const role = url.searchParams.get('role')
        const content = state.users.filter((user) => !role || user.role === role)
        return HttpResponse.json({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 })
      }),
      http.patch('/api/admin/users/:id', async ({ params, request }) => {
        const body = (await request.json()) as { role?: string; active?: boolean; version: number }
        state.patches.push(body)
        if (params.id === 'u-admin' && body.role !== 'ADMIN') {
          return problem(409, 'LAST_ADMIN_REQUIRED', 'At least one active administrator must remain')
        }
        state.users = state.users.map((user) => (user.id === params.id ? ({ ...user, ...body, version: user.version + 1 } as User) : user))
        return HttpResponse.json(state.users.find((user) => user.id === params.id))
      }),
      http.post('/api/admin/users', async ({ request }) => {
        const body = (await request.json()) as { email: string; displayName: string; role: User['role'] }
        state.created.push(body)
        if (body.email === 'taken@example.com') return problem(409, 'EMAIL_ALREADY_EXISTS', 'A user with this email already exists')
        state.users.push(aUser({ id: `u-${state.users.length + 1}`, ...body }))
        return HttpResponse.json(state.users.at(-1), { status: 201 })
      }),
    )
    return state
  }

  it('lists people and filters by role', async () => {
    signedInAdmin()
    const backend = peopleBackend([aUser({}), aUser({ id: 'u-2', email: 'rita@example.com', displayName: 'Rita Requester', role: 'REQUESTER' })])
    const { user } = renderApp('/admin/people')

    expect(await screen.findByRole('cell', { name: 'Rita Requester' })).toBeVisible()
    await user.selectOptions(screen.getByLabelText('Role'), 'AGENT')

    await waitForAbsent('Rita Requester')
    expect(backend.searches.at(-1)).toContain('role=AGENT')
  })

  it('changes a role with the current version and explains a refusal', async () => {
    signedInAdmin()
    const backend = peopleBackend([aUser({}), aUser({ id: 'u-admin', email: 'ann@example.com', displayName: 'Ann Admin', role: 'ADMIN', version: 7 })])
    const { user } = renderApp('/admin/people')

    await user.click(await screen.findByRole('button', { name: 'Edit Nina Network' }))
    const first = await screen.findByRole('dialog')
    await user.selectOptions(within(first).getByLabelText('Role'), 'TEAM_LEAD')
    await user.click(within(first).getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('cell', { name: 'Team lead' })).toBeVisible()
    expect(backend.patches[0]).toEqual({ role: 'TEAM_LEAD', active: true, version: 3 })

    await user.click(screen.getByRole('button', { name: 'Edit Ann Admin' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Role'), 'AGENT')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('At least one active administrator must remain')
  })

  it('adds a person and reports a taken email', async () => {
    signedInAdmin()
    const backend = peopleBackend([])
    const { user } = renderApp('/admin/people')

    await user.click(await screen.findByRole('button', { name: 'Add person' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Email'), 'taken@example.com')
    await user.type(within(dialog).getByLabelText('Name'), 'Tom Taken')
    await user.type(within(dialog).getByLabelText('Initial password'), 'a-long-enough-password')
    await user.click(within(dialog).getByRole('button', { name: 'Add person' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('already exists')

    await user.clear(within(dialog).getByLabelText('Email'))
    await user.type(within(dialog).getByLabelText('Email'), 'tom@example.com')
    await user.selectOptions(within(dialog).getByLabelText('Role'), 'AGENT')
    await user.click(within(dialog).getByRole('button', { name: 'Add person' }))

    expect(await screen.findByRole('cell', { name: 'tom@example.com' })).toBeVisible()
    expect(backend.created.at(-1)).toEqual({ email: 'tom@example.com', displayName: 'Tom Taken', role: 'AGENT', initialPassword: 'a-long-enough-password' })
  })
})

describe('teams', () => {
  function teamsBackend() {
    const nina: TeamMember = { userId: 'u-1', displayName: 'Nina Network', email: 'nina@example.com', role: 'AGENT', active: true, joinedAt: at }
    const state = { teams: [aTeam({}), aTeam({ id: 'team-old', name: 'Old Team', active: false })], members: [nina], added: [] as string[] }
    server.use(
      http.get('/api/admin/teams', () => HttpResponse.json(state.teams)),
      http.get('/api/teams/:id/members', () => HttpResponse.json(state.members)),
      http.get('/api/admin/users', () =>
        HttpResponse.json({
          content: [
            aUser({}),
            aUser({ id: 'u-3', displayName: 'Leo Lead', role: 'TEAM_LEAD' }),
            aUser({ id: 'u-4', displayName: 'Rita Requester', role: 'REQUESTER' }),
          ],
          page: 0,
          size: 100,
          totalElements: 3,
          totalPages: 1,
        }),
      ),
      http.put('/api/admin/teams/:id/members/:userId', ({ params }) => {
        state.added.push(String(params.userId))
        state.members.push({ userId: String(params.userId), displayName: 'Leo Lead', email: 'leo@example.com', role: 'TEAM_LEAD', active: true, joinedAt: at })
        return new HttpResponse(null, { status: 204 })
      }),
      http.delete('/api/admin/teams/:id/members/:userId', () => problem(409, 'MEMBER_HAS_OPEN_TICKETS', 'Nina Network still has 2 open tickets in this team')),
      http.post('/api/admin/teams', async ({ request }) => {
        const body = (await request.json()) as { name: string }
        state.teams.push(aTeam({ id: 'team-new', name: body.name }))
        return HttpResponse.json(state.teams.at(-1), { status: 201 })
      }),
    )
    return state
  }

  it('adds active staff to a team, never requesters, and explains a refused removal', async () => {
    signedInAdmin()
    const backend = teamsBackend()
    const { user } = renderApp('/admin/teams')

    expect(within(await screen.findByRole('list', { name: 'Teams' })).getByText('Inactive')).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Network Team' }))

    const members = await screen.findByRole('list', { name: 'Members of Network Team' })
    expect(within(members).getByText('Nina Network')).toBeVisible()
    const picker = screen.getByLabelText('Add a member')
    expect(within(picker).queryByText(/Rita Requester/)).not.toBeInTheDocument()
    expect(within(picker).queryByText(/Nina Network/)).not.toBeInTheDocument()

    await user.selectOptions(picker, 'u-3')
    await user.click(screen.getByRole('button', { name: 'Add' }))
    expect(await within(members).findByText('Leo Lead')).toBeVisible()
    expect(backend.added).toEqual(['u-3'])

    await user.click(screen.getByRole('button', { name: 'Remove Nina Network' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('still has 2 open tickets')
  })

  it('creates a team and opens it', async () => {
    signedInAdmin()
    teamsBackend()
    const { user } = renderApp('/admin/teams')

    await user.type(await screen.findByLabelText('New team'), 'Facilities')
    await user.click(screen.getByRole('button', { name: 'Create' }))

    expect(await screen.findByRole('heading', { name: 'Facilities' })).toBeVisible()
  })
})

describe('categories', () => {
  function categoriesBackend() {
    const wifi = aCategory({ id: 'c-wifi', code: 'WIFI', name: 'Wi-Fi', parentId: 'c-net', defaultTeamId: null })
    const state = { categories: [aCategory({ subcategories: [wifi] })], created: [] as unknown[], patches: [] as unknown[] }
    server.use(
      http.get('/api/admin/categories', () => HttpResponse.json(state.categories)),
      http.get('/api/admin/teams', () => HttpResponse.json([aTeam({}), aTeam({ id: 'team-hw', name: 'Hardware Team' })])),
      http.post('/api/admin/categories', async ({ request }) => {
        state.created.push(await request.json())
        return HttpResponse.json(aCategory({ id: 'c-new' }), { status: 201 })
      }),
      http.patch('/api/admin/categories/:id', async ({ request }) => {
        state.patches.push(await request.json())
        return HttpResponse.json(aCategory({}))
      }),
    )
    return state
  }

  it('shows the tree with where each kind of ticket goes', async () => {
    signedInAdmin()
    categoriesBackend()
    renderApp('/admin/categories')

    const wifiRow = (await screen.findByText('Wi-Fi')).closest('tr') as HTMLElement
    expect(within(wifiRow).getByText('Parent’s team')).toBeVisible()
    const networkRow = screen.getByText('Network', { selector: 'td' }).closest('tr') as HTMLElement
    expect(within(networkRow).getByText('Network Team')).toBeVisible()
  })

  it('adds a subcategory routed to its own team and retires a category', async () => {
    signedInAdmin()
    const backend = categoriesBackend()
    const { user } = renderApp('/admin/categories')

    await user.click(await screen.findByRole('button', { name: 'Add category' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Inside'), 'c-net')
    await user.type(within(dialog).getByLabelText('Name'), 'Printers')
    await user.type(within(dialog).getByLabelText('Code'), 'printers')
    await user.selectOptions(within(dialog).getByLabelText('Routed to'), 'team-hw')
    await user.click(within(dialog).getByRole('button', { name: 'Add category' }))
    await waitForAbsent('Add category', 'dialog')
    expect(backend.created).toEqual([{ code: 'PRINTERS', name: 'Printers', appliesTo: 'INCIDENT', parentId: 'c-net', defaultTeamId: 'team-hw' }])

    await user.click(screen.getByRole('button', { name: 'Edit Wi-Fi' }))
    const edit = await screen.findByRole('dialog')
    await user.click(within(edit).getByLabelText('Offered for new tickets'))
    await user.click(within(edit).getByRole('button', { name: 'Save' }))
    await waitForAbsent('Edit Wi-Fi', 'dialog')
    expect(backend.patches).toEqual([{ name: 'Wi-Fi', active: false, version: 0 }])
  })
})

/** Waits until nothing with that text (or that dialog title) is on screen. */
async function waitForAbsent(text: string, role?: 'dialog') {
  await waitFor(() => {
    if (role === 'dialog') expect(screen.queryByRole('dialog', { name: text })).not.toBeInTheDocument()
    else expect(screen.queryByText(text)).not.toBeInTheDocument()
  })
}

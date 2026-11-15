import { fireEvent, screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { ServiceDeskReport } from '@/api/types'
import type { AuthenticatedUser } from '@/auth/roles'
import { problem } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'
import { server } from '@/test/server'
import { agent, signedInAs, staffContext } from '@/test/tickets'

const lead: AuthenticatedUser = { id: 'u-lead', email: 'leo@example.com', displayName: 'Leo Lead', role: 'TEAM_LEAD' }

function aReport(overrides: Partial<ServiceDeskReport> = {}): ServiceDeskReport {
  return {
    from: '2027-03-01',
    to: '2027-03-03',
    zone: 'Europe/London',
    teamId: null,
    created: 4,
    resolved: 2,
    openNow: 2,
    breachedOpen: 1,
    daily: [
      { day: '2027-03-01', created: 3, resolved: 1 },
      { day: '2027-03-02', created: 1, resolved: 1 },
      { day: '2027-03-03', created: 0, resolved: 0 },
    ],
    backlogByPriority: [
      { priority: 'P2', count: 1 },
      { priority: 'P4', count: 2 },
    ],
    sla: [{ metric: 'RESPONSE', met: 1, missed: 1, percentMet: 50 }],
    times: [
      { priority: 'P1', firstResponseMinutes: 20, resolutionMinutes: 780, resolvedCount: 2 },
      { priority: 'P2', firstResponseMinutes: null, resolutionMinutes: null, resolvedCount: 0 },
    ],
    topCategories: [{ categoryId: 'c-net', name: 'Network', count: 3 }],
    changeOutcomes: [{ outcome: 'SUCCESSFUL', count: 1 }],
    ...overrides,
  }
}

function reportBackend(report = aReport()) {
  const requests: URLSearchParams[] = []
  server.use(
    http.get('/api/reports/service-desk', ({ request }) => {
      const params = new URL(request.url).searchParams
      requests.push(params)
      if (params.get('teamId') === 'team-hardware') return problem(403, 'ACCESS_DENIED', 'Team leads see reports for their own teams')
      return HttpResponse.json(report)
    }),
  )
  return requests
}

describe('reports', () => {
  it('shows the headline figures, the backlog and the tables', async () => {
    signedInAs(lead)
    staffContext(lead)
    reportBackend()
    renderApp('/reports')

    const tiles = await screen.findByLabelText('Headline figures')
    expect(within(tiles).getByText('Raised').nextSibling).toHaveTextContent('4')
    expect(within(tiles).getByText('First responses on time').nextSibling).toHaveTextContent('50%')
    expect(
      within(screen.getByRole('list', { name: 'Open tickets by priority' }))
        .getByText('P4')
        .closest('li'),
    ).toHaveTextContent('2')
    const times = screen.getByRole('heading', { name: 'Typical times, by priority' }).closest('section') as HTMLElement
    expect(within(times).getByRole('row', { name: /P1/ })).toHaveTextContent('13 h')
    expect(within(times).getByRole('row', { name: /P2/ })).toHaveTextContent('–')
    expect(screen.getByText('Successful')).toBeVisible()
  })

  it('draws raised and resolved per day, with a hover readout and a table view', async () => {
    signedInAs(lead)
    staffContext(lead)
    reportBackend()
    const { user } = renderApp('/reports')

    const chart = await screen.findByRole('img', { name: 'Tickets raised and resolved per day' })
    expect(
      within(screen.getByRole('list', { name: 'Legend' }))
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual(['Raised', 'Resolved'])
    chart.getBoundingClientRect = () => ({ left: 0, width: 720, top: 0, height: 220, right: 720, bottom: 220, x: 0, y: 0, toJSON: () => ({}) })
    fireEvent.pointerMove(chart, { clientX: 36 })
    expect(screen.getByRole('tooltip')).toHaveTextContent('Raised: 3')

    await user.click(screen.getByRole('button', { name: 'Show table' }))
    expect(screen.getByRole('row', { name: /2 Mar|Mar 2/ })).toHaveTextContent('1')
  })

  it('asks for the chosen period and team, and explains a refusal', async () => {
    signedInAs(lead)
    staffContext(lead, ['team-network', 'team-hardware'])
    const requests = reportBackend()
    const { user } = renderApp('/reports')

    await screen.findByRole('img', { name: /raised and resolved/ })
    expect(requests[0]?.get('from')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: '7 days' }))
    await user.selectOptions(screen.getByLabelText('Team'), 'team-hardware')

    expect(await screen.findByText(/couldn’t be loaded|own teams|went wrong/i)).toBeVisible()
    const last = requests.at(-1) as URLSearchParams
    expect(last.get('teamId')).toBe('team-hardware')
    const days = (Date.parse(last.get('to') as string) - Date.parse(last.get('from') as string)) / 86_400_000
    expect(days).toBe(6)
  })

  it('is for team leads and admins only', async () => {
    signedInAs(agent)
    staffContext(agent)
    renderApp('/reports')

    expect(await screen.findByRole('heading', { name: 'You don’t have access to this page' })).toBeVisible()
    expect(screen.queryByRole('link', { name: 'Reports' })).not.toBeInTheDocument()
  })
})

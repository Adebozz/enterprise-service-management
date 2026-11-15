import { screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { BusinessHours, SlaPolicy } from '@/api/types'
import type { AuthenticatedUser } from '@/auth/roles'
import { problem } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'
import { server } from '@/test/server'
import { agent, signedInAs, staffContext } from '@/test/tickets'

const admin: AuthenticatedUser = {
  id: '0199b2c4-6a1e-7c3e-9f00-000000000301',
  email: 'ann@example.com',
  displayName: 'Ann Admin',
  role: 'ADMIN',
}

const p1: SlaPolicy = { id: 'p1', ticketType: 'INCIDENT', priority: 'P1', responseMinutes: 15, resolutionMinutes: 240, businessHours: false, version: 0 }
const p2: SlaPolicy = { id: 'p2', ticketType: 'INCIDENT', priority: 'P2', responseMinutes: 60, resolutionMinutes: 480, businessHours: false, version: 0 }

/** Policies held in memory, recording what the page sends. */
function policyBackend() {
  const state = {
    policies: [p1, p2],
    sent: [] as unknown[],
    calendar: {
      zone: 'Europe/London',
      week: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'].map((day) => ({ day, opensAt: '09:00:00', closesAt: '17:30:00' })),
      holidays: [] as { day: string; name: string }[],
    } as BusinessHours,
    weeks: [] as unknown[],
  }
  server.use(
    http.get('/api/admin/business-hours', () => HttpResponse.json(state.calendar)),
    http.put('/api/admin/business-hours/week', async ({ request }) => {
      const body = (await request.json()) as { days: BusinessHours['week'] }
      state.weeks.push(body)
      state.calendar = { ...state.calendar, week: body.days }
      return HttpResponse.json(state.calendar)
    }),
    http.post('/api/admin/business-hours/holidays', async ({ request }) => {
      const body = (await request.json()) as { day: string; name: string }
      if (state.calendar.holidays.some((holiday) => holiday.day === body.day)) {
        return problem(422, 'INVALID_BUSINESS_HOURS', `${body.day} is already a holiday`)
      }
      state.calendar = { ...state.calendar, holidays: [...state.calendar.holidays, body] }
      return HttpResponse.json(state.calendar)
    }),
    http.delete('/api/admin/business-hours/holidays/:day', ({ params }) => {
      state.calendar = { ...state.calendar, holidays: state.calendar.holidays.filter((holiday) => holiday.day !== params.day) }
      return HttpResponse.json(state.calendar)
    }),
    http.get('/api/admin/sla-policies', () => HttpResponse.json(state.policies)),
    http.put('/api/admin/sla-policies/:id', async ({ params, request }) => {
      const body = (await request.json()) as { responseMinutes: number; resolutionMinutes: number; version: number }
      state.sent.push({ id: params.id, ...body })
      if (body.responseMinutes > body.resolutionMinutes) {
        return problem(400, 'VALIDATION_FAILED', 'The response target can’t be longer than the resolution target')
      }
      state.policies = state.policies.map((p) => (p.id === params.id ? { ...p, ...body, version: p.version + 1 } : p))
      return HttpResponse.json(state.policies.find((p) => p.id === params.id))
    }),
  )
  return state
}

describe('SLA targets page', () => {
  it('lists targets with readable durations and saves a change', async () => {
    signedInAs(admin)
    staffContext(admin)
    const state = policyBackend()
    const { user } = renderApp('/admin/sla')

    const response = await screen.findByLabelText('Incident P2 first response, in minutes')
    expect(screen.getByText('4 h')).toBeVisible() // P1 resolution: 240 minutes

    await user.clear(response)
    await user.type(response, '30')
    await user.click(screen.getByRole('button', { name: 'Save Incident P2' }))

    expect(await screen.findByText('30 min')).toBeVisible()
    expect(state.sent).toEqual([{ id: 'p2', responseMinutes: 30, resolutionMinutes: 480, businessHours: false, version: 0 }])
    expect(screen.getByRole('button', { name: 'Save Incident P2' })).toBeDisabled() // saved, nothing pending
  })

  it('shows why the server refused a change', async () => {
    signedInAs(admin)
    staffContext(admin)
    policyBackend()
    const { user } = renderApp('/admin/sla')

    const response = await screen.findByLabelText('Incident P1 first response, in minutes')
    await user.clear(response)
    await user.type(response, '999')
    await user.click(screen.getByRole('button', { name: 'Save Incident P1' }))

    const row = screen.getByRole('button', { name: 'Save Incident P1' }).closest('tr')
    expect(await within(row as HTMLElement).findByRole('alert')).toHaveTextContent('longer than the resolution target')
  })

  it('switches a target to business hours', async () => {
    signedInAs(admin)
    staffContext(admin)
    const state = policyBackend()
    const { user } = renderApp('/admin/sla')

    await user.selectOptions(await screen.findByLabelText('Incident P2 counts'), 'BUSINESS')
    await user.click(screen.getByRole('button', { name: 'Save Incident P2' }))

    await waitFor(() => expect(state.sent).toEqual([{ id: 'p2', responseMinutes: 60, resolutionMinutes: 480, businessHours: true, version: 0 }]))
  })

  it('saves the opening hours, sending only the open days', async () => {
    signedInAs(admin)
    staffContext(admin)
    const state = policyBackend()
    const { user } = renderApp('/admin/sla')

    expect(await screen.findByText('Opening hours (Europe/London)')).toBeVisible()
    expect(screen.getByLabelText('Monday opens')).toHaveValue('09:00')
    await user.click(screen.getByRole('checkbox', { name: 'Friday' }))
    await user.click(screen.getByRole('checkbox', { name: 'Saturday' }))
    await user.click(screen.getByRole('button', { name: 'Save opening hours' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Opening hours saved.')
    expect(state.weeks).toEqual([
      {
        days: [
          { day: 'MONDAY', opensAt: '09:00', closesAt: '17:30' },
          { day: 'TUESDAY', opensAt: '09:00', closesAt: '17:30' },
          { day: 'WEDNESDAY', opensAt: '09:00', closesAt: '17:30' },
          { day: 'THURSDAY', opensAt: '09:00', closesAt: '17:30' },
          { day: 'SATURDAY', opensAt: '09:00', closesAt: '17:30' },
        ],
      },
    ])
  })

  it('refuses a week with no open day without asking the server', async () => {
    signedInAs(admin)
    staffContext(admin)
    const state = policyBackend()
    const { user } = renderApp('/admin/sla')

    for (const day of ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday']) {
      await user.click(await screen.findByRole('checkbox', { name: day }))
    }
    await user.click(screen.getByRole('button', { name: 'Save opening hours' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('at least one day')
    expect(state.weeks).toHaveLength(0)
  })

  it('adds and removes holidays, and explains a duplicate', async () => {
    signedInAs(admin)
    staffContext(admin)
    policyBackend()
    const { user } = renderApp('/admin/sla')

    const addHoliday = async () => {
      await user.type(await screen.findByLabelText('Day'), '2026-12-25')
      await user.type(screen.getByLabelText('Name'), 'Christmas Day')
      await user.click(screen.getByRole('button', { name: 'Add holiday' }))
    }
    await addHoliday()
    const list = await screen.findByRole('list', { name: 'Holidays' })
    expect(within(list).getByText('Christmas Day', { exact: false })).toBeVisible()

    await addHoliday()
    expect(await screen.findByRole('alert')).toHaveTextContent('already a holiday')

    await user.click(screen.getByRole('button', { name: 'Remove Christmas Day' }))
    expect(await screen.findByText(/No holidays/)).toBeVisible()
  })

  it('is only in the navigation for admins', async () => {
    signedInAs(admin)
    staffContext(admin)
    policyBackend()
    renderApp('/admin/sla')
    expect(await screen.findByRole('link', { name: 'Admin' })).toBeVisible()
  })

  it('is refused to agents', async () => {
    signedInAs(agent)
    staffContext(agent)

    renderApp('/admin/sla')

    expect(await screen.findByRole('heading', { name: 'You don’t have access to this page' })).toBeVisible()
    expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument()
  })
})

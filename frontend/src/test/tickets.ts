import { http, HttpResponse } from 'msw'
import type { Category, Comment, Ticket, TicketSummary } from '@/api/types'
import type { AuthenticatedUser } from '@/auth/roles'
import { sessionFor } from './fixtures'
import { server } from './server'

export const requester: AuthenticatedUser = {
  id: '0199b2c4-6a1e-7c3e-9f00-000000000101',
  email: 'rita@example.com',
  displayName: 'Rita Requester',
  role: 'REQUESTER',
}

export const agent: AuthenticatedUser = {
  id: '0199b2c4-6a1e-7c3e-9f00-000000000201',
  email: 'alex@example.com',
  displayName: 'Alex Agent',
  role: 'AGENT',
}

/** Staff pages also read the profile (teams) and the team list. */
export function staffContext(user: AuthenticatedUser, teamIds: string[] = ['team-network']) {
  server.use(
    http.get('/api/users/me', () =>
      HttpResponse.json({ ...user, teams: teamIds.map((id) => ({ id, name: id === 'team-network' ? 'Network Team' : 'Hardware Team' })) }),
    ),
    http.get('/api/teams', () =>
      HttpResponse.json([
        { id: 'team-network', name: 'Network Team', description: null, active: true, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z', version: 0 },
        { id: 'team-hardware', name: 'Hardware Team', description: null, active: true, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z', version: 0 },
      ]),
    ),
    http.get('/api/tickets/:id/history', () => HttpResponse.json([])),
  )
}

/** Signs the user in as the app starts (the silent refresh succeeds). */
export function signedInAs(user: AuthenticatedUser) {
  server.use(http.post('/api/auth/refresh', () => HttpResponse.json(sessionFor(user))))
}

const ref = (id: string, name: string) => ({ id, name })

export function aTicket(overrides: Partial<Ticket> = {}): Ticket {
  return {
    id: 'ticket-1',
    reference: 'INC-000042',
    type: 'INCIDENT',
    title: 'Wi-Fi keeps dropping',
    description: 'Disconnects every few minutes on floor 3',
    status: 'NEW',
    impact: 'LOW',
    urgency: 'MEDIUM',
    priority: 'P4',
    category: ref('cat-network', 'Network'),
    subcategory: ref('cat-wifi', 'Wi-Fi'),
    requester: ref(requester.id, requester.displayName),
    assignedTeam: ref('team-network', 'Network Team'),
    assignee: null,
    createdAt: '2026-10-04T08:00:00Z',
    updatedAt: '2026-10-04T08:00:00Z',
    firstRespondedAt: null,
    resolvedAt: null,
    closedAt: null,
    version: 0,
    incident: { affectedService: 'Office Wi-Fi', resolutionCode: null, resolutionNotes: null, reopenCount: 0 },
    serviceRequest: null,
    ...overrides,
  }
}

export function aSummary(overrides: Partial<TicketSummary> = {}): TicketSummary {
  return {
    id: 'ticket-1',
    reference: 'INC-000042',
    type: 'INCIDENT',
    title: 'Wi-Fi keeps dropping',
    status: 'NEW',
    priority: 'P4',
    category: ref('cat-network', 'Network'),
    assignedTeam: ref('team-network', 'Network Team'),
    assignee: null,
    requester: ref(requester.id, requester.displayName),
    createdAt: '2026-10-04T08:00:00Z',
    updatedAt: '2026-10-04T08:00:00Z',
    firstRespondedAt: null,
    resolvedAt: null,
    ...overrides,
  }
}

export function aComment(overrides: Partial<Comment> = {}): Comment {
  return {
    id: 'comment-1',
    author: ref('agent-1', 'Alex Agent'),
    visibility: 'PUBLIC',
    body: 'Which floor are you on?',
    relatedStatus: null,
    createdAt: '2026-10-04T09:00:00Z',
    ...overrides,
  }
}

export const incidentCategories: Category[] = [
  {
    id: 'cat-network',
    code: 'NETWORK',
    name: 'Network',
    parentId: null,
    defaultTeamId: 'team-network',
    appliesTo: 'INCIDENT',
    active: true,
    version: 0,
    subcategories: [
      { id: 'cat-wifi', code: 'WIFI', name: 'Wi-Fi', parentId: 'cat-network', defaultTeamId: null, appliesTo: 'INCIDENT', active: true, version: 0, subcategories: [] },
      { id: 'cat-vpn', code: 'VPN', name: 'VPN', parentId: 'cat-network', defaultTeamId: null, appliesTo: 'INCIDENT', active: true, version: 0, subcategories: [] },
    ],
  },
  {
    id: 'cat-hardware',
    code: 'HARDWARE',
    name: 'Hardware',
    parentId: null,
    defaultTeamId: 'team-hardware',
    appliesTo: 'ANY',
    active: true,
    version: 0,
    subcategories: [
      { id: 'cat-laptop', code: 'LAPTOP', name: 'Laptop', parentId: 'cat-hardware', defaultTeamId: null, appliesTo: 'ANY', active: true, version: 0, subcategories: [] },
    ],
  },
]

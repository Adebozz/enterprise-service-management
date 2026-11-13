import { api } from './client'
import { isApiError, unwrap } from './errors'
import type { TicketListParams, TicketType } from './types'

/**
 * Query keys are hierarchical, so one invalidation of ['tickets'] after a change refreshes every
 * list and detail view that could show stale data.
 */
export const queryKeys = {
  me: ['me'] as const,
  tickets: ['tickets'] as const,
  ticketList: (params: TicketListParams) => ['tickets', 'list', params] as const,
  ticket: (id: string) => ['tickets', 'detail', id] as const,
  comments: (id: string) => ['tickets', 'detail', id, 'comments'] as const,
  transitions: (id: string) => ['tickets', 'detail', id, 'transitions'] as const,
  history: (id: string) => ['tickets', 'detail', id, 'history'] as const,
  sla: (id: string) => ['tickets', 'detail', id, 'sla'] as const,
  approval: (id: string) => ['tickets', 'detail', id, 'approval'] as const,
  /** Under ['tickets'], so any ticket change also refreshes the inbox. */
  approvals: ['tickets', 'approvals'] as const,
  /** Under ['tickets'] too: planning, approving or cancelling a change moves it on the schedule. */
  changeSchedule: ['tickets', 'change-schedule'] as const,
  slaPolicies: ['sla-policies'] as const,
  catalogue: ['catalogue'] as const,
  catalogueItem: (id: string) => ['catalogue', 'item', id] as const,
  adminCatalogue: ['catalogue', 'admin'] as const,
  categories: (type: TicketType) => ['categories', type] as const,
  teams: ['teams'] as const,
  teamMembers: (teamId: string) => ['teams', teamId, 'members'] as const,
}

export const myProfileQuery = {
  queryKey: queryKeys.me,
  queryFn: async () => unwrap(await api.GET('/api/users/me')),
}

export const ticketListQuery = (params: TicketListParams) => ({
  queryKey: queryKeys.ticketList(params),
  queryFn: async () => unwrap(await api.GET('/api/tickets', { params: { query: params } })),
})

export const ticketQuery = (id: string) => ({
  queryKey: queryKeys.ticket(id),
  queryFn: async () => unwrap(await api.GET('/api/tickets/{id}', { params: { path: { id } } })),
})

export const commentsQuery = (id: string) => ({
  queryKey: queryKeys.comments(id),
  queryFn: async () => unwrap(await api.GET('/api/tickets/{id}/comments', { params: { path: { id } } })),
})

export const transitionsQuery = (id: string) => ({
  queryKey: queryKeys.transitions(id),
  queryFn: async () => unwrap(await api.GET('/api/tickets/{id}/transitions', { params: { path: { id } } })),
})

export const categoriesQuery = (type: TicketType) => ({
  queryKey: queryKeys.categories(type),
  queryFn: async () => unwrap(await api.GET('/api/categories', { params: { query: { type } } })),
  staleTime: 5 * 60_000, // reference data: changes rarely
})

export const historyQuery = (id: string) => ({
  queryKey: queryKeys.history(id),
  queryFn: async () => unwrap(await api.GET('/api/tickets/{id}/history', { params: { path: { id } } })),
})

export const slaQuery = (id: string) => ({
  queryKey: queryKeys.sla(id),
  queryFn: async () => unwrap(await api.GET('/api/tickets/{id}/sla', { params: { path: { id } } })),
  refetchInterval: 60_000, // time-based: keep "due in ..." honest while the page stays open
})

export const slaPoliciesQuery = {
  queryKey: queryKeys.slaPolicies,
  queryFn: async () => unwrap(await api.GET('/api/admin/sla-policies')),
}

export const teamsQuery = {
  queryKey: queryKeys.teams,
  queryFn: async () => unwrap(await api.GET('/api/teams')),
  staleTime: 5 * 60_000,
}

export const teamMembersQuery = (teamId: string) => ({
  queryKey: queryKeys.teamMembers(teamId),
  queryFn: async () => unwrap(await api.GET('/api/teams/{id}/members', { params: { path: { id: teamId } } })),
  staleTime: 60_000,
})

export const catalogueQuery = {
  queryKey: queryKeys.catalogue,
  queryFn: async () => unwrap(await api.GET('/api/catalogue')),
}

export const catalogueItemQuery = (id: string) => ({
  queryKey: queryKeys.catalogueItem(id),
  queryFn: async () => unwrap(await api.GET('/api/catalogue/{id}', { params: { path: { id } } })),
})

export const adminCatalogueQuery = {
  queryKey: queryKeys.adminCatalogue,
  queryFn: async () => unwrap(await api.GET('/api/admin/catalogue')),
}

/** The ticket's approval, or null when it needs none (the API answers 404). */
export const ticketApprovalQuery = (id: string) => ({
  queryKey: queryKeys.approval(id),
  queryFn: async () => {
    try {
      return unwrap(await api.GET('/api/tickets/{id}/approval', { params: { path: { id } } }))
    } catch (error) {
      if (isApiError(error) && error.code === 'RESOURCE_NOT_FOUND') return null
      throw error
    }
  },
})

export const pendingApprovalsQuery = {
  queryKey: queryKeys.approvals,
  queryFn: async () => unwrap(await api.GET('/api/approvals')),
}

export const changeScheduleQuery = {
  queryKey: queryKeys.changeSchedule,
  queryFn: async () => unwrap(await api.GET('/api/changes/schedule')),
}

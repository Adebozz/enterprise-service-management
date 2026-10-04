import type { components } from '@/api/schema'

export type Role = components['schemas']['AuthenticatedUser']['role']
export type AuthenticatedUser = components['schemas']['AuthenticatedUser']

const LEVEL: Record<Role, number> = { REQUESTER: 1, AGENT: 2, TEAM_LEAD: 3, ADMIN: 4 }

/**
 * Mirrors the backend role hierarchy for the UI only (what to show). The server enforces every
 * permission regardless of what the browser does.
 */
export function hasRole(user: AuthenticatedUser, required: Role): boolean {
  return LEVEL[user.role] >= LEVEL[required]
}

export const ROLE_LABEL: Record<Role, string> = {
  REQUESTER: 'Requester',
  AGENT: 'Agent',
  TEAM_LEAD: 'Team lead',
  ADMIN: 'Administrator',
}

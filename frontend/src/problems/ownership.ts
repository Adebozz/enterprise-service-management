import { useQuery } from '@tanstack/react-query'
import { myProfileQuery } from '@/api/queries'
import type { Ticket } from '@/api/types'
import { useCurrentUser } from '@/auth/AuthContext'

/**
 * Whether the signed-in user handles this ticket: an admin, its assignee, or a member of its team.
 * A UX guess for which controls to offer; the server makes the decision and its refusals are shown.
 */
export function useHandlesTicket(ticket: Ticket): boolean {
  const user = useCurrentUser()
  const profile = useQuery(myProfileQuery)
  const inTeam = profile.data?.teams.some((team) => team.id === ticket.assignedTeam.id) ?? false
  return user.role === 'ADMIN' || ticket.assignee?.id === user.id || inTeam
}

/** Mirrors the server's Problem.isOpen: the analysis can change and incidents can be linked. */
export function isOpenProblem(status: string): boolean {
  return ['NEW', 'ASSIGNED', 'INVESTIGATING', 'KNOWN_ERROR'].includes(status)
}

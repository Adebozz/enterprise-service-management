import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { api } from '@/api/client'
import { isApiError, unwrap } from '@/api/errors'
import { myProfileQuery, queryKeys, teamMembersQuery, teamsQuery } from '@/api/queries'
import type { AssignmentRequest, Ticket } from '@/api/types'
import { useCurrentUser } from '@/auth/AuthContext'
import { hasRole } from '@/auth/roles'
import { NativeSelect } from '@/components/NativeSelect'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { isAssignable } from './labels'

/**
 * Take / release / assign / transfer. Which controls appear is a UX guess from role and team
 * membership; the server's AssignmentPolicy makes the actual decision and its refusals are shown.
 */
export function AssignmentPanel({ ticket }: { ticket: Ticket }) {
  const user = useCurrentUser()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const profile = useQuery(myProfileQuery)
  const teams = useQuery(teamsQuery)
  const [message, setMessage] = useState<string | null>(null)

  const isAdmin = user.role === 'ADMIN'
  const inTicketTeam = profile.data?.teams.some((t) => t.id === ticket.assignedTeam.id) ?? false
  const isAssignee = ticket.assignee?.id === user.id
  const canLead = isAdmin || (hasRole(user, 'TEAM_LEAD') && inTicketTeam)
  const supporting = isAdmin || inTicketTeam || isAssignee
  const members = useQuery({ ...teamMembersQuery(ticket.assignedTeam.id), enabled: canLead })

  const assign = useMutation({
    mutationFn: async (body: AssignmentRequest) =>
      unwrap(await api.PUT('/api/tickets/{id}/assignment', { params: { path: { id: ticket.id } }, body })),
  })

  const change = async (teamId: string, assigneeId: string | null, transferredTo?: string) => {
    setMessage(null)
    try {
      const result = await assign.mutateAsync({ teamId, assigneeId: assigneeId ?? undefined, version: ticket.version })
      await queryClient.invalidateQueries({ queryKey: queryKeys.tickets })
      if (transferredTo) {
        // After a transfer the agent may no longer be allowed to see the ticket (by design), so
        // go back to the queue rather than showing "not found".
        void navigate('/queue', { state: { notice: `${result.reference} was transferred to ${transferredTo}.` } })
      }
    } catch (error) {
      if (isApiError(error) && error.code === 'CONCURRENT_MODIFICATION') {
        setMessage('Someone else changed this ticket meanwhile. It has been reloaded; please try again.')
        await queryClient.invalidateQueries({ queryKey: queryKeys.ticket(ticket.id) })
      } else {
        setMessage(isApiError(error) ? error.message : 'The assignment couldn’t be changed. Please try again.')
      }
    }
  }

  if (!isAssignable(ticket.status) || !supporting) {
    return null
  }
  const otherTeams = teams.data?.filter((t) => t.id !== ticket.assignedTeam.id) ?? []

  return (
    <section aria-labelledby="assignment-heading" className="grid gap-3 rounded-md border p-4">
      <h2 id="assignment-heading" className="font-semibold">
        Assignment
      </h2>
      {message && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{message}</AlertDescription>
        </Alert>
      )}
      <div className="flex flex-wrap gap-2">
        {!ticket.assignee && inTicketTeam && (
          <Button size="sm" disabled={assign.isPending} onClick={() => void change(ticket.assignedTeam.id, user.id)}>
            Take it
          </Button>
        )}
        {isAssignee && (
          <Button size="sm" variant="outline" disabled={assign.isPending} onClick={() => void change(ticket.assignedTeam.id, null)}>
            Release
          </Button>
        )}
      </div>
      {canLead && members.isSuccess && (
        <div className="grid gap-2">
          <Label htmlFor="assignee">Assign to</Label>
          <NativeSelect
            id="assignee"
            value={ticket.assignee?.id ?? ''}
            disabled={assign.isPending}
            onChange={(e) => void change(ticket.assignedTeam.id, e.target.value || null)}
          >
            <option value="">Unassigned</option>
            {members.data
              .filter((m) => m.active)
              .map((m) => (
                <option key={m.userId} value={m.userId}>
                  {m.displayName}
                </option>
              ))}
          </NativeSelect>
        </div>
      )}
      {otherTeams.length > 0 && (
        <TransferControl
          teams={otherTeams}
          disabled={assign.isPending}
          onTransfer={(teamId, teamName) => void change(teamId, null, teamName)}
        />
      )}
    </section>
  )
}

function TransferControl({
  teams,
  disabled,
  onTransfer,
}: {
  teams: { id: string; name: string }[]
  disabled: boolean
  onTransfer: (teamId: string, teamName: string) => void
}) {
  const [target, setTarget] = useState('')
  const team = teams.find((t) => t.id === target)
  return (
    <div className="grid gap-2">
      <Label htmlFor="transfer">Transfer to another team</Label>
      <div className="flex gap-2">
        <NativeSelect id="transfer" value={target} onChange={(e) => setTarget(e.target.value)} disabled={disabled}>
          <option value="">Select a team</option>
          {teams.map((t) => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </NativeSelect>
        <Button size="sm" variant="outline" disabled={!team || disabled} onClick={() => team && onTransfer(team.id, team.name)}>
          Transfer
        </Button>
      </div>
    </div>
  )
}

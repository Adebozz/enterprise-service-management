import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { api } from '@/api/client'
import { unwrap } from '@/api/errors'
import { adminTeamsQuery, adminUsersQuery, queryKeys, teamMembersQuery } from '@/api/queries'
import type { Team } from '@/api/types'
import { ROLE_LABEL } from '@/auth/roles'
import { NativeSelect } from '@/components/NativeSelect'
import { QueryError } from '@/components/QueryError'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { Textarea } from '@/components/ui/textarea'
import { cn } from '@/lib/utils'
import { AdminBackLink } from './AdminHomePage'
import { adminErrorMessage } from './adminErrors'

/** Admins create support teams, rename or retire them, and choose their members. */
export function TeamsAdminPage() {
  const teams = useQuery(adminTeamsQuery)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const selected = teams.data?.find((team) => team.id === selectedId) ?? null

  return (
    <section className="grid gap-5">
      <div className="grid gap-1">
        <AdminBackLink />
        <h1 className="text-2xl font-semibold">Teams</h1>
        <p className="text-muted-foreground">Tickets are routed to teams; their members work the team’s queue and team leads approve its requests.</p>
      </div>
      {teams.isPending && <Skeleton className="h-48 w-full" />}
      {teams.isError && <QueryError error={teams.error} what="The teams" />}
      {teams.isSuccess && (
        <div className="grid gap-6 lg:grid-cols-[1fr_2fr]">
          <div className="grid content-start gap-4">
            <ul aria-label="Teams" className="grid gap-2">
              {teams.data.map((team) => (
                <li key={team.id}>
                  <button
                    type="button"
                    aria-current={team.id === selectedId ? 'true' : undefined}
                    onClick={() => setSelectedId(team.id)}
                    className={cn(
                      'flex w-full items-center justify-between rounded-md border p-3 text-left text-sm hover:bg-muted',
                      team.id === selectedId && 'border-primary bg-muted',
                    )}
                  >
                    <span className="font-medium">{team.name}</span>
                    {!team.active && <Badge variant="outline">Inactive</Badge>}
                  </button>
                </li>
              ))}
            </ul>
            <NewTeamForm onCreated={setSelectedId} />
          </div>
          {selected ? (
            <TeamDetails key={`${selected.id}-${selected.version}`} team={selected} />
          ) : (
            <p className="text-muted-foreground">Choose a team to edit it and its members.</p>
          )}
        </div>
      )}
    </section>
  )
}

function NewTeamForm({ onCreated }: { onCreated: (id: string) => void }) {
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [failure, setFailure] = useState<string | null>(null)
  const create = useMutation({ mutationFn: async () => unwrap(await api.POST('/api/admin/teams', { body: { name: name.trim() } })) })

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setFailure(null)
    try {
      const team = await create.mutateAsync()
      setName('')
      await queryClient.invalidateQueries({ queryKey: queryKeys.teams })
      onCreated(team.id)
    } catch (error) {
      setFailure(adminErrorMessage(error, 'The team'))
    }
  }

  return (
    <form onSubmit={(event) => void onSubmit(event)} className="grid gap-2 rounded-md border p-3">
      <Label htmlFor="new-team">New team</Label>
      <div className="flex gap-2">
        <Input id="new-team" required maxLength={100} value={name} onChange={(event) => setName(event.target.value)} />
        <Button type="submit" disabled={create.isPending}>
          Create
        </Button>
      </div>
      {failure && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{failure}</AlertDescription>
        </Alert>
      )}
    </form>
  )
}

function TeamDetails({ team }: { team: Team }) {
  const queryClient = useQueryClient()
  const [name, setName] = useState(team.name)
  const [description, setDescription] = useState(team.description ?? '')
  const [failure, setFailure] = useState<string | null>(null)
  const update = useMutation({
    mutationFn: async (body: { name?: string; description?: string; active?: boolean }) =>
      unwrap(await api.PATCH('/api/admin/teams/{id}', { params: { path: { id: team.id } }, body: { ...body, version: team.version } })),
  })

  async function save(body: { name?: string; description?: string; active?: boolean }) {
    setFailure(null)
    try {
      await update.mutateAsync(body)
    } catch (error) {
      setFailure(adminErrorMessage(error, 'The team'))
    }
    await queryClient.invalidateQueries({ queryKey: queryKeys.teams })
  }

  return (
    <div className="grid content-start gap-6 rounded-md border p-4">
      <form
        className="grid gap-3"
        onSubmit={(event) => {
          event.preventDefault()
          void save({ name: name.trim(), description: description.trim() })
        }}
      >
        <h2 className="text-lg font-semibold">{team.name}</h2>
        {failure && (
          <Alert variant="destructive" role="alert">
            <AlertDescription>{failure}</AlertDescription>
          </Alert>
        )}
        <div className="grid gap-1">
          <Label htmlFor="team-name">Name</Label>
          <Input id="team-name" required maxLength={100} value={name} onChange={(event) => setName(event.target.value)} />
        </div>
        <div className="grid gap-1">
          <Label htmlFor="team-description">Description</Label>
          <Textarea id="team-description" rows={2} maxLength={500} value={description} onChange={(event) => setDescription(event.target.value)} />
        </div>
        <div className="flex flex-wrap gap-2">
          <Button type="submit" disabled={update.isPending || (name.trim() === team.name && description.trim() === (team.description ?? ''))}>
            Save
          </Button>
          <Button type="button" variant="outline" disabled={update.isPending} onClick={() => void save({ active: !team.active })}>
            {team.active ? 'Deactivate team' : 'Reactivate team'}
          </Button>
        </div>
        {!team.active && <p className="text-sm text-muted-foreground">Inactive: no new tickets are routed here and nobody can join.</p>}
      </form>
      <Members team={team} />
    </div>
  )
}

function Members({ team }: { team: Team }) {
  const queryClient = useQueryClient()
  const members = useQuery(teamMembersQuery(team.id))
  // Only active support staff can join a team.
  const staff = useQuery(adminUsersQuery({ active: true, size: 100, page: 0 }))
  const [chosen, setChosen] = useState('')
  const [failure, setFailure] = useState<string | null>(null)
  const refresh = () => queryClient.invalidateQueries({ queryKey: queryKeys.teamMembers(team.id) })

  const add = useMutation({
    mutationFn: async (userId: string) => unwrap(await api.PUT('/api/admin/teams/{id}/members/{userId}', { params: { path: { id: team.id, userId } } })),
  })
  const remove = useMutation({
    mutationFn: async (userId: string) => unwrap(await api.DELETE('/api/admin/teams/{id}/members/{userId}', { params: { path: { id: team.id, userId } } })),
  })

  async function run(action: () => Promise<unknown>, what: string) {
    setFailure(null)
    try {
      await action()
      setChosen('')
    } catch (error) {
      setFailure(adminErrorMessage(error, what))
    }
    await refresh()
  }

  const memberIds = new Set(members.data?.map((member) => member.userId))
  const candidates = (staff.data?.content ?? []).filter((user) => user.role !== 'REQUESTER' && !memberIds.has(user.id))

  return (
    <section aria-labelledby="members-heading" className="grid gap-3">
      <h3 id="members-heading" className="font-semibold">
        Members
      </h3>
      {failure && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{failure}</AlertDescription>
        </Alert>
      )}
      {members.isPending && <Skeleton className="h-16 w-full" />}
      {members.isError && <QueryError error={members.error} what="The members" />}
      {members.isSuccess &&
        (members.data.length === 0 ? (
          <p className="text-sm text-muted-foreground">Nobody yet.</p>
        ) : (
          <ul aria-label={`Members of ${team.name}`} className="grid gap-2">
            {members.data.map((member) => (
              <li key={member.userId} className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-2 text-sm">
                <span>
                  <span className="font-medium">{member.displayName}</span>{' '}
                  <span className="text-muted-foreground">
                    · {ROLE_LABEL[member.role]}
                    {!member.active && ' · deactivated'}
                  </span>
                </span>
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={remove.isPending}
                  aria-label={`Remove ${member.displayName}`}
                  onClick={() => void run(() => remove.mutateAsync(member.userId), 'The change')}
                >
                  Remove
                </Button>
              </li>
            ))}
          </ul>
        ))}
      {team.active && (
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={(event) => {
            event.preventDefault()
            if (chosen) void run(() => add.mutateAsync(chosen), 'The change')
          }}
        >
          <div className="grid gap-1">
            <Label htmlFor="add-member">Add a member</Label>
            <NativeSelect id="add-member" value={chosen} onChange={(event) => setChosen(event.target.value)} className="w-64">
              <option value="">Choose someone…</option>
              {candidates.map((user) => (
                <option key={user.id} value={user.id}>
                  {user.displayName} ({ROLE_LABEL[user.role]})
                </option>
              ))}
            </NativeSelect>
          </div>
          <Button type="submit" variant="outline" disabled={!chosen || add.isPending}>
            Add
          </Button>
        </form>
      )}
    </section>
  )
}

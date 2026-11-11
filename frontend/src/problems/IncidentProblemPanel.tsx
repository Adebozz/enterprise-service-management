import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { api } from '@/api/client'
import { isApiError, unwrap } from '@/api/errors'
import { queryKeys, ticketListQuery } from '@/api/queries'
import type { Ticket } from '@/api/types'
import { NativeSelect } from '@/components/NativeSelect'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { StatusBadge } from '@/tickets/badges'
import { isClosed } from '@/tickets/labels'
import { isOpenProblem, useHandlesTicket } from './ownership'

/**
 * The problem behind an incident (staff only): its workaround where there is one, and for whoever
 * handles the incident, linking to an open problem or raising a new one from here.
 */
export function IncidentProblemPanel({ ticket }: { ticket: Ticket }) {
  const handles = useHandlesTicket(ticket)
  const linked = ticket.incident?.problem
  if (!linked && !handles) return null

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>Problem</h2>
        </CardTitle>
      </CardHeader>
      <CardContent className="grid gap-3 text-sm">
        {linked ? (
          <>
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-muted-foreground">{linked.reference}</span>
              <StatusBadge status={linked.status} />
            </div>
            <Link to={`/tickets/${linked.id}`} className="font-medium underline-offset-4 hover:underline">
              {linked.title}
            </Link>
            {linked.workaround && (
              <div className="grid gap-1 rounded-md bg-muted p-3">
                <span className="font-medium">Workaround</span>
                <p className="whitespace-pre-wrap">{linked.workaround}</p>
              </div>
            )}
            {handles && <Unlink ticket={ticket} />}
          </>
        ) : (
          <LinkForm ticket={ticket} />
        )}
      </CardContent>
    </Card>
  )
}

function Unlink({ ticket }: { ticket: Ticket }) {
  const queryClient = useQueryClient()
  const [message, setMessage] = useState<string | null>(null)
  const unlink = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.DELETE('/api/incidents/{id}/problem', {
          params: { path: { id: ticket.id }, query: { version: ticket.version } },
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.tickets }),
    onError: (error) => setMessage(failure(error)),
  })
  return (
    <>
      {message && <Message text={message} />}
      <Button variant="outline" size="sm" className="justify-self-start" disabled={unlink.isPending} onClick={() => unlink.mutate()}>
        Remove link
      </Button>
    </>
  )
}

function LinkForm({ ticket }: { ticket: Ticket }) {
  const queryClient = useQueryClient()
  const [problemId, setProblemId] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  const problems = useQuery(ticketListQuery({ view: 'ALL', type: ['PROBLEM'], open: true, size: 100, sort: ['updatedAt,desc'] }))
  const choices = problems.data?.content.filter((p) => isOpenProblem(p.status)) ?? []

  const link = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.PUT('/api/incidents/{id}/problem', {
          params: { path: { id: ticket.id } },
          body: { problemId, version: ticket.version },
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.tickets }),
    onError: (error) => setMessage(failure(error)),
  })

  return (
    <>
      <p className="text-muted-foreground">Is this caused by a known problem? Linking shows its workaround here.</p>
      {message && <Message text={message} />}
      {choices.length > 0 && (
        <form
          className="grid gap-2"
          onSubmit={(event) => {
            event.preventDefault()
            setMessage(null)
            if (problemId) link.mutate()
          }}
        >
          <Label htmlFor="problemId">Open problems</Label>
          <NativeSelect id="problemId" value={problemId} onChange={(event) => setProblemId(event.target.value)}>
            <option value="">Choose a problem</option>
            {choices.map((p) => (
              <option key={p.id} value={p.id}>
                {p.reference} · {p.title}
              </option>
            ))}
          </NativeSelect>
          <Button type="submit" size="sm" className="justify-self-start" disabled={!problemId || link.isPending}>
            Link to problem
          </Button>
        </form>
      )}
      {!isClosed(ticket.status) && (
        <Link to={`/problems/new?incident=${ticket.id}`} className="underline-offset-4 hover:underline">
          Raise a new problem from this incident
        </Link>
      )}
    </>
  )
}

function Message({ text }: { text: string }) {
  return (
    <Alert variant="destructive" role="alert">
      <AlertDescription>{text}</AlertDescription>
    </Alert>
  )
}

function failure(error: unknown): string {
  if (isApiError(error) && error.code === 'CONCURRENT_MODIFICATION') {
    return 'This incident changed meanwhile. It has been reloaded; please try again.'
  }
  return isApiError(error) ? error.message : 'That didn’t work. Please try again.'
}

import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router'
import { api } from '@/api/client'
import { isApiError, unwrap } from '@/api/errors'
import { queryKeys } from '@/api/queries'
import type { ProblemDetails, Ticket, UpdateProblemAnalysis } from '@/api/types'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { StatusBadge } from '@/tickets/badges'
import { formatDateTime } from '@/tickets/labels'
import { isOpenProblem, useHandlesTicket } from './ownership'

/** What the investigation found, editable by the team working on it while the problem is open. */
export function ProblemAnalysis({ ticket, problem }: { ticket: Ticket; problem: ProblemDetails }) {
  const handles = useHandlesTicket(ticket)
  const [editing, setEditing] = useState(false)
  const canEdit = handles && isOpenProblem(ticket.status)
  const missingForKnownError = ticket.status === 'INVESTIGATING' && (!problem.rootCause || !problem.workaround)

  return (
    <section aria-labelledby="analysis-heading" className="grid gap-4">
      <div className="flex items-center justify-between gap-2">
        <h2 id="analysis-heading" className="text-lg font-semibold">
          Analysis
        </h2>
        {canEdit && !editing && (
          <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
            Edit analysis
          </Button>
        )}
      </div>
      {editing ? (
        <AnalysisForm ticket={ticket} problem={problem} onDone={() => setEditing(false)} />
      ) : (
        <dl className="grid gap-4 text-sm">
          <Finding term="Root cause" value={problem.rootCause} empty="Not known yet." />
          <Finding
            term="Workaround"
            value={problem.workaround}
            empty="None yet. People affected have no way round it."
            note={problem.knownErrorAt ? `Known error since ${formatDateTime(problem.knownErrorAt)}` : undefined}
          />
          {problem.fixNotes && <Finding term="Permanent fix" value={problem.fixNotes} empty="" />}
        </dl>
      )}
      {canEdit && missingForKnownError && !editing && (
        <p className="text-sm text-muted-foreground">Record the root cause and a workaround to mark this a known error.</p>
      )}
    </section>
  )
}

function Finding({ term, value, empty, note }: { term: string; value?: string | null; empty: string; note?: string }) {
  return (
    <div className="grid gap-1">
      <dt className="font-medium">{term}</dt>
      <dd className={value ? 'whitespace-pre-wrap' : 'text-muted-foreground'}>{value ?? empty}</dd>
      {note && <dd className="text-xs text-muted-foreground">{note}</dd>}
    </div>
  )
}

interface Form {
  rootCause: string
  workaround: string
}

function AnalysisForm({ ticket, problem, onDone }: { ticket: Ticket; problem: ProblemDetails; onDone: () => void }) {
  const queryClient = useQueryClient()
  const [message, setMessage] = useState<string | null>(null)
  const { register, handleSubmit, formState } = useForm<Form>({
    defaultValues: { rootCause: problem.rootCause ?? '', workaround: problem.workaround ?? '' },
  })

  const save = useMutation({
    mutationFn: async (body: UpdateProblemAnalysis) =>
      unwrap(await api.PUT('/api/problems/{id}/analysis', { params: { path: { id: ticket.id } }, body })),
  })

  const onSubmit = handleSubmit(async (form) => {
    setMessage(null)
    try {
      await save.mutateAsync({ rootCause: form.rootCause, workaround: form.workaround, version: ticket.version })
      await queryClient.invalidateQueries({ queryKey: queryKeys.tickets })
      onDone()
    } catch (error) {
      if (isApiError(error) && error.code === 'CONCURRENT_MODIFICATION') {
        setMessage('Someone else changed this problem meanwhile. It has been reloaded; check it and save again.')
        await queryClient.invalidateQueries({ queryKey: queryKeys.ticket(ticket.id) })
      } else {
        setMessage(isApiError(error) ? error.message : 'The analysis couldn’t be saved. Please try again.')
      }
    }
  })

  return (
    <form onSubmit={onSubmit} noValidate className="grid gap-4">
      {message && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{message}</AlertDescription>
        </Alert>
      )}
      <div className="grid gap-2">
        <Label htmlFor="rootCause">Root cause</Label>
        <Textarea id="rootCause" rows={4} placeholder="Why do the incidents happen?" {...register('rootCause')} />
      </div>
      <div className="grid gap-2">
        <Label htmlFor="workaround">Workaround</Label>
        <Textarea id="workaround" rows={4} placeholder="How can people carry on until it is fixed?" {...register('workaround')} />
      </div>
      <div className="flex justify-end gap-2">
        <Button type="button" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
        <Button type="submit" disabled={formState.isSubmitting}>
          {formState.isSubmitting ? 'Saving…' : 'Save analysis'}
        </Button>
      </div>
    </form>
  )
}

/** The incidents this problem explains, oldest first. */
export function LinkedIncidents({ problem }: { problem: ProblemDetails }) {
  return (
    <section aria-labelledby="incidents-heading" className="grid gap-3">
      <h2 id="incidents-heading" className="text-lg font-semibold">
        Incidents it explains ({problem.incidents.length})
      </h2>
      {problem.incidents.length === 0 ? (
        <p className="text-sm text-muted-foreground">None linked yet. Link incidents from their own page.</p>
      ) : (
        <ul className="grid gap-2">
          {problem.incidents.map((incident) => (
            <li key={incident.id} className="flex flex-wrap items-center gap-2 text-sm">
              <span className="font-mono text-muted-foreground">{incident.reference}</span>
              <Link to={`/tickets/${incident.id}`} className="underline-offset-4 hover:underline">
                {incident.title}
              </Link>
              <StatusBadge status={incident.status} />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

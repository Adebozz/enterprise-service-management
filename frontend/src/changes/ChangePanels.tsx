import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Link } from 'react-router'
import { z } from 'zod'
import { api } from '@/api/client'
import { isApiError, unwrap } from '@/api/errors'
import { queryKeys } from '@/api/queries'
import type { ChangeDetails, ChangeOutcome, LinkedTicket, RecordChangeOutcome, Ticket, UpdateChangePlan } from '@/api/types'
import { FieldError } from '@/components/FieldError'
import { NativeSelect } from '@/components/NativeSelect'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { isOpenProblem, useHandlesTicket } from '@/problems/ownership'
import { StatusBadge } from '@/tickets/badges'
import { CHANGE_TYPE_LABEL, OUTCOME_LABEL, RISK_LABEL, formatDateTime } from '@/tickets/labels'
import { PlanFields } from './PlanFields'
import { checkPlan, planShape, type PlanForm } from './planSchema'
import { formatWindow, fromLocalInput, isPlanEditable, toLocalInput } from './window'

/** The plan: editable by the team carrying the change out until it is submitted, then fixed. */
export function ChangePlan({ ticket, change }: { ticket: Ticket; change: ChangeDetails }) {
  const handles = useHandlesTicket(ticket)
  const [editing, setEditing] = useState(false)
  const canEdit = handles && isPlanEditable(ticket.status)

  return (
    <section aria-labelledby="plan-heading" className="grid gap-4">
      <div className="flex items-center justify-between gap-2">
        <h2 id="plan-heading" className="text-lg font-semibold">
          Plan
        </h2>
        {canEdit && !editing && (
          <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
            Edit plan
          </Button>
        )}
      </div>
      {editing ? (
        <PlanEditor ticket={ticket} change={change} onDone={() => setEditing(false)} />
      ) : (
        <>
          <div className="flex flex-wrap gap-2 text-sm">
            <Badge variant="outline">{CHANGE_TYPE_LABEL[change.changeType]} change</Badge>
            <Badge variant="outline">{RISK_LABEL[change.risk]} risk</Badge>
          </div>
          <dl className="grid gap-4 text-sm">
            <Entry
              term="Window"
              value={change.plannedStart && change.plannedEnd ? formatWindow(change.plannedStart, change.plannedEnd) : null}
              empty="Not planned yet."
            />
            <Entry term="Implementation plan" value={change.implementationPlan} empty="Not written yet." />
            <Entry term="Backout plan" value={change.backoutPlan} empty="Not written yet." />
            {change.problem && (
              <div className="grid gap-1">
                <dt className="font-medium">Fixes problem</dt>
                <dd>
                  <TicketLink ticket={change.problem} />
                </dd>
              </div>
            )}
          </dl>
        </>
      )}
      {change.clashes.length > 0 && <Clashes clashes={change.clashes} />}
    </section>
  )
}

function Clashes({ clashes }: { clashes: LinkedTicket[] }) {
  return (
    <Alert role="status">
      <AlertDescription className="grid gap-2">
        <p>
          {clashes.length === 1 ? 'Another change is' : `${clashes.length} other changes are`} scheduled during this window. Check
          they won’t interfere.
        </p>
        <ul className="grid gap-1">
          {clashes.map((clash) => (
            <li key={clash.id}>
              <TicketLink ticket={clash} />
            </li>
          ))}
        </ul>
      </AlertDescription>
    </Alert>
  )
}

function TicketLink({ ticket }: { ticket: LinkedTicket }) {
  return (
    <span className="flex flex-wrap items-center gap-2">
      <span className="font-mono text-muted-foreground">{ticket.reference}</span>
      <Link to={`/tickets/${ticket.id}`} className="underline-offset-4 hover:underline">
        {ticket.title}
      </Link>
      <StatusBadge status={ticket.status} />
    </span>
  )
}

function Entry({ term, value, empty }: { term: string; value?: string | null; empty: string }) {
  return (
    <div className="grid gap-1">
      <dt className="font-medium">{term}</dt>
      <dd className={value ? 'whitespace-pre-wrap' : 'text-muted-foreground'}>{value ?? empty}</dd>
    </div>
  )
}

const planSchema = z.object(planShape).superRefine(checkPlan)

function PlanEditor({ ticket, change, onDone }: { ticket: Ticket; change: ChangeDetails; onDone: () => void }) {
  const queryClient = useQueryClient()
  const [message, setMessage] = useState<string | null>(null)
  const { register, handleSubmit, control, formState } = useForm<PlanForm>({
    resolver: zodResolver(planSchema),
    defaultValues: {
      changeType: change.changeType,
      risk: change.risk,
      implementationPlan: change.implementationPlan ?? '',
      backoutPlan: change.backoutPlan ?? '',
      plannedStart: toLocalInput(change.plannedStart),
      plannedEnd: toLocalInput(change.plannedEnd),
    },
  })
  const changeType = useWatch({ control, name: 'changeType' })

  const save = useMutation({
    mutationFn: async (body: UpdateChangePlan) =>
      unwrap(await api.PUT('/api/changes/{id}/plan', { params: { path: { id: ticket.id } }, body })),
  })

  const onSubmit = handleSubmit(async (form) => {
    setMessage(null)
    try {
      await save.mutateAsync({
        changeType: form.changeType,
        risk: form.risk,
        implementationPlan: form.implementationPlan,
        backoutPlan: form.backoutPlan,
        plannedStart: fromLocalInput(form.plannedStart),
        plannedEnd: fromLocalInput(form.plannedEnd),
        version: ticket.version,
      })
      await queryClient.invalidateQueries({ queryKey: queryKeys.tickets })
      onDone()
    } catch (error) {
      setMessage(await failure(error, queryClient, ticket.id))
    }
  })

  return (
    <form onSubmit={onSubmit} noValidate className="grid gap-4">
      {message && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{message}</AlertDescription>
        </Alert>
      )}
      <PlanFields register={register} errors={formState.errors} changeType={changeType} />
      <div className="flex justify-end gap-2">
        <Button type="button" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
        <Button type="submit" disabled={formState.isSubmitting}>
          {formState.isSubmitting ? 'Saving…' : 'Save plan'}
        </Button>
      </div>
    </form>
  )
}

const OUTCOMES = Object.keys(OUTCOME_LABEL) as [ChangeOutcome, ...ChangeOutcome[]]

const reviewSchema = z.object({
  outcome: z.enum(OUTCOMES, { error: 'Choose how it went' }),
  notes: z.string().trim().min(1, { error: 'Say what happened' }).max(10_000, { error: 'Keep it under 10,000 characters' }),
})
type ReviewForm = z.infer<typeof reviewSchema>

/** How the change went: recorded by its team once work has started, and needed before closing. */
export function ChangeReview({ ticket, change }: { ticket: Ticket; change: ChangeDetails }) {
  const handles = useHandlesTicket(ticket)
  const [editing, setEditing] = useState(false)
  const canRecord = handles && (ticket.status === 'IMPLEMENTING' || ticket.status === 'REVIEW')
  if (!change.outcome && !canRecord) return null

  return (
    <section aria-labelledby="review-heading" className="grid gap-4">
      <div className="flex items-center justify-between gap-2">
        <h2 id="review-heading" className="text-lg font-semibold">
          Review
        </h2>
        {canRecord && change.outcome && !editing && (
          <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
            Edit outcome
          </Button>
        )}
      </div>
      {change.implementedAt && <p className="text-sm text-muted-foreground">Implementation started {formatDateTime(change.implementedAt)}.</p>}
      {canRecord && (editing || !change.outcome) ? (
        <ReviewEditor ticket={ticket} change={change} onDone={() => setEditing(false)} />
      ) : (
        change.outcome && (
          <dl className="grid gap-4 text-sm">
            <Entry term="Outcome" value={OUTCOME_LABEL[change.outcome]} empty="" />
            <Entry term="What happened" value={change.reviewNotes} empty="" />
          </dl>
        )
      )}
    </section>
  )
}

function ReviewEditor({ ticket, change, onDone }: { ticket: Ticket; change: ChangeDetails; onDone: () => void }) {
  const queryClient = useQueryClient()
  const [message, setMessage] = useState<string | null>(null)
  const { register, handleSubmit, formState } = useForm<ReviewForm>({
    resolver: zodResolver(reviewSchema),
    defaultValues: { outcome: change.outcome ?? undefined, notes: change.reviewNotes ?? '' },
  })
  const { errors } = formState

  const save = useMutation({
    mutationFn: async (body: RecordChangeOutcome) =>
      unwrap(await api.PUT('/api/changes/{id}/outcome', { params: { path: { id: ticket.id } }, body })),
  })

  const onSubmit = handleSubmit(async (form) => {
    setMessage(null)
    try {
      await save.mutateAsync({ outcome: form.outcome, notes: form.notes, version: ticket.version })
      await queryClient.invalidateQueries({ queryKey: queryKeys.tickets })
      onDone()
    } catch (error) {
      setMessage(await failure(error, queryClient, ticket.id))
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
        <Label htmlFor="outcome">How did it go?</Label>
        <NativeSelect
          id="outcome"
          aria-invalid={errors.outcome ? true : undefined}
          aria-describedby={errors.outcome ? 'outcome-error' : undefined}
          {...register('outcome')}
        >
          <option value="">Select the outcome</option>
          {OUTCOMES.map((outcome) => (
            <option key={outcome} value={outcome}>
              {OUTCOME_LABEL[outcome]}
            </option>
          ))}
        </NativeSelect>
        <FieldError id="outcome-error" message={errors.outcome?.message} />
      </div>
      <div className="grid gap-2">
        <Label htmlFor="reviewNotes">What happened</Label>
        <Textarea
          id="reviewNotes"
          rows={4}
          aria-invalid={errors.notes ? true : undefined}
          aria-describedby={errors.notes ? 'reviewNotes-error' : undefined}
          {...register('notes')}
        />
        <FieldError id="reviewNotes-error" message={errors.notes?.message} />
      </div>
      <div className="flex justify-end gap-2">
        {change.outcome && (
          <Button type="button" variant="ghost" onClick={onDone}>
            Cancel
          </Button>
        )}
        <Button type="submit" disabled={formState.isSubmitting}>
          {formState.isSubmitting ? 'Saving…' : 'Save outcome'}
        </Button>
      </div>
    </form>
  )
}

async function failure(error: unknown, queryClient: ReturnType<typeof useQueryClient>, ticketId: string): Promise<string> {
  if (isApiError(error) && error.code === 'CONCURRENT_MODIFICATION') {
    await queryClient.invalidateQueries({ queryKey: queryKeys.ticket(ticketId) })
    return 'Someone else changed this change meanwhile. It has been reloaded; check it and save again.'
  }
  return isApiError(error) ? error.message : 'That couldn’t be saved. Please try again.'
}

/** The changes raised to fix a problem, oldest first, with a way to raise another. */
export function ProblemChanges({ ticket }: { ticket: Ticket }) {
  const changes = ticket.problem?.changes ?? []
  const open = isOpenProblem(ticket.status)
  return (
    <section aria-labelledby="changes-heading" className="grid gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="changes-heading" className="text-lg font-semibold">
          Changes to fix it ({changes.length})
        </h2>
        {open && (
          <Button asChild variant="outline" size="sm">
            <Link to={`/changes/new?problem=${ticket.id}`}>Raise a change to fix this</Link>
          </Button>
        )}
      </div>
      {changes.length === 0 ? (
        <p className="text-sm text-muted-foreground">None yet.</p>
      ) : (
        <ul className="grid gap-2 text-sm">
          {changes.map((change) => (
            <li key={change.id}>
              <TicketLink ticket={change} />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { api } from '@/api/client'
import { isApiError, unwrap } from '@/api/errors'
import { queryKeys } from '@/api/queries'
import type { AvailableTransition, Ticket, TransitionRequest } from '@/api/types'
import { FieldError } from '@/components/FieldError'
import { NativeSelect } from '@/components/NativeSelect'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { RESOLUTION_CODES, RESOLUTION_LABEL } from './labels'

type Requirement = AvailableTransition['requirements'][number]

interface ActionForm {
  reason: string | undefined
  resolutionCode: string | undefined
  notes: string | undefined
}

/** Builds validation for exactly the inputs this move requires (as listed by the server). */
function schemaFor(requirements: Requirement[]) {
  const needs = (r: Requirement) => requirements.includes(r)
  const optional = z.string().optional()
  return z.object({
    reason: needs('REASON')
      ? z.string().trim().min(1, { error: 'Please give a reason' }).max(1000, { error: 'Keep it under 1000 characters' })
      : optional,
    resolutionCode: needs('RESOLUTION') ? z.enum(RESOLUTION_CODES, { error: 'Choose how it was resolved' }) : optional,
    notes:
      needs('RESOLUTION') || needs('FULFILMENT_NOTES')
        ? z.string().trim().min(1, { error: 'Describe what was done' }).max(10_000, { error: 'Keep it under 10,000 characters' })
        : optional,
  })
}

/** Moves whose inputs are typed into a dialog; ASSIGNEE is about ticket state, never typed. */
const needsDialog = (t: AvailableTransition) => t.requirements.some((r) => r !== 'ASSIGNEE')

/**
 * One button per move the SERVER says this user can make now, with a dialog that collects whatever
 * that move requires. The frontend never encodes the workflow, so it can't drift from the backend.
 */
export function TicketActions({ ticket, transitions }: { ticket: Ticket; transitions: AvailableTransition[] }) {
  const queryClient = useQueryClient()
  const [pending, setPending] = useState<AvailableTransition | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const transition = useMutation({
    mutationFn: async (body: TransitionRequest) =>
      unwrap(await api.POST('/api/tickets/{id}/transitions', { params: { path: { id: ticket.id } }, body })),
    onSuccess: async (updated) => {
      queryClient.setQueryData(queryKeys.ticket(ticket.id), updated)
      await queryClient.invalidateQueries({ queryKey: queryKeys.tickets })
    },
  })

  const run = async (target: AvailableTransition, input: Partial<ActionForm> = {}) => {
    setMessage(null)
    try {
      await transition.mutateAsync({
        targetStatus: target.targetStatus,
        version: ticket.version,
        reason: input.reason || undefined,
        resolutionCode: (input.resolutionCode || undefined) as TransitionRequest['resolutionCode'],
        notes: input.notes || undefined,
      })
      setPending(null)
    } catch (error) {
      setPending(null)
      if (isApiError(error) && error.code === 'CONCURRENT_MODIFICATION') {
        setMessage('Someone else updated this ticket while you had it open. It has been reloaded; please check it and try again.')
        await queryClient.invalidateQueries({ queryKey: queryKeys.ticket(ticket.id) })
      } else {
        setMessage(isApiError(error) ? error.message : 'That didn’t work. Please try again.')
      }
    }
  }

  if (transitions.length === 0 && !message) return null

  return (
    <div className="grid gap-3">
      {message && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{message}</AlertDescription>
        </Alert>
      )}
      <div className="flex flex-wrap gap-2">
        {transitions.map((t) => (
          <Button
            key={t.targetStatus}
            variant={t.targetStatus === 'CANCELLED' ? 'outline' : 'default'}
            disabled={transition.isPending}
            onClick={() => (needsDialog(t) ? setPending(t) : void run(t))}
          >
            {t.label}
          </Button>
        ))}
      </div>
      {pending && <ActionDialog transition={pending} onCancel={() => setPending(null)} onConfirm={(input) => run(pending, input)} />}
    </div>
  )
}

function ActionDialog({
  transition,
  onCancel,
  onConfirm,
}: {
  transition: AvailableTransition
  onCancel: () => void
  onConfirm: (input: ActionForm) => Promise<void>
}) {
  const needs = (r: Requirement) => transition.requirements.includes(r)
  const { register, handleSubmit, formState } = useForm<ActionForm>({
    resolver: zodResolver(schemaFor(transition.requirements)),
    defaultValues: { reason: '', resolutionCode: '', notes: '' },
  })
  const { errors } = formState

  return (
    <Dialog open onOpenChange={(open) => !open && onCancel()}>
      <DialogContent>
        <form onSubmit={handleSubmit(onConfirm)} noValidate className="grid gap-4">
          <DialogHeader>
            <DialogTitle>{transition.label}</DialogTitle>
            <DialogDescription>
              {needs('REASON')
                ? 'Your reason is added to the conversation, so everyone involved sees it.'
                : 'This is shown to the requester on the ticket.'}
            </DialogDescription>
          </DialogHeader>
          {needs('REASON') && (
            <div className="grid gap-2">
              <Label htmlFor="reason">Reason</Label>
              <Textarea id="reason" rows={4} aria-invalid={errors.reason ? true : undefined} aria-describedby={errors.reason ? 'reason-error' : undefined} {...register('reason')} />
              <FieldError id="reason-error" message={errors.reason?.message} />
            </div>
          )}
          {needs('RESOLUTION') && (
            <div className="grid gap-2">
              <Label htmlFor="resolutionCode">Resolution</Label>
              <NativeSelect
                id="resolutionCode"
                aria-invalid={errors.resolutionCode ? true : undefined}
                aria-describedby={errors.resolutionCode ? 'resolutionCode-error' : undefined}
                {...register('resolutionCode')}
              >
                <option value="">Select how it was resolved</option>
                {RESOLUTION_CODES.map((code) => (
                  <option key={code} value={code}>
                    {RESOLUTION_LABEL[code]}
                  </option>
                ))}
              </NativeSelect>
              <FieldError id="resolutionCode-error" message={errors.resolutionCode?.message} />
            </div>
          )}
          {(needs('RESOLUTION') || needs('FULFILMENT_NOTES')) && (
            <div className="grid gap-2">
              <Label htmlFor="notes">{needs('RESOLUTION') ? 'Resolution notes' : 'What was delivered?'}</Label>
              <Textarea id="notes" rows={4} aria-invalid={errors.notes ? true : undefined} aria-describedby={errors.notes ? 'notes-error' : undefined} {...register('notes')} />
              <FieldError id="notes-error" message={errors.notes?.message} />
            </div>
          )}
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onCancel}>
              Back
            </Button>
            <Button type="submit" disabled={formState.isSubmitting}>
              {transition.label}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

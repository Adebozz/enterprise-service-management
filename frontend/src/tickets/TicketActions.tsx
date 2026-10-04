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
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'

/**
 * Requirements this screen can collect. Moves needing a resolution or fulfilment notes belong to
 * support staff and get their inputs in the agent portal (M11); until then they aren't offered here
 * rather than shown as buttons that can only fail.
 */
const SUPPORTED = new Set<AvailableTransition['requirements'][number]>(['REASON'])

const reasonSchema = z.object({
  reason: z.string().trim().min(1, { error: 'Please give a reason' }).max(1000, { error: 'Keep it under 1000 characters' }),
})

/**
 * One button per move the SERVER says this user can make now. The frontend never encodes the
 * workflow, so it can't drift from the backend's rules.
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

  const run = async (target: AvailableTransition, reason?: string) => {
    setMessage(null)
    try {
      await transition.mutateAsync({ targetStatus: target.targetStatus, version: ticket.version, reason })
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

  const available = transitions.filter((t) => t.requirements.every((r) => SUPPORTED.has(r)))
  if (available.length === 0 && !message) return null

  return (
    <div className="grid gap-3">
      {message && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{message}</AlertDescription>
        </Alert>
      )}
      <div className="flex flex-wrap gap-2">
        {available.map((t) => (
          <Button
            key={t.targetStatus}
            variant={t.targetStatus === 'CANCELLED' ? 'outline' : 'default'}
            disabled={transition.isPending}
            onClick={() => (t.requirements.includes('REASON') ? setPending(t) : void run(t))}
          >
            {t.label}
          </Button>
        ))}
      </div>
      {pending && <ReasonDialog transition={pending} onCancel={() => setPending(null)} onConfirm={(reason) => run(pending, reason)} />}
    </div>
  )
}

function ReasonDialog({
  transition,
  onCancel,
  onConfirm,
}: {
  transition: AvailableTransition
  onCancel: () => void
  onConfirm: (reason: string) => Promise<void>
}) {
  const { register, handleSubmit, formState } = useForm<z.infer<typeof reasonSchema>>({
    resolver: zodResolver(reasonSchema),
    defaultValues: { reason: '' },
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onCancel()}>
      <DialogContent>
        <form onSubmit={handleSubmit(({ reason }) => onConfirm(reason))} noValidate className="grid gap-4">
          <DialogHeader>
            <DialogTitle>{transition.label}</DialogTitle>
            <DialogDescription>Your reason is added to the conversation, so the support team sees it.</DialogDescription>
          </DialogHeader>
          <div className="grid gap-2">
            <Label htmlFor="reason">Reason</Label>
            <Textarea
              id="reason"
              rows={4}
              aria-invalid={formState.errors.reason ? true : undefined}
              aria-describedby={formState.errors.reason ? 'reason-error' : undefined}
              {...register('reason')}
            />
            <FieldError id="reason-error" message={formState.errors.reason?.message} />
          </div>
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

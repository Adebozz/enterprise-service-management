import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { api } from '@/api/client'
import { isApiError, unwrap } from '@/api/errors'
import { commentsQuery, queryKeys } from '@/api/queries'
import { FieldError } from '@/components/FieldError'
import { QueryError } from '@/components/QueryError'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { Textarea } from '@/components/ui/textarea'
import { formatDateTime, statusLabel } from './labels'

const replySchema = z.object({
  body: z.string().trim().min(1, { error: 'Write a message first' }).max(10_000, { error: 'Keep it under 10,000 characters' }),
})

export function Conversation({ ticketId, closed }: { ticketId: string; closed: boolean }) {
  const comments = useQuery(commentsQuery(ticketId))

  return (
    <section aria-labelledby="conversation-heading" className="grid gap-4">
      <h2 id="conversation-heading" className="text-lg font-semibold">
        Conversation
      </h2>
      {comments.isPending && <Skeleton className="h-16 w-full" />}
      {comments.isError && <QueryError error={comments.error} what="The conversation" />}
      {comments.isSuccess &&
        (comments.data.length === 0 ? (
          <p className="text-muted-foreground">No messages yet.</p>
        ) : (
          <ol className="grid gap-3">
            {comments.data.map((comment) => (
              <li key={comment.id} className="rounded-md border p-4">
                <div className="flex flex-wrap items-baseline justify-between gap-2 text-sm">
                  <span className="font-medium">{comment.author.name}</span>
                  <time dateTime={comment.createdAt} className="text-muted-foreground">
                    {formatDateTime(comment.createdAt)}
                  </time>
                </div>
                {comment.relatedStatus && (
                  <p className="mt-1 text-xs text-muted-foreground">Set the status to {statusLabel(comment.relatedStatus).toLowerCase()}</p>
                )}
                <p className="mt-2 whitespace-pre-wrap">{comment.body}</p>
              </li>
            ))}
          </ol>
        ))}
      {closed ? (
        <p className="text-sm text-muted-foreground">This ticket is closed, so it no longer accepts messages.</p>
      ) : (
        <ReplyForm ticketId={ticketId} />
      )}
    </section>
  )
}

function ReplyForm({ ticketId }: { ticketId: string }) {
  const queryClient = useQueryClient()
  const [failure, setFailure] = useState<string | null>(null)
  const { register, handleSubmit, reset, formState } = useForm<z.infer<typeof replySchema>>({
    resolver: zodResolver(replySchema),
    defaultValues: { body: '' },
  })

  const send = useMutation({
    mutationFn: async (body: string) =>
      unwrap(await api.POST('/api/tickets/{id}/comments', { params: { path: { id: ticketId } }, body: { body, visibility: 'PUBLIC' } })),
    onSuccess: async () => {
      reset()
      // The ticket may change too (e.g. support's first reply records the first response time).
      await queryClient.invalidateQueries({ queryKey: queryKeys.ticket(ticketId) })
    },
  })

  return (
    <form
      noValidate
      className="grid gap-2"
      onSubmit={handleSubmit(async ({ body }) => {
        setFailure(null)
        try {
          await send.mutateAsync(body)
        } catch (error) {
          setFailure(
            isApiError(error) && error.code === 'TICKET_CLOSED'
              ? 'This ticket has just been closed, so it no longer accepts messages.'
              : 'Your message wasn’t sent. Please try again.',
          )
        }
      })}
    >
      {failure && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{failure}</AlertDescription>
        </Alert>
      )}
      <Label htmlFor="reply">Add a message</Label>
      <Textarea
        id="reply"
        rows={3}
        aria-invalid={formState.errors.body ? true : undefined}
        aria-describedby={formState.errors.body ? 'reply-error' : undefined}
        {...register('body')}
      />
      <FieldError id="reply-error" message={formState.errors.body?.message} />
      <div className="flex justify-end">
        <Button type="submit" disabled={formState.isSubmitting}>
          {formState.isSubmitting ? 'Sending…' : 'Send'}
        </Button>
      </div>
    </form>
  )
}

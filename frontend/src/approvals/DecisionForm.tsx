import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useId, useState } from 'react'
import { api } from '@/api/client'
import { isApiError, unwrap } from '@/api/errors'
import { queryKeys } from '@/api/queries'
import type { ApprovalDecision } from '@/api/types'
import { FieldError } from '@/components/FieldError'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'

/**
 * Approve or reject one request. The comment is optional to approve and required to reject; the
 * requester sees it in the conversation either way.
 */
export function DecisionForm({ approvalId, version, onDecided }: { approvalId: string; version: number; onDecided?: () => void }) {
  const queryClient = useQueryClient()
  const id = useId()
  const [comment, setComment] = useState('')
  const [commentError, setCommentError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)

  const decide = useMutation({
    mutationFn: async (body: ApprovalDecision) =>
      unwrap(await api.POST('/api/approvals/{id}/decision', { params: { path: { id: approvalId } }, body })),
    onSettled: () => queryClient.invalidateQueries({ queryKey: queryKeys.tickets }),
  })

  const submit = async (decision: ApprovalDecision['decision']) => {
    setFormError(null)
    setCommentError(null)
    if (decision === 'REJECT' && comment.trim() === '') {
      setCommentError('Say why, so the requester knows what to do instead')
      return
    }
    try {
      await decide.mutateAsync({ decision, comment: comment.trim() || undefined, version })
      onDecided?.()
    } catch (error) {
      if (isApiError(error) && (error.code === 'CONCURRENT_MODIFICATION' || error.code === 'APPROVAL_ALREADY_DECIDED')) {
        setFormError('This request changed meanwhile (perhaps someone else decided it). It has been reloaded.')
      } else {
        setFormError(isApiError(error) ? error.message : 'Something went wrong. Please try again.')
      }
    }
  }

  return (
    <div className="grid gap-3">
      {formError && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{formError}</AlertDescription>
        </Alert>
      )}
      <div className="grid gap-2">
        <Label htmlFor={`${id}-comment`}>Comment (added to the ticket’s conversation)</Label>
        <Textarea
          id={`${id}-comment`}
          rows={2}
          value={comment}
          maxLength={2000}
          onChange={(e) => setComment(e.target.value)}
          aria-invalid={commentError ? true : undefined}
          aria-describedby={commentError ? `${id}-comment-error` : `${id}-comment-help`}
        />
        {commentError ? (
          <FieldError id={`${id}-comment-error`} message={commentError} />
        ) : (
          <p id={`${id}-comment-help`} className="text-sm text-muted-foreground">
            Optional to approve, required to reject.
          </p>
        )}
      </div>
      <div className="flex gap-2">
        <Button type="button" disabled={decide.isPending} onClick={() => void submit('APPROVE')}>
          Approve
        </Button>
        <Button type="button" variant="outline" disabled={decide.isPending} onClick={() => void submit('REJECT')}>
          Reject
        </Button>
      </div>
    </div>
  )
}

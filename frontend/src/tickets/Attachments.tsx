import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { api } from '@/api/client'
import { isApiError, unwrap } from '@/api/errors'
import { ACCEPTED_EXTENSIONS, MAX_ATTACHMENT_BYTES, downloadAttachment, formatBytes, uploadAttachment } from '@/api/files'
import { attachmentsQuery, queryKeys } from '@/api/queries'
import type { Attachment, CommentVisibility } from '@/api/types'
import { QueryError } from '@/components/QueryError'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime } from './labels'

const UPLOAD_ERRORS = {
  ATTACHMENT_TOO_LARGE: 'Files can be up to 10 MB.',
  ATTACHMENT_TYPE_NOT_ALLOWED: `That file can’t be attached: its type isn’t accepted, or its content doesn’t match its extension. Accepted: ${ACCEPTED_EXTENSIONS.join(', ')}.`,
  ATTACHMENT_EMPTY: 'That file is empty.',
  ATTACHMENT_LIMIT_REACHED: 'This ticket already has 20 files. Remove one first.',
  TICKET_CLOSED: 'This ticket has been closed, so it no longer accepts files.',
  COMMENT_NOT_PERMITTED: 'Only support staff handling this ticket can add internal files.',
} as const satisfies Record<string, string>

function uploadError(code: string): string | undefined {
  return (UPLOAD_ERRORS as Record<string, string>)[code]
}

/**
 * Files on the ticket. Like the conversation, internal files are for support staff: the API never
 * returns them to anyone else, so `canAddInternal` only decides whether to offer the choice.
 */
export function Attachments({
  ticketId,
  closed,
  canAddInternal = false,
  staffOnly = false,
}: {
  ticketId: string
  closed: boolean
  canAddInternal?: boolean
  staffOnly?: boolean
}) {
  const attachments = useQuery(attachmentsQuery(ticketId))
  const [failure, setFailure] = useState<string | null>(null)

  return (
    <section aria-labelledby="attachments-heading" className="grid gap-3">
      <h2 id="attachments-heading" className="text-lg font-semibold">
        Files
      </h2>
      {failure && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{failure}</AlertDescription>
        </Alert>
      )}
      {attachments.isPending && <Skeleton className="h-10 w-full" />}
      {attachments.isError && <QueryError error={attachments.error} what="The files" />}
      {attachments.isSuccess &&
        (attachments.data.length === 0 ? (
          <p className="text-muted-foreground">No files yet.</p>
        ) : (
          <ul className="grid gap-2" aria-label="Attached files">
            {attachments.data.map((attachment) => (
              <AttachmentRow key={attachment.id} ticketId={ticketId} attachment={attachment} onFailure={setFailure} />
            ))}
          </ul>
        ))}
      {!closed && <UploadForm ticketId={ticketId} canAddInternal={canAddInternal} staffOnly={staffOnly} onFailure={setFailure} />}
    </section>
  )
}

function AttachmentRow({ ticketId, attachment, onFailure }: { ticketId: string; attachment: Attachment; onFailure: (message: string | null) => void }) {
  const queryClient = useQueryClient()
  const remove = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.DELETE('/api/tickets/{id}/attachments/{attachmentId}', {
          params: { path: { id: ticketId, attachmentId: attachment.id } },
        }),
      ),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: queryKeys.attachments(ticketId),
      }),
  })
  const internal = attachment.visibility === 'INTERNAL'

  return (
    <li className={`flex flex-wrap items-center justify-between gap-2 rounded-md border p-3 text-sm ${internal ? 'border-amber-300 bg-amber-50' : ''}`}>
      <div className="grid gap-0.5">
        <button
          type="button"
          className="text-left font-medium underline-offset-4 hover:underline"
          onClick={async () => {
            onFailure(null)
            try {
              await downloadAttachment(ticketId, attachment)
            } catch {
              onFailure(`${attachment.fileName} couldn’t be downloaded. Please try again.`)
            }
          }}
        >
          {attachment.fileName}
        </button>
        <span className="text-muted-foreground">
          {formatBytes(attachment.sizeBytes)} · {attachment.uploadedBy.name} ·{' '}
          <time dateTime={attachment.createdAt}>{formatDateTime(attachment.createdAt)}</time>
          {internal && <span className="text-amber-900"> · Internal: support staff only</span>}
        </span>
      </div>
      {attachment.canDelete && (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={remove.isPending}
          aria-label={`Remove ${attachment.fileName}`}
          onClick={async () => {
            onFailure(null)
            try {
              await remove.mutateAsync()
            } catch (error) {
              onFailure(
                isApiError(error) && error.code === 'TICKET_CLOSED' ? UPLOAD_ERRORS.TICKET_CLOSED : `${attachment.fileName} wasn’t removed. Please try again.`,
              )
            }
          }}
        >
          Remove
        </Button>
      )}
    </li>
  )
}

function UploadForm({
  ticketId,
  canAddInternal,
  staffOnly,
  onFailure,
}: {
  ticketId: string
  canAddInternal: boolean
  staffOnly: boolean
  onFailure: (message: string | null) => void
}) {
  const queryClient = useQueryClient()
  const input = useRef<HTMLInputElement>(null)
  const [visibility, setVisibility] = useState<CommentVisibility>(staffOnly ? 'INTERNAL' : 'PUBLIC')
  const upload = useMutation({
    mutationFn: (file: File) => uploadAttachment(ticketId, file, visibility),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: queryKeys.attachments(ticketId),
      }),
  })

  async function onPick(file: File | undefined) {
    onFailure(null)
    if (!file) return
    try {
      if (file.size > MAX_ATTACHMENT_BYTES) {
        onFailure(UPLOAD_ERRORS.ATTACHMENT_TOO_LARGE)
        return
      }
      await upload.mutateAsync(file)
    } catch (error) {
      onFailure((isApiError(error) && uploadError(error.code)) || `${file.name} wasn’t attached. Please try again.`)
    } finally {
      if (input.current) input.current.value = ''
    }
  }

  return (
    <div className="grid gap-2">
      {canAddInternal && !staffOnly && (
        <fieldset className="flex gap-4 text-sm">
          <legend className="sr-only">Who can see this file</legend>
          {(
            [
              ['PUBLIC', 'Visible to requester'],
              ['INTERNAL', 'Internal file'],
            ] as const
          ).map(([value, label]) => (
            <label key={value} className="flex items-center gap-2">
              <input type="radio" name="attachment-visibility" value={value} checked={visibility === value} onChange={() => setVisibility(value)} />
              {label}
            </label>
          ))}
        </fieldset>
      )}
      <div className="flex flex-wrap items-center gap-3">
        <Label htmlFor="attachment-file">{upload.isPending ? 'Uploading…' : 'Attach a file'}</Label>
        <input
          ref={input}
          id="attachment-file"
          type="file"
          className="text-sm"
          accept={ACCEPTED_EXTENSIONS.map((extension) => `.${extension}`).join(',')}
          disabled={upload.isPending}
          onChange={(event) => onPick(event.target.files?.[0])}
        />
      </div>
      <p className="text-xs text-muted-foreground">Up to 10 MB: images, PDFs, Office documents, text, logs, CSV, JSON or zip.</p>
    </div>
  )
}

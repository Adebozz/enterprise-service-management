import { authenticatedFetch } from './client'
import { ApiError, type ApiProblem } from './errors'
import type { Attachment, CommentVisibility } from './types'

/** Matches the server's limit, so an oversized file is refused before it is sent. */
export const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024

/** The extensions the server accepts; also the file picker's filter. */
export const ACCEPTED_EXTENSIONS = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'pdf', 'zip', 'docx', 'xlsx', 'pptx', 'txt', 'log', 'csv', 'json']

function attachmentsUrl(ticketId: string) {
  return `${window.location.origin}/api/tickets/${encodeURIComponent(ticketId)}/attachments`
}

async function failure(response: Response): Promise<ApiError> {
  const problem = (await response.json().catch(() => null)) as ApiProblem | null
  return new ApiError(response.status, problem && typeof problem === 'object' && 'code' in problem ? problem : null)
}

/**
 * Multipart upload. The typed client only speaks JSON, so this uses the same authenticated fetch
 * (token and one refresh-and-retry included) directly.
 */
export async function uploadAttachment(ticketId: string, file: File, visibility: CommentVisibility): Promise<Attachment> {
  const form = new FormData()
  // The name is passed explicitly: not every FormData implementation takes it from the File.
  form.append('file', file, file.name)
  const url = `${attachmentsUrl(ticketId)}?visibility=${visibility}`
  const response = await authenticatedFetch(new Request(url, { method: 'POST', body: form }))
  if (!response.ok) throw await failure(response)
  return (await response.json()) as Attachment
}

/**
 * The download needs the access token, so a plain link can't fetch it: the file is fetched,
 * then handed to the browser as a temporary object URL.
 */
export async function downloadAttachment(ticketId: string, attachment: Attachment): Promise<void> {
  const url = `${attachmentsUrl(ticketId)}/${encodeURIComponent(attachment.id)}/content`
  const response = await authenticatedFetch(new Request(url))
  if (!response.ok) throw await failure(response)
  const objectUrl = URL.createObjectURL(await response.blob())
  try {
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = attachment.fileName
    document.body.append(link)
    link.click()
    link.remove()
  } finally {
    // Revoked on the next tick: the click has started the download by then.
    setTimeout(() => URL.revokeObjectURL(objectUrl), 0)
  }
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

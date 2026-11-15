import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Attachment, Ticket } from '@/api/types'
import { problem } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'
import { server } from '@/test/server'
import { aTicket, agent, requester, signedInAs } from '@/test/tickets'

function anAttachment(overrides: Partial<Attachment> = {}): Attachment {
  return {
    id: 'a-1',
    fileName: 'wifi-drops.txt',
    contentType: 'text/plain',
    sizeBytes: 2048,
    visibility: 'PUBLIC',
    uploadedBy: { id: requester.id, name: requester.displayName },
    createdAt: '2026-11-15T09:00:00Z',
    canDelete: true,
    ...overrides,
  }
}

/** One ticket whose files can be added and removed. */
function filesBackend(ticket: Ticket, initial: Attachment[] = [], options: { refuse?: boolean; storedAs?: string } = {}) {
  const state = {
    files: [...initial],
    uploads: [] as { visibility: string | null; multipart: boolean }[],
  }
  server.use(
    http.get('/api/tickets/:id', () => HttpResponse.json(ticket)),
    http.get('/api/tickets/:id/comments', () => HttpResponse.json([])),
    http.get('/api/tickets/:id/transitions', () => HttpResponse.json([])),
    http.get('/api/tickets/:id/history', () => HttpResponse.json([])),
    http.get('/api/tickets/:id/sla', () => problem(404, 'RESOURCE_NOT_FOUND')),
    http.get('/api/tickets/:id/attachments', () => HttpResponse.json(state.files)),
    http.post('/api/tickets/:id/attachments', async ({ request }) => {
      // jsdom's File isn't the File that Node's fetch knows, so the file's name and content don't
      // survive the trip in tests (they do in a browser, and the API tests cover the server side).
      // What the app sends alongside the file is checked here; each test says what comes back.
      const visibility = new URL(request.url).searchParams.get('visibility')
      state.uploads.push({
        visibility,
        multipart: request.headers.get('content-type')?.startsWith('multipart/form-data') ?? false,
      })
      if (options.refuse) {
        return problem(415, 'ATTACHMENT_TYPE_NOT_ALLOWED', 'Files of this type can’t be attached.')
      }
      const created = anAttachment({
        id: `a-${state.files.length + 1}`,
        fileName: options.storedAs ?? 'uploaded.txt',
      })
      state.files.push(created)
      return HttpResponse.json(created, { status: 201 })
    }),
    http.delete('/api/tickets/:id/attachments/:attachmentId', ({ params }) => {
      state.files = state.files.filter((file) => file.id !== params.attachmentId)
      return new HttpResponse(null, { status: 204 })
    }),
    http.get('/api/tickets/:id/attachments/:attachmentId/content', () =>
      HttpResponse.text('09:12 disconnected', {
        headers: { 'Content-Type': 'text/plain' },
      }),
    ),
  )
  return state
}

afterEach(() => vi.restoreAllMocks())

describe('ticket files', () => {
  it('lists files with their size and uploader, and attaches a new one', async () => {
    signedInAs(requester)
    const backend = filesBackend(aTicket(), [anAttachment()], {
      storedAs: 'error.log',
    })
    const { user } = renderApp('/tickets/ticket-1')

    const list = await screen.findByRole('list', { name: 'Attached files' })
    expect(within(list).getByRole('button', { name: 'wifi-drops.txt' })).toBeVisible()
    expect(within(list).getByText(/2 KB/)).toBeVisible()

    await user.upload(screen.getByLabelText('Attach a file'), new File(['hello'], 'error.log', { type: 'text/plain' }))

    expect(await within(list).findByRole('button', { name: 'error.log' })).toBeVisible()
    expect(backend.uploads).toEqual([{ visibility: 'PUBLIC', multipart: true }])
    // Requesters aren't offered internal files.
    expect(screen.queryByLabelText('Internal file')).not.toBeInTheDocument()
  })

  it('explains a refused file using the server’s code', async () => {
    signedInAs(requester)
    filesBackend(aTicket(), [], { refuse: true })
    const { user } = renderApp('/tickets/ticket-1')

    // An accepted extension whose content the server finds doesn't match it.
    await user.upload(await screen.findByLabelText('Attach a file'), new File(['<script>'], 'photo.png', { type: 'image/png' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('its content doesn’t match its extension')
  })

  it('refuses files over 10 MB without sending them', async () => {
    signedInAs(requester)
    const backend = filesBackend(aTicket())
    const { user } = renderApp('/tickets/ticket-1')
    const big = new File(['x'], 'huge.zip', { type: 'application/zip' })
    Object.defineProperty(big, 'size', { value: 11 * 1024 * 1024 })

    await user.upload(await screen.findByLabelText('Attach a file'), big)

    expect(await screen.findByRole('alert')).toHaveTextContent('Files can be up to 10 MB.')
    expect(backend.uploads).toHaveLength(0)
  })

  it('lets staff add internal files and marks them as such', async () => {
    signedInAs(agent)
    const backend = filesBackend(
      aTicket(),
      [
        anAttachment({
          id: 'a-9',
          fileName: 'scan.csv',
          visibility: 'INTERNAL',
          canDelete: false,
        }),
      ],
      { storedAs: 'trace.txt' },
    )
    const { user } = renderApp('/tickets/ticket-1')

    expect(await screen.findByText(/Internal: support staff only/)).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Remove scan.csv' })).not.toBeInTheDocument()

    await user.click(screen.getByLabelText('Internal file'))
    await user.upload(screen.getByLabelText('Attach a file'), new File(['x'], 'trace.txt', { type: 'text/plain' }))

    await screen.findByRole('button', { name: 'trace.txt' })
    expect(backend.uploads).toEqual([{ visibility: 'INTERNAL', multipart: true }])
  })

  it('removes a file and downloads one through an object URL', async () => {
    signedInAs(requester)
    filesBackend(aTicket(), [anAttachment(), anAttachment({ id: 'a-2', fileName: 'photo.png' })])
    const createObjectURL = vi.fn(() => 'blob:file')
    const revokeObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL, revokeObjectURL })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const { user } = renderApp('/tickets/ticket-1')

    await user.click(await screen.findByRole('button', { name: 'Remove photo.png' }))
    await vi.waitFor(() => expect(screen.queryByRole('button', { name: 'photo.png' })).not.toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: 'wifi-drops.txt' }))
    await vi.waitFor(() => expect(click).toHaveBeenCalledOnce())
    expect(createObjectURL).toHaveBeenCalledOnce()
  })

  it('takes no new files on a closed ticket', async () => {
    signedInAs(requester)
    filesBackend(aTicket({ status: 'CLOSED' }), [anAttachment({ canDelete: false })])
    renderApp('/tickets/ticket-1')

    expect(await screen.findByRole('button', { name: 'wifi-drops.txt' })).toBeVisible()
    expect(screen.queryByLabelText('Attach a file')).not.toBeInTheDocument()
  })
})

import { HttpResponse, http } from 'msw'
import { setupServer } from 'msw/node'
import { problem } from './fixtures'

/**
 * MSW intercepts the app's real fetch calls, so tests exercise the actual API client, token
 * handling and refresh logic, not a mocked module. Default: nobody is signed in, tickets have no
 * files and nobody has unread notifications (tests about those replace the handlers).
 */
export const server = setupServer(
  http.post('/api/auth/refresh', () => problem(401, 'REFRESH_TOKEN_INVALID')),
  http.get('/api/tickets/:id/attachments', () => HttpResponse.json([])),
  http.get('/api/notifications/unread-count', () => HttpResponse.json({ count: 0 })),
)

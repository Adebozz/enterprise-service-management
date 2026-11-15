import { HttpResponse, http } from 'msw'
import { setupServer } from 'msw/node'
import { problem } from './fixtures'

/**
 * MSW intercepts the app's real fetch calls, so tests exercise the actual API client, token
 * handling and refresh logic, not a mocked module. Default: nobody is signed in, and tickets have
 * no files (tests about files replace that handler).
 */
export const server = setupServer(
  http.post('/api/auth/refresh', () => problem(401, 'REFRESH_TOKEN_INVALID')),
  http.get('/api/tickets/:id/attachments', () => HttpResponse.json([])),
)

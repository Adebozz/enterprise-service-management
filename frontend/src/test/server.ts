import { http } from 'msw'
import { setupServer } from 'msw/node'
import { problem } from './fixtures'

/**
 * MSW intercepts the app's real fetch calls, so tests exercise the actual API client, token
 * handling and refresh logic, not a mocked module. Default: nobody is signed in.
 */
export const server = setupServer(http.post('/api/auth/refresh', () => problem(401, 'REFRESH_TOKEN_INVALID')))

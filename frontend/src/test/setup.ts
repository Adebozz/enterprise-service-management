import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { tokenStore } from '@/auth/tokenStore'
import { server } from './server'

// Any request without a handler fails the test: no silent calls to unexpected endpoints.
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  cleanup()
  server.resetHandlers()
  tokenStore.clear()
})
afterAll(() => server.close())

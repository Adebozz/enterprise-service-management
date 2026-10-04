import { QueryClient } from '@tanstack/react-query'
import { isApiError } from '@/api/errors'

/**
 * Server state lives in TanStack Query. Client errors (4xx) are answers, not glitches, so they
 * are never retried; server and network failures get one retry.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        retry: (failureCount, error) =>
          !(isApiError(error) && error.status >= 400 && error.status < 500) && failureCount < 1,
      },
      mutations: { retry: false },
    },
  })
}

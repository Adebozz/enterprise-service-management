import { isApiError } from '@/api/errors'

/**
 * A message for an admin form. The server's messages are written for people ("Would leave no active
 * administrator"), so business errors show them; a stale version gets its own explanation.
 */
export function adminErrorMessage(error: unknown, what: string): string {
  if (!isApiError(error)) return `${what} couldn’t be saved. Please try again.`
  if (error.code === 'CONCURRENT_MODIFICATION') return 'Someone else changed this meanwhile. It has been reloaded; please try again.'
  if (error.code === 'VALIDATION_FAILED' && error.problem?.fieldErrors?.length) {
    return error.problem.fieldErrors.map((fe) => `${fe.field}: ${fe.message}`).join('; ')
  }
  return error.message || `${what} couldn’t be saved. Please try again.`
}

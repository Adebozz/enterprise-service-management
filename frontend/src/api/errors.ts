import type { components } from './schema'

export type ApiProblem = components['schemas']['ApiProblem']
export type ErrorCode = ApiProblem['code']

/**
 * Every failed API call surfaces as an ApiError carrying the server's RFC 9457 problem body.
 * UI code branches on `code` (a typed union generated from the contract), never on message text.
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: ErrorCode | 'NETWORK_ERROR' | 'UNEXPECTED_RESPONSE'
  readonly problem: ApiProblem | null

  constructor(status: number, problem: ApiProblem | null, fallbackMessage = 'Request failed') {
    super(problem?.detail ?? fallbackMessage)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
    this.code = problem?.code ?? (status === 0 ? 'NETWORK_ERROR' : 'UNEXPECTED_RESPONSE')
  }

  get correlationId(): string | null {
    return this.problem?.correlationId ?? null
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError
}

function isProblem(value: unknown): value is ApiProblem {
  return typeof value === 'object' && value !== null && 'code' in value && 'status' in value
}

/**
 * Turns an openapi-fetch result into data or a thrown ApiError, so TanStack Query and forms can use
 * ordinary promise rejection for failures.
 */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.response.ok && result.error === undefined) {
    return result.data as T
  }
  throw new ApiError(result.response.status, isProblem(result.error) ? result.error : null)
}

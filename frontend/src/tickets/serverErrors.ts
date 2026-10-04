import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'
import { isApiError } from '@/api/errors'

/**
 * Applies a server rejection to a form: field errors go next to their fields; anything else
 * becomes a form-level message. The client validates first for speed, but the server decides.
 */
export function applyServerErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  fields: readonly Path<T>[],
  codeToField: Partial<Record<string, Path<T>>> = {},
): string | null {
  if (!isApiError(error)) {
    return 'Something went wrong. Please try again.'
  }
  const mapped = codeToField[error.code]
  if (mapped) {
    setError(mapped, { message: error.message })
    return null
  }
  const fieldErrors = (error.problem?.fieldErrors ?? []).filter((fe) => (fields as readonly string[]).includes(fe.field))
  if (fieldErrors.length > 0) {
    fieldErrors.forEach((fe) => setError(fe.field as Path<T>, { message: fe.message }))
    return null
  }
  return error.message || 'Something went wrong. Please try again.'
}

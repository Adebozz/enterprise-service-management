import { isApiError } from '@/api/errors'
import { Alert, AlertDescription } from '@/components/ui/alert'

/** Standard failure message for a query, with the correlation id support staff can trace. */
export function QueryError({ error, what }: { error: unknown; what: string }) {
  const reference = isApiError(error) ? error.correlationId : null
  return (
    <Alert variant="destructive">
      <AlertDescription>
        {what} couldn’t be loaded. Please try again.
        {reference && <span className="block text-xs opacity-80">Reference: {reference}</span>}
      </AlertDescription>
    </Alert>
  )
}

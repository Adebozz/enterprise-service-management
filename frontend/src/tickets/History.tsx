import { useQuery } from '@tanstack/react-query'
import { historyQuery } from '@/api/queries'
import { QueryError } from '@/components/QueryError'
import { Skeleton } from '@/components/ui/skeleton'
import { describe, text } from './historyText'
import { formatDateTime } from './labels'

/** Audit timeline for support staff (the API refuses it to everyone else). */
export function History({ ticketId }: { ticketId: string }) {
  const history = useQuery(historyQuery(ticketId))
  return (
    <section aria-labelledby="history-heading" className="grid gap-3">
      <h2 id="history-heading" className="text-lg font-semibold">
        History
      </h2>
      {history.isPending && <Skeleton className="h-24 w-full" />}
      {history.isError && <QueryError error={history.error} what="The history" />}
      {history.isSuccess && (
        <ol className="grid gap-2 border-l pl-4">
          {history.data.map((entry, index) => (
            <li key={`${entry.occurredAt}-${index}`} className="text-sm">
              <span className="font-medium">{entry.actor?.name ?? 'System'}</span> {describe(entry)}
              {text(entry.metadata, 'reason') && <span className="block text-muted-foreground">“{text(entry.metadata, 'reason')}”</span>}
              <time dateTime={entry.occurredAt} className="block text-xs text-muted-foreground">
                {formatDateTime(entry.occurredAt)}
              </time>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}

import { useQuery } from '@tanstack/react-query'
import { isApiError } from '@/api/errors'
import { slaQuery } from '@/api/queries'
import type { SlaClock } from '@/api/types'
import { QueryError } from '@/components/QueryError'
import { Skeleton } from '@/components/ui/skeleton'
import { formatAge, formatDateTime } from './labels'
import { SlaBadge } from './badges'
import { SLA_METRIC_LABEL, formatMinutes } from './sla'

/** The ticket's response and resolution clocks, for support staff handling it. */
export function SlaPanel({ ticketId }: { ticketId: string }) {
  const sla = useQuery(slaQuery(ticketId))

  // An agent can see a ticket they raised themselves in another team's queue, but not its service
  // levels: the API says so with ACCESS_DENIED, and the panel simply isn't shown.
  if (sla.isError && isApiError(sla.error) && sla.error.code === 'ACCESS_DENIED') return null

  return (
    <section aria-labelledby="sla-heading" className="grid gap-3 rounded-md border p-4">
      <h2 id="sla-heading" className="font-semibold">
        Service levels
      </h2>
      {sla.isPending && <Skeleton className="h-16 w-full" />}
      {sla.isError && <QueryError error={sla.error} what="The service levels" />}
      {sla.isSuccess && sla.data.clocks.length === 0 && (
        <p className="text-sm text-muted-foreground">No targets: this ticket was raised before service levels were tracked.</p>
      )}
      {sla.isSuccess && sla.data.clocks.length > 0 && (
        <dl className="grid gap-3 text-sm">
          {sla.data.clocks.map((clock) => (
            <div key={clock.metric} className="grid gap-1">
              <dt className="flex items-center justify-between gap-2">
                <span className="font-medium">{SLA_METRIC_LABEL[clock.metric]}</span>
                <SlaBadge status={clock.status} />
              </dt>
              <dd className="text-muted-foreground">
                Target {formatMinutes(clock.targetMinutes)}. {progress(clock)}
              </dd>
            </div>
          ))}
        </dl>
      )}
    </section>
  )
}

function progress(clock: SlaClock): string {
  const remaining = formatMinutes(clock.remainingSeconds / 60)
  switch (clock.status) {
    case 'ON_TRACK':
    case 'AT_RISK':
      return clock.dueAt ? `Due ${formatAge(clock.dueAt)} (${formatDateTime(clock.dueAt)}).` : `${remaining} left.`
    case 'BREACHED':
      return clock.breachedAt ? `Breached ${formatAge(clock.breachedAt)}.` : `Overdue by ${remaining}.`
    case 'PAUSED':
      return `Paused while waiting, with ${remaining} left.`
    case 'MET':
    case 'MISSED':
      return clock.completedAt ? `Done ${formatDateTime(clock.completedAt)}.` : ''
    case 'CANCELLED':
      return 'The ticket was cancelled.'
  }
}

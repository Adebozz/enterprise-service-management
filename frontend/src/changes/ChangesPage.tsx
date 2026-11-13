import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router'
import { changeScheduleQuery, ticketListQuery } from '@/api/queries'
import type { ScheduledChange } from '@/api/types'
import { Pagination } from '@/components/Pagination'
import { QueryError } from '@/components/QueryError'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { PriorityBadge, StatusBadge } from '@/tickets/badges'
import { CHANGE_TYPE_LABEL, RISK_LABEL, formatAge } from '@/tickets/labels'
import { formatWindow } from './window'

/**
 * Changes for all support staff: the schedule of what is about to happen to live services, so
 * teams don't work on the same thing at once, and every change being planned or carried out.
 */
export function ChangesPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const showAll = searchParams.get('show') === 'all'
  const page = Number(searchParams.get('page') ?? '0')
  const changes = useQuery(
    ticketListQuery({ view: 'ALL', type: ['CHANGE'], open: showAll ? undefined : true, page, size: 20, sort: ['updatedAt,desc'] }),
  )

  return (
    <section className="grid gap-8">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="grid gap-1">
          <h1 className="text-2xl font-semibold">Changes</h1>
          <p className="text-muted-foreground">Planned changes to live services, approved before they happen.</p>
        </div>
        <Button asChild>
          <Link to="/changes/new">Raise a change</Link>
        </Button>
      </div>

      <Schedule />

      <section aria-labelledby="all-changes-heading" className="grid gap-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 id="all-changes-heading" className="text-lg font-semibold">
            {showAll ? 'All changes' : 'Open changes'}
          </h2>
          <div className="flex gap-2" role="group" aria-label="Which changes">
            <Button variant={showAll ? 'outline' : 'secondary'} size="sm" aria-pressed={!showAll} onClick={() => setSearchParams({})}>
              Open
            </Button>
            <Button variant={showAll ? 'secondary' : 'outline'} size="sm" aria-pressed={showAll} onClick={() => setSearchParams({ show: 'all' })}>
              All
            </Button>
          </div>
        </div>
        {changes.isPending && <Skeleton className="h-48 w-full" />}
        {changes.isError && <QueryError error={changes.error} what="Changes" />}
        {changes.isSuccess &&
          (changes.data.content.length === 0 ? (
            <p className="rounded-md border border-dashed p-8 text-center text-muted-foreground">
              {showAll ? 'No changes have been raised yet.' : 'No open changes.'}
            </p>
          ) : (
            <>
              <ul className="divide-y rounded-md border">
                {changes.data.content.map((c) => (
                  <li key={c.id} className="grid gap-1 p-4">
                    <div className="flex flex-wrap items-center gap-2 text-sm">
                      <span className="font-mono text-muted-foreground">{c.reference}</span>
                      <StatusBadge status={c.status} />
                      <PriorityBadge priority={c.priority} />
                    </div>
                    <Link to={`/tickets/${c.id}`} className="font-medium underline-offset-4 hover:underline">
                      {c.title}
                    </Link>
                    <p className="text-sm text-muted-foreground">
                      {c.assignedTeam.name} · {c.assignee?.name ?? 'Unassigned'} · updated {formatAge(c.updatedAt)}
                    </p>
                  </li>
                ))}
              </ul>
              <Pagination
                page={changes.data.page}
                totalPages={changes.data.totalPages}
                onChange={(next) => setSearchParams({ ...(showAll ? { show: 'all' } : {}), page: String(next) })}
              />
            </>
          ))}
      </section>
    </section>
  )
}

function Schedule() {
  const schedule = useQuery(changeScheduleQuery)
  return (
    <section aria-labelledby="schedule-heading" className="grid gap-4">
      <div className="grid gap-1">
        <h2 id="schedule-heading" className="text-lg font-semibold">
          Schedule
        </h2>
        <p className="text-sm text-muted-foreground">Changes awaiting approval, approved or under way, soonest first.</p>
      </div>
      {schedule.isPending && <Skeleton className="h-32 w-full" />}
      {schedule.isError && <QueryError error={schedule.error} what="The change schedule" />}
      {schedule.isSuccess &&
        (schedule.data.length === 0 ? (
          <p className="rounded-md border border-dashed p-6 text-center text-muted-foreground">Nothing is scheduled.</p>
        ) : (
          <ol aria-label="Change schedule" className="divide-y rounded-md border">
            {schedule.data.map((change) => (
              <ScheduleEntry key={change.id} change={change} overlaps={overlapsAnother(change, schedule.data)} />
            ))}
          </ol>
        ))}
    </section>
  )
}

function ScheduleEntry({ change, overlaps }: { change: ScheduledChange; overlaps: boolean }) {
  return (
    <li className="grid gap-1 p-4 sm:grid-cols-[14rem_1fr] sm:gap-4">
      <p className="text-sm font-medium tabular-nums">{formatWindow(change.plannedStart, change.plannedEnd)}</p>
      <div className="grid gap-1">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <span className="font-mono text-muted-foreground">{change.reference}</span>
          <StatusBadge status={change.status} />
          <Badge variant="outline">{CHANGE_TYPE_LABEL[change.changeType]}</Badge>
          <Badge variant="outline">{RISK_LABEL[change.risk]} risk</Badge>
          {overlaps && <Badge variant="destructive">Overlaps another change</Badge>}
        </div>
        <Link to={`/tickets/${change.id}`} className="font-medium underline-offset-4 hover:underline">
          {change.title}
        </Link>
        <p className="text-sm text-muted-foreground">
          {change.team.name} · {change.assignee?.name ?? 'Unassigned'}
        </p>
      </div>
    </li>
  )
}

function overlapsAnother(change: ScheduledChange, all: ScheduledChange[]): boolean {
  const start = Date.parse(change.plannedStart)
  const end = Date.parse(change.plannedEnd)
  return all.some((other) => other.id !== change.id && Date.parse(other.plannedStart) < end && Date.parse(other.plannedEnd) > start)
}
